package net.veloclient.launcher.sandbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Party co-op for Velo World, hosted by a player like Essential does it: the party leader's launcher runs the game
 * (world, monsters, bosses) and the members connect to it. Both sides only ever connect out - to the Velo relay (the
 * one World Hosting uses), which checks that everyone is in the same party and then just passes the bytes along.
 * Messages are JSON lines.
 */
final class Coop {

	static final String RELAY_HOST = System.getProperty("velo.arcadeRelay", "api.velo-client.com");
	static final int RELAY_PORT = Integer.getInteger("velo.arcadeRelayPort", 25600);

	private final boolean host;
	private final String token;
	private volatile boolean alive = true;
	private Socket control;
	private final Map<String, Peer> peers = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<>();

	private static final class Peer {
		final String id;
		final String name;
		final Socket socket;
		final OutputStream out;

		Peer(String id, String name, Socket socket) throws IOException {
			this.id = id;
			this.name = name;
			this.socket = socket;
			this.out = socket.getOutputStream();
		}
	}

	private Coop(boolean host, String token) {
		this.host = host;
		this.token = token;
	}

	boolean isHost() {
		return host;
	}

	boolean alive() {
		return alive;
	}

	int peerCount() {
		return peers.size();
	}

	/** Opens the host side (blocking; call off the FX thread). */
	static Coop host(String token) throws IOException {
		Coop coop = new Coop(true, token);
		Socket socket = open();
		write(socket.getOutputStream(), "ARCADE HOST " + token + "\n");
		String answer = readLine(socket.getInputStream());
		if (answer == null || !answer.startsWith("OK")) {
			socket.close();
			throw new IOException(answer == null ? "relay closed" : answer);
		}
		socket.setSoTimeout(0);
		coop.control = socket;
		Thread.ofVirtual().name("velo-arcade-host").start(() -> coop.controlLoop(socket));
		Thread.ofVirtual().name("velo-arcade-ping").start(() -> {
			while (coop.alive) {
				try {
					Thread.sleep(25_000);
					synchronized (socket) {
						write(socket.getOutputStream(), "PING\n");
					}
				} catch (Exception e) {
					coop.close();
				}
			}
		});
		return coop;
	}

	/** Connects to the party leader's game (blocking). */
	static Coop join(String token) throws IOException {
		Coop coop = new Coop(false, token);
		Socket socket = open();
		write(socket.getOutputStream(), "ARCADE JOIN " + token + "\n");
		String answer = readLine(socket.getInputStream());
		if (answer == null || !answer.startsWith("OK")) {
			socket.close();
			throw new IOException(answer == null ? "relay closed" : answer.replace("ERR ", ""));
		}
		socket.setSoTimeout(0);
		Peer peer = new Peer("host", "host", socket);
		coop.peers.put("host", peer);
		Thread.ofVirtual().name("velo-arcade-guest").start(() -> coop.readLoop(peer));
		return coop;
	}

	private static Socket open() throws IOException {
		Socket socket = new Socket();
		socket.setTcpNoDelay(true);
		socket.connect(new InetSocketAddress(RELAY_HOST, RELAY_PORT), 6000);
		socket.setSoTimeout(12_000);
		return socket;
	}

	private void controlLoop(Socket socket) {
		try {
			InputStream in = socket.getInputStream();
			String line;
			while (alive && (line = readLine(in)) != null) {
				String[] parts = line.split(" ", 4);
				if (parts[0].equals("OPEN") && parts.length >= 3) {
					String stream = parts[1];
					String uuid = parts[2];
					String name = parts.length > 3 ? parts[3] : uuid;
					Thread.ofVirtual().start(() -> accept(stream, uuid, name));
				}
			}
		} catch (IOException ignored) {
			// relay gone
		}
		close();
	}

	private void accept(String stream, String uuid, String name) {
		try {
			Socket data = open();
			write(data.getOutputStream(), "ARCADE DATA " + token + " " + stream + "\n");
			data.setSoTimeout(0);
			Peer old = peers.remove(uuid);
			if (old != null) {
				closeQuietly(old.socket);
			}
			Peer peer = new Peer(uuid, name, data);
			peers.put(uuid, peer);
			JsonObject joined = new JsonObject();
			joined.addProperty("t", "_join");
			joined.addProperty("_from", uuid);
			joined.addProperty("name", name);
			inbox.add(joined);
			readLoop(peer);
		} catch (IOException ignored) {
			// that guest didn't make it
		}
	}

	private void readLoop(Peer peer) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(peer.socket.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (alive && (line = reader.readLine()) != null) {
				if (line.length() > 2_000_000) {
					continue;
				}
				try {
					JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
					msg.addProperty("_from", peer.id);
					inbox.add(msg);
				} catch (Exception ignored) {
					// a broken line: skip it
				}
			}
		} catch (IOException ignored) {
			// gone
		}
		peers.remove(peer.id, peer);
		closeQuietly(peer.socket);
		JsonObject left = new JsonObject();
		left.addProperty("t", "_leave");
		left.addProperty("_from", peer.id);
		inbox.add(left);
		if (!host) {
			alive = false;
		}
	}

	/** Host: to every guest. Guest: to the host. */
	void send(JsonObject msg) {
		byte[] bytes = (msg + "\n").getBytes(StandardCharsets.UTF_8);
		for (Peer peer : peers.values()) {
			deliver(peer, bytes);
		}
	}

	void sendTo(String peerId, JsonObject msg) {
		Peer peer = peers.get(peerId);
		if (peer != null) {
			deliver(peer, (msg + "\n").getBytes(StandardCharsets.UTF_8));
		}
	}

	/** Host: to every guest except one. */
	void sendExcept(String peerId, JsonObject msg) {
		byte[] bytes = (msg + "\n").getBytes(StandardCharsets.UTF_8);
		for (Peer peer : peers.values()) {
			if (!peer.id.equals(peerId)) {
				deliver(peer, bytes);
			}
		}
	}

	private void deliver(Peer peer, byte[] bytes) {
		try {
			synchronized (peer) {
				peer.out.write(bytes);
				peer.out.flush();
			}
		} catch (IOException e) {
			closeQuietly(peer.socket);
		}
	}

	List<JsonObject> drain() {
		List<JsonObject> out = new ArrayList<>();
		JsonObject msg;
		while ((msg = inbox.poll()) != null) {
			out.add(msg);
		}
		return out;
	}

	void close() {
		alive = false;
		closeQuietly(control);
		for (Peer peer : peers.values()) {
			closeQuietly(peer.socket);
		}
		peers.clear();
	}

	private static void write(OutputStream out, String text) throws IOException {
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
			if (sb.length() > 512) {
				throw new IOException("line too long");
			}
			sb.append((char) c);
		}
		return sb.isEmpty() ? null : sb.toString();
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// closed
			}
		}
	}
}
