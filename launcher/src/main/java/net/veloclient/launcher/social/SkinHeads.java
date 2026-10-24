package net.veloclient.launcher.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.data.VeloPaths;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin PNGs for any player (friends included), straight from Mojang's session server, cached in
 * {@code cache/heads/} - the same folder the game's {@code PlayerHeads} uses, so each skin is
 * downloaded once for both. Render with {@code PlayerHeadView.build(bytes, size)}.
 */
public final class SkinHeads {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final long TTL_MILLIS = 6 * 60 * 60 * 1000L;
	private static final Map<String, CompletableFuture<byte[]>> CACHE = net.veloclient.launcher.ui.LauncherCaches.lru(300);

	private SkinHeads() {
	}

	// Called (on the FX thread) with the uuid whose skin just changed.
	private static final java.util.List<java.util.function.Consumer<String>> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	public static void onChange(java.util.function.Consumer<String> listener) {
		LISTENERS.add(listener);
	}

	/**
	 * A skin known to be current (your own, fresh from Mojang's profile API or just uploaded): replaces
	 * the cached one - otherwise the 6 h disk cache kept showing the old face, even across restarts.
	 */
	public static void update(String uuid, byte[] png) {
		if (uuid == null || png == null) {
			return;
		}
		String key = uuid.replace("-", "").toLowerCase();
		Path file = VeloPaths.root().resolve("cache").resolve("heads").resolve(key + ".png");
		try {
			if (Files.exists(file) && java.util.Arrays.equals(Files.readAllBytes(file), png)) {
				Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
				return;
			}
			Files.createDirectories(file.getParent());
			Files.write(file, png);
		} catch (Exception ignored) {
			// memory cache below still updates
		}
		CACHE.put(key, CompletableFuture.completedFuture(png));
		javafx.application.Platform.runLater(() -> LISTENERS.forEach(listener -> listener.accept(key)));
	}

	public static CompletableFuture<byte[]> skin(String uuid) {
		String key = uuid.replace("-", "").toLowerCase();
		return CACHE.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(k)));
	}

	/**
	 * Re-reads a skin from Mojang's public session server, skipping the disk cache - for your own
	 * account when its login token can't ask the profile API (expired). Fires onChange if it differs.
	 */
	public static void refresh(String uuid) {
		if (uuid == null) {
			return;
		}
		String key = uuid.replace("-", "").toLowerCase();
		Path file = VeloPaths.root().resolve("cache").resolve("heads").resolve(key + ".png");
		byte[] before = null;
		try {
			before = Files.exists(file) ? Files.readAllBytes(file) : null;
		} catch (Exception ignored) {
			// treated as unknown
		}
		byte[] now = load(key, true);
		if (now != null && !java.util.Arrays.equals(before, now)) {
			CACHE.put(key, CompletableFuture.completedFuture(now));
			javafx.application.Platform.runLater(() -> LISTENERS.forEach(listener -> listener.accept(key)));
		}
	}

	private static byte[] load(String uuid) {
		return load(uuid, false);
	}

	private static byte[] load(String uuid, boolean fresh) {
		Path file = VeloPaths.root().resolve("cache").resolve("heads").resolve(uuid + ".png");
		try {
			if (!fresh && Files.exists(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < TTL_MILLIS) {
				return Files.readAllBytes(file);
			}
			HttpResponse<String> profile = HTTP.send(HttpRequest.newBuilder(
					URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid)).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (profile.statusCode() != 200) {
				return Files.exists(file) ? Files.readAllBytes(file) : null;
			}
			JsonObject json = JsonParser.parseString(profile.body()).getAsJsonObject();
			String encoded = json.getAsJsonArray("properties").get(0).getAsJsonObject().get("value").getAsString();
			JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) {
				return null;
			}
			String url = textures.getAsJsonObject("SKIN").get("url").getAsString().replace("http://", "https://");
			HttpResponse<byte[]> skin = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (skin.statusCode() != 200) {
				return null;
			}
			Files.createDirectories(file.getParent());
			Files.write(file, skin.body());
			return skin.body();
		} catch (Exception e) {
			try {
				return Files.exists(file) ? Files.readAllBytes(file) : null;
			} catch (Exception ignored) {
				return null;
			}
		}
	}
}
