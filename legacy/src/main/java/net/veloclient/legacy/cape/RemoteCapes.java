package net.veloclient.legacy.cape;

import com.google.gson.JsonObject;
import net.veloclient.legacy.Json;
import net.veloclient.legacy.VeloPaths;
import net.veloclient.legacy.net.VeloNetwork;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * The picture behind a published cloak id: {@code custom:<hash>} from the server's cape store, a store item from your
 * own library, the bundled art or the cosmetics catalog. Downloads go to the same caches the modern client uses.
 */
public final class RemoteCapes {

	private static final int MAX_BYTES = 12 * 1024 * 1024;

	private RemoteCapes() {
	}

	/** Background thread only. Null = not available (yet). */
	public static byte[] bytes(String capeId) throws Exception {
		if (capeId.startsWith("custom:")) {
			String hash = capeId.substring("custom:".length());
			if (!hash.matches("[0-9a-f]{64}")) {
				return null;
			}
			return cached(VeloPaths.cache("remote-capes").resolve(hash), hash, "/v1/cape/" + hash);
		}
		LocalCape.Bundle own = LocalCape.bySourceItem(capeId);
		if (own != null) {
			byte[] art = LocalCape.art(own);
			if (art != null) {
				return art;
			}
		}
		try (InputStream bundled = RemoteCapes.class.getResourceAsStream("/assets/velo-client/textures/cape/" + capeId + ".gif")) {
			if (bundled != null) {
				return LocalCape.readAll(bundled);
			}
		}
		String base = VeloNetwork.base();
		if (base == null || !capeId.matches("[a-z0-9_]{1,64}")) {
			return null;
		}
		HttpRequest.Builder item = HttpRequest.newBuilder(URI.create(base + "/v1/cosmetics/item?id=" + URLEncoder.encode(capeId, StandardCharsets.UTF_8)))
				.header("Accept", "application/json").timeout(Duration.ofSeconds(20)).GET();
		String token = VeloNetwork.sessionToken();
		if (token != null) {
			item.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> response = VeloNetwork.HTTP.send(item.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Cloak " + capeId + ": HTTP " + response.statusCode());
		}
		JsonObject json = Json.parse(response.body());
		JsonObject files = json.has("files") && json.get("files").isJsonObject() ? json.getAsJsonObject("files") : null;
		String sha = Json.string(files, "gif");
		if (sha == null) {
			sha = Json.string(files, "texture");
		}
		if (sha == null || !sha.matches("[0-9a-f]{64}")) {
			throw new IOException("Cloak " + capeId + " has no art");
		}
		return cached(VeloPaths.cache("cosmetics").resolve("files").resolve(sha), sha, "/v1/cosmetics/file/" + sha);
	}

	private static byte[] cached(Path file, String sha, String path) throws Exception {
		if (Files.exists(file)) {
			byte[] bytes = Files.readAllBytes(file);
			if (LocalCape.sha256(bytes).equals(sha)) {
				return bytes;
			}
		}
		String base = VeloNetwork.base();
		if (base == null) {
			return null;
		}
		HttpResponse<byte[]> response = VeloNetwork.HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
				.timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		byte[] bytes = response.body();
		if (response.statusCode() != 200 || bytes.length > MAX_BYTES || !LocalCape.sha256(bytes).equals(sha)) {
			throw new IOException("Bad cloak download " + path + " (HTTP " + response.statusCode() + ")");
		}
		Files.createDirectories(file.getParent());
		Path tmp = Files.createTempFile(file.getParent(), "dl", ".tmp");
		Files.write(tmp, bytes);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return bytes;
	}
}
