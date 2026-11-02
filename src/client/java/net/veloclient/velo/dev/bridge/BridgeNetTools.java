package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.veloclient.velo.VeloClient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The network helpers a server can ask for: pings to other servers (real Minecraft status pings - bare connects get
 * flagged by anti-bot proxies), resource packs downloaded ahead of time straight into Minecraft's own pack cache
 * ({@code downloads/<pack uuid>/<sha1>}, which the game then uses without downloading again), and connections to
 * transfer targets warmed up (DNS + a status ping) so a transfer starts faster.
 */
final class BridgeNetTools {

	private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final long MAX_PACK_BYTES = 256L * 1024 * 1024;

	private BridgeNetTools() {
	}

	static void answerPings(String nonce, JsonArray servers) {
		POOL.submit(() -> {
			JsonObject results = new JsonObject();
			int n = 0;
			for (JsonElement e : servers) {
				if (n++ >= 16) {
					break;
				}
				String server = e.getAsString();
				results.addProperty(server, statusPing(server));
			}
			JsonObject answer = VeloBridgeClient.message("ping_result");
			answer.addProperty("nonce", nonce);
			answer.add("results", results);
			net.minecraft.client.MinecraftClient.getInstance().execute(() -> BridgeNet.send(answer.toString()));
		});
	}

	static void precache(JsonArray packs) {
		for (JsonElement e : packs) {
			JsonObject p = e.getAsJsonObject();
			String id = VeloBridgeClient.str(p, "id", "");
			String url = VeloBridgeClient.str(p, "url", "");
			String sha1 = VeloBridgeClient.str(p, "sha1", "").toLowerCase(java.util.Locale.ROOT);
			if (!sha1.matches("[0-9a-f]{40}") || !(url.startsWith("https://") || url.startsWith("http://"))) {
				continue;
			}
			UUID uuid;
			try {
				uuid = UUID.fromString(id);
			} catch (IllegalArgumentException ex) {
				continue;
			}
			POOL.submit(() -> download(uuid, url, sha1));
		}
	}

	/** Into Minecraft's own server pack cache, exactly where it looks: downloads/<id>/<sha1>. */
	private static void download(UUID id, String url, String sha1) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("downloads").resolve(id.toString());
		Path target = dir.resolve(sha1);
		try {
			if (Files.exists(target) && sha1(target).equals(sha1)) {
				return;
			}
			Files.createDirectories(dir);
			Path tmp = dir.resolve(sha1 + ".velo-tmp");
			HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() != 200) {
				return;
			}
			try (InputStream in = r.body(); var out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[64 * 1024];
				long total = 0;
				int read;
				while ((read = in.read(buf)) > 0) {
					total += read;
					if (total > MAX_PACK_BYTES) {
						throw new IOException("pack larger than 256 MB");
					}
					out.write(buf, 0, read);
				}
			}
			if (!sha1(tmp).equals(sha1)) {
				Files.deleteIfExists(tmp);
				VeloClient.LOGGER.info("Velo Bridge: pre-downloaded pack didn't match its sha1, dropped");
				return;
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception ex) {
			VeloClient.LOGGER.debug("Velo Bridge: pack pre-download failed", ex);
		}
	}

	static void preconnect(JsonArray hosts) {
		for (JsonElement e : hosts) {
			String host = e.getAsString();
			POOL.submit(() -> {
				try {
					InetAddress.getAllByName(host.replaceFirst(":\\d+$", ""));
					statusPing(host);
				} catch (Exception ignored) {
					// Only a warm-up.
				}
			});
		}
	}

	/** A real server list ping: handshake (next state status), status request, ping/pong. Milliseconds, -1 when unreachable. */
	static int statusPing(String server) {
		String host = server.replaceFirst(":\\d+$", "");
		int port = server.matches(".*:\\d+$") ? Integer.parseInt(server.substring(server.lastIndexOf(':') + 1)) : 25565;
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), 3000);
			socket.setSoTimeout(3000);
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());
			ByteArrayOutputStream hs = new ByteArrayOutputStream();
			DataOutputStream h = new DataOutputStream(hs);
			writeVarInt(h, 0x00);
			writeVarInt(h, -1);
			byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
			writeVarInt(h, hostBytes.length);
			h.write(hostBytes);
			h.writeShort(port);
			writeVarInt(h, 1);
			writeVarInt(out, hs.size());
			out.write(hs.toByteArray());
			out.write(new byte[] {0x01, 0x00});
			int len = readVarInt(in);
			in.skipNBytes(len);
			long start = System.nanoTime();
			out.write(new byte[] {0x09, 0x01});
			out.writeLong(start);
			readVarInt(in);
			readVarInt(in);
			in.readLong();
			return (int) ((System.nanoTime() - start) / 1_000_000);
		} catch (Exception e) {
			return -1;
		}
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int value = 0;
		int position = 0;
		while (true) {
			byte b = in.readByte();
			value |= (b & 0x7F) << position;
			if ((b & 0x80) == 0) {
				return value;
			}
			position += 7;
			if (position >= 32) {
				throw new IOException("VarInt too big");
			}
		}
	}

	private static String sha1(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buf = new byte[64 * 1024];
			int read;
			while ((read = in.read(buf)) > 0) {
				digest.update(buf, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
