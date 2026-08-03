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
		String url = "https://meta.fabricmc.net/v2/versions/loader/" + version.id() + "/" + version.fabricLoaderVersion() + "/profile/json";
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
		try {
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new IOException("HTTP " + response.statusCode() + " fetching Fabric loader profile for Minecraft "
						+ version.id() + " (loader " + version.fabricLoaderVersion() + ") from " + url);
			}
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted fetching " + url, e);
		}
	}
}
