package net.veloclient.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Velo World co-op (the launcher's Home mini-game): the party leader's launcher hosts the game, the members connect to
 * it through this relay - the same port as World Hosting, lines starting with {@code ARCADE}. The relay only checks the
 * session and that both are in the same party, then copies bytes; the game itself runs on the leader's PC.
 * <ul>
 * <li>{@code ARCADE HOST <session>} - the leader's control connection; gets {@code OPEN <stream> <uuid> <name>}.</li>
 * <li>{@code ARCADE JOIN <session>} - a member; answered {@code OK} once the host opened a data connection.</li>
 * <li>{@code ARCADE DATA <session> <stream>} - the host's data connection for one member.</li>
 * </ul>
 */
final class ArcadeRelay {

	private static final int MAX_GUESTS = 7;
	private static final long OPEN_WAIT_MILLIS = 10_000;

	private record Host(String uuid, Socket control) {
	}

	private final SessionRegistry registry;
	private final PartyService parties;
	private final SocialService social;
	private final Map<String, Host> hosts = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Socket>> waiting = new ConcurrentHashMap<>();
	private final Map<String, Integer> guests = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();

	ArcadeRelay(SessionRegistry registry, PartyService parties, SocialService social) {
		this.registry = registry;
		this.parties = parties;
		this.social = social;
	}

	void handle(Socket socket, InputStream in, String[] parts) {
		try {
			String mode = parts.length > 1 ? parts[1] : "";
			String token = parts.length > 2 ? parts[2] : "";
			SessionRegistry.Session session = registry.session(token);
			if (session == null) {
				refuse(socket, "ERR session");
				return;
			}
			String uuid = session.uuid();
			String party = parties.partyIdOf(uuid);
			if (party == null) {
				refuse(socket, "ERR not in a party");
				return;
			}
			switch (mode) {
				case "HOST" -> host(socket, in, uuid, party);
				case "JOIN" -> join(socket, in, uuid, party);
				case "DATA" -> data(socket, in, uuid, party, parts.length > 3 ? parts[3] : "");
				default -> refuse(socket, "ERR mode");
			}
		} catch (Exception e) {
			close(socket);
		}
	}

	private void host(Socket socket, InputStream in, String uuid, String party) throws IOException {
		if (!parties.isLeader(uuid)) {
			refuse(socket, "ERR only the party leader hosts");
			return;
		}
		Host old = hosts.put(party, new Host(uuid, socket));
		if (old != null) {
			close(old.control());
		}
		socket.setSoTimeout(90_000);
		write(socket, "OK\n");
		try {
			while (readLine(in) != null) {
				// PING
			}
		} catch (IOException ignored) {
			// gone
		} finally {
			hosts.remove(party, new Host(uuid, socket));
			close(socket);
		}
	}

	private void join(Socket guest, InputStream in, String uuid, String party) throws Exception {
		Host host = hosts.get(party);
		if (host == null || host.control().isClosed()) {
			refuse(guest, "ERR host offline");
			return;
		}
		if (host.uuid().equals(uuid)) {
			refuse(guest, "ERR you are the host");
			return;
		}
		if (guests.merge(party, 1, Integer::sum) > MAX_GUESTS) {
			guests.merge(party, -1, Integer::sum);
			refuse(guest, "ERR full");
			return;
		}
		byte[] b = new byte[12];
		random.nextBytes(b);
		String stream = HexFormat.of().formatHex(b);
		CompletableFuture<Socket> hostSide = new CompletableFuture<>();
		waiting.put(stream, hostSide);
		try {
			String name = social.usernameOf(uuid);
			synchronized (host.control()) {
				write(host.control(), "OPEN " + stream + " " + uuid + " " + (name == null ? uuid : name) + "\n");
			}
			Socket hostData = hostSide.get(OPEN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
			write(guest, "OK\n");
			pipe(guest, in, hostData);
		} catch (Exception e) {
			refuse(guest, "ERR host didn't answer");
		} finally {
			waiting.remove(stream);
			guests.merge(party, -1, Integer::sum);
		}
	}

	private final Map<Socket, InputStream> pendingInputs = new ConcurrentHashMap<>();

	private void data(Socket socket, InputStream in, String uuid, String party, String stream) {
		Host host = hosts.get(party);
		CompletableFuture<Socket> pending = host != null && host.uuid().equals(uuid) ? waiting.remove(stream) : null;
		if (pending == null) {
			close(socket);
			return;
		}
		pendingInputs.put(socket, in);
		pending.complete(socket);
	}

	private void pipe(Socket guest, InputStream guestIn, Socket host) throws IOException {
		guest.setSoTimeout(0);
		host.setSoTimeout(0);
		InputStream hostIn = pendingInputs.remove(host);
		InputStream fromHost = hostIn != null ? hostIn : host.getInputStream();
		Thread back = Thread.ofVirtual().start(() -> copy(fromHost, guest, host));
		copy(guestIn, host, guest);
		try {
			back.join(5000);
		} catch (InterruptedException ignored) {
			// done
		}
	}

	private static void copy(InputStream in, Socket to, Socket other) {
		byte[] buffer = new byte[32 * 1024];
		try {
			OutputStream out = to.getOutputStream();
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				out.flush();
			}
		} catch (IOException ignored) {
			// one side closed
		} finally {
			close(to);
			close(other);
		}
	}

	private static void refuse(Socket socket, String why) {
		try {
			write(socket, why + "\n");
		} catch (IOException ignored) {
			// gone anyway
		}
		close(socket);
	}

	private static void write(Socket socket, String text) throws IOException {
		OutputStream out = socket.getOutputStream();
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) != -1) {
			if (c == '\n') {
				return sb.toString().trim();
			}
			if (sb.length() > 256) {
				throw new IOException("line too long");
			}
			sb.append((char) c);
		}
		return null;
	}

	private static void close(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// closed
			}
		}
	}
}
