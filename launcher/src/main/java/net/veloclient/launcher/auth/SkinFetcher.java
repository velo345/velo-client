package net.veloclient.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Fetches the signed-in player's own skin PNG (plus its CLASSIC/SLIM model
 * variant - the 3D preview needs to know since slim ("Alex") arms are a
 * pixel narrower than classic ("Steve") ones, at the same UV origin) from
 * Mojang's authenticated profile endpoint and caches both locally.
 */
public final class SkinFetcher {

	private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public record SkinData(byte[] pngBytes, boolean slim) {
	}

	private SkinFetcher() {
	}

	/** @return the cached/downloaded skin, or null if it couldn't be fetched (offline, no skin set, etc). */
	public static SkinData fetch(MinecraftSession session) {
		Path cacheFile = VeloPaths.root().resolve("cache").resolve("skins").resolve(session.uuid() + ".png");
		Path variantFile = VeloPaths.root().resolve("cache").resolve("skins").resolve(session.uuid() + ".variant");
		try {
			Fetched fetched = fetchSkinInfo(session.minecraftAccessToken());
			if (fetched == null) {
				return readCached(cacheFile, variantFile);
			}
			byte[] bytes = download(fetched.url());
			Files.createDirectories(cacheFile.getParent());
			Files.write(cacheFile, bytes);
			Files.writeString(variantFile, fetched.slim() ? "slim" : "classic", StandardCharsets.UTF_8);
			return new SkinData(bytes, fetched.slim());
		} catch (Exception e) {
			return readCached(cacheFile, variantFile);
		}
	}

	private static SkinData readCached(Path cacheFile, Path variantFile) {
		try {
			if (!Files.exists(cacheFile)) {
				return null;
			}
			boolean slim = Files.exists(variantFile) && Files.readString(variantFile, StandardCharsets.UTF_8).strip().equals("slim");
			return new SkinData(Files.readAllBytes(cacheFile), slim);
		} catch (IOException e) {
			return null;
		}
	}

	private record Fetched(String url, boolean slim) {
	}

	private static Fetched fetchSkinInfo(String accessToken) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE_URL))
				.header("Authorization", "Bearer " + accessToken)
				.timeout(Duration.ofSeconds(15))
				.GET().build();
		HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " fetching Minecraft profile");
		}
		JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
		if (!body.has("skins")) {
			return null;
		}
		JsonArray skins = body.getAsJsonArray("skins");
		for (var element : skins) {
			JsonObject skin = element.getAsJsonObject();
			if ("ACTIVE".equalsIgnoreCase(skin.get("state").getAsString())) {
				boolean slim = skin.has("variant") && "SLIM".equalsIgnoreCase(skin.get("variant").getAsString());
				return new Fetched(skin.get("url").getAsString(), slim);
			}
		}
		return null;
	}

	private static byte[] download(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
		HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " downloading skin");
		}
		return response.body();
	}
}
