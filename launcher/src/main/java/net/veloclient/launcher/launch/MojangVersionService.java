package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Resolves a Minecraft version id to its full version-detail JSON via Mojang's public launcher metadata. */
public final class MojangVersionService {

	private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private MojangVersionService() {
	}

	/** @return the version's own detail JSON: {@code downloads}, {@code libraries}, {@code assetIndex}, {@code arguments}, ... */
	public static JsonObject fetchVersionDetail(String mcVersion) throws IOException {
		JsonObject manifest = getJson(VERSION_MANIFEST_URL);
		JsonArray versions = manifest.getAsJsonArray("versions");
		for (var element : versions) {
			JsonObject entry = element.getAsJsonObject();
			if (entry.get("id").getAsString().equals(mcVersion)) {
				return getJson(entry.get("url").getAsString());
			}
		}
		throw new IOException("Minecraft version \"" + mcVersion + "\" not found in Mojang's version manifest.");
	}

	private static JsonObject getJson(String url) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
		try {
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
			}
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted fetching " + url, e);
		}
	}
}
