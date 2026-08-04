package net.veloclient.launcher.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.List;

/**
 * Implements the standard Minecraft Server List Ping protocol (the same
 * handshake every vanilla client uses to show MOTD/player count/version in
 * the multiplayer screen) so the launcher's "My Servers" widget
 * (design spec section 4) can show live TPS-adjacent status - online count,
 * MOTD, version - for any server address the user adds, without needing a
 * plugin on the server side. No authentication, no gameplay packets.
 */
public final class ServerPinger {

	private static final Gson GSON = new Gson();
	private static final int TIMEOUT_MILLIS = 4000;

	private ServerPinger() {
	}

	/** @param faviconPngBase64 nullable - the raw base64 payload of the server's 64x64 favicon PNG (the "favicon" field vanilla's own multiplayer screen shows), without the {@code data:image/png;base64,} prefix. */
	public record PingResult(List<MotdText.Segment> motd, int onlinePlayers, int maxPlayers, String versionName,
			long latencyMillis, String faviconPngBase64) {
	}

	public static PingResult ping(String host, int port) throws IOException {
		long start = System.currentTimeMillis();
		// Vanilla resolves a `_minecraft._tcp.<host>` SRV record whenever no
		// explicit port was given and connects to whatever host:port that
		// points at instead - a lot of larger networks rely on this to run
		// off a non-default port or a separate load-balanced frontend
		// without every player needing to type ":port". The launcher's own
		// "My Servers" port field always has *some* number in it (defaults
		// to 25565 in the edit dialog), so there's no way to tell "the user
		// really meant 25565" from "this field is just at its default" -
		// attempting SRV whenever the port is exactly 25565 covers the
		// overwhelming real-world case (servers relying on SRV do so
		// specifically because they're *not* on the default port) while
		// never overriding a genuinely-non-default port the user set.
		InetSocketAddress connectTarget = new InetSocketAddress(host, port);
		if (port == 25565) {
			InetSocketAddress srvTarget = resolveSrv(host);
			if (srvTarget != null) {
				connectTarget = srvTarget;
			}
		}
		try (Socket socket = new Socket()) {
			socket.connect(connectTarget, TIMEOUT_MILLIS);
			socket.setSoTimeout(TIMEOUT_MILLIS);
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// The *original* host (not the SRV target) goes in the
			// handshake - virtual-host-routing proxies (BungeeCord/Velocity
			// backends behind one shared frontend) key off this field, and
			// it should still be what the player actually typed/connected to.
			writePacket(out, handshakePacket(host, connectTarget.getPort()));
			writePacket(out, new byte[] {0x00});

			int length = readVarInt(in);
			int packetId = readVarInt(in);
			if (packetId != 0x00) {
				throw new IOException("Unexpected packet id " + packetId);
			}
			String json = readString(in);
			long latency = System.currentTimeMillis() - start;

			JsonObject root = GSON.fromJson(json, JsonObject.class);
			List<MotdText.Segment> motd = MotdText.parse(root.has("description") ? root.get("description") : null);
			int online = 0;
			int max = 0;
			if (root.has("players")) {
				JsonObject players = root.getAsJsonObject("players");
				online = players.has("online") ? players.get("online").getAsInt() : 0;
				max = players.has("max") ? players.get("max").getAsInt() : 0;
			}
			String version = root.has("version") && root.getAsJsonObject("version").has("name")
					? root.getAsJsonObject("version").get("name").getAsString()
					: "unknown";
			String favicon = null;
			if (root.has("favicon") && !root.get("favicon").isJsonNull()) {
				String raw = root.get("favicon").getAsString();
				int comma = raw.indexOf(',');
				favicon = comma >= 0 ? raw.substring(comma + 1) : raw;
			}
			return new PingResult(motd, online, max, version, latency, favicon);
		}
	}

	/** @return the SRV record's (target, port), or null if there isn't one/the lookup fails - never throws, since this is a best-effort hint layered on top of a plain host:port connect that must still work without it. */
	private static InetSocketAddress resolveSrv(String host) {
		Hashtable<String, String> env = new Hashtable<>();
		env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
		env.put("java.naming.provider.url", "dns:");
		env.put("com.sun.jndi.dns.timeout.initial", "1500");
		env.put("com.sun.jndi.dns.timeout.retries", "1");
		try {
			InitialDirContext ctx = new InitialDirContext(env);
			try {
				Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"});
				Attribute srv = attrs.get("SRV");
				if (srv == null || srv.size() == 0) {
					return null;
				}
				// SRV record data: "<priority> <weight> <port> <target>" - only one record is ever registered for this service in practice, so just take the first.
				String[] parts = String.valueOf(srv.get(0)).trim().split("\\s+");
				if (parts.length < 4) {
					return null;
				}
				int srvPort = Integer.parseInt(parts[2]);
				String target = parts[3];
				if (target.endsWith(".")) {
					target = target.substring(0, target.length() - 1);
				}
				return target.isEmpty() ? null : new InetSocketAddress(target, srvPort);
			} finally {
				ctx.close();
			}
		} catch (Exception e) {
			// No SRV record, DNS doesn't support it, or the lookup timed out - fall back to the plain host:port.
			return null;
		}
	}

	private static byte[] handshakePacket(String host, int port) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		DataOutputStream data = new DataOutputStream(body);
		data.writeByte(0x00);
		writeVarInt(data, -1);
		writeString(data, host);
		data.writeShort(port);
		writeVarInt(data, 1);
		return body.toByteArray();
	}

	private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
		ByteArrayOutputStream framed = new ByteArrayOutputStream();
		DataOutputStream data = new DataOutputStream(framed);
		writeVarInt(data, payload.length);
		data.write(payload);
		out.write(framed.toByteArray());
		out.flush();
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while (true) {
			if ((value & ~0x7F) == 0) {
				out.writeByte(value);
				return;
			}
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int result = 0;
		int position = 0;
		while (true) {
			byte b = in.readByte();
			result |= (b & 0x7F) << position;
			if ((b & 0x80) == 0) {
				return result;
			}
			position += 7;
			if (position >= 32) {
				throw new IOException("VarInt too big");
			}
		}
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = readVarInt(in);
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
