package net.veloclient.launcher.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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

	public record PingResult(String motd, int onlinePlayers, int maxPlayers, String versionName, long latencyMillis) {
	}

	public static PingResult ping(String host, int port) throws IOException {
		long start = System.currentTimeMillis();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
			socket.setSoTimeout(TIMEOUT_MILLIS);
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			writePacket(out, handshakePacket(host, port));
			writePacket(out, new byte[] {0x00});

			int length = readVarInt(in);
			int packetId = readVarInt(in);
			if (packetId != 0x00) {
				throw new IOException("Unexpected packet id " + packetId);
			}
			String json = readString(in);
			long latency = System.currentTimeMillis() - start;

			JsonObject root = GSON.fromJson(json, JsonObject.class);
			String motd = extractMotd(root);
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
			return new PingResult(motd, online, max, version, latency);
		}
	}

	private static String extractMotd(JsonObject root) {
		if (!root.has("description")) {
			return "";
		}
		var description = root.get("description");
		if (description.isJsonPrimitive()) {
			return description.getAsString();
		}
		JsonObject obj = description.getAsJsonObject();
		return obj.has("text") ? obj.get("text").getAsString() : "";
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
