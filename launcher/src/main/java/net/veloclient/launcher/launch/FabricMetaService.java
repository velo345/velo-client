package net.veloclient.launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Fetches Fabric's own "launcher profile" JSON (same shape as a vanilla version JSON) from Fabric's meta API. */
public final class FabricMetaService {

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private FabricMetaService() {
	}

	/** @return {@code mainClass} (Fabric's KnotClient) + {@code libraries} (loader/intermediary/adapters) to merge on top of the vanilla version. */
	public static JsonObject fetchLoaderProfile(GameVersion version) throws IOException {
		return fetchLoaderProfile(version, version.fabricLoaderVersion());
	}

	/** The same for a specific loader build (a profile that picked another one, see {@link LoaderVersions}). */
	public static JsonObject fetchLoaderProfile(GameVersion version, String loader) throws IOException {
		// Pinned loader version -> the profile never changes; fetched once, then served from disk.
		return MetaCache.getOrFetch((version.ornithe() ? "ornithe2-" : "fabric-") + version.id() + "-" + loader, () -> fetchUncached(version, loader));
	}

	private static JsonObject fetchUncached(GameVersion version, String loader) throws IOException {
		String url = version.loaderProfileUrl(loader);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
		try {
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new IOException("HTTP " + response.statusCode() + " fetching Fabric loader profile for Minecraft "
						+ version.id() + " (loader " + loader + ") from " + url);
			}
			JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
			if (version.ornithe()) {
				addLibraryUpgrades(version, profile);
			}
			return profile;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted fetching " + url, e);
		}
	}

	/**
	 * Ornithe runs old versions with newer libraries than they shipped with (log4j 2.19, slf4j 2, fastutil, gson 2.10,
	 * jopt-simple 5, Ornithe's log4j patch...): its meta lists them per version. They go into the loader profile, whose
	 * libraries win over vanilla's with the same group:artifact ({@link LibraryResolver#resolve}).
	 */
	private static void addLibraryUpgrades(GameVersion version, JsonObject profile) throws IOException, InterruptedException {
		String url = version.metaBase() + "/v3/versions/gen2/libraries/" + version.id();
		HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " fetching Ornithe's libraries for Minecraft " + version.id() + " from " + url);
		}
		com.google.gson.JsonArray libraries = profile.has("libraries") ? profile.getAsJsonArray("libraries") : new com.google.gson.JsonArray();
		for (var library : JsonParser.parseString(response.body()).getAsJsonArray()) {
			libraries.add(library);
		}
		profile.add("libraries", libraries);
	}
}
