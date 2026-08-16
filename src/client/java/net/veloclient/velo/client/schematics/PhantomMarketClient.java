package net.veloclient.velo.client.schematics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game counterpart to the launcher's {@code
 * net.veloclient.launcher.schematic.PhantomMarketClient} - same API, same
 * scope restriction (only the robots.txt-allowed {@code /api/posts/search}
 * endpoint; the real download happens on the site itself, opened in the
 * system browser). Kept as a separate copy rather than a shared module since
 * the launcher and the mod are different Gradle projects/classpaths (same
 * reason {@code VeloPaths} is duplicated between them) - see the launcher's
 * copy for the full rationale on why this doesn't call the site's file-
 * download route directly.
 */
public final class PhantomMarketClient {

	private static final String API_BASE = "https://market.phantom-node.com/api";
	private static final String SITE_BASE = "https://market.phantom-node.com";
	private static final Gson GSON = new GsonBuilder().create();
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final String USER_AGENT = "VeloClient/1.0 (github.com/veloclient - contact via repo issues)";

	private PhantomMarketClient() {
	}

	public record Tag(String name, String slug, @SerializedName("colorHex") String colorHex) {
	}

	public record SearchHit(
			String title,
			String slug,
			String thumbnail,
			@SerializedName("authorUsername") String authorUsername,
			@SerializedName("downloadCount") long downloadCount,
			@SerializedName("viewCount") long viewCount,
			@SerializedName("displayType") String displayType,
			List<Tag> tags) {

		public String thumbnailUrl() {
			return thumbnail == null || thumbnail.isBlank() ? null : "https://cdn.phantom-node.com/" + thumbnail;
		}
	}

	public static String postUrl(String slug) {
		return SITE_BASE + "/post/" + slug;
	}

	public static List<SearchHit> search(String query) throws IOException {
		String url = API_BASE + "/posts/search?q=" + encode(query == null ? "" : query);
		JsonObject response = getJson(url);
		List<SearchHit> hits = new ArrayList<>();
		if (response.has("posts")) {
			for (var element : response.getAsJsonArray("posts")) {
				hits.add(GSON.fromJson(element, SearchHit.class));
			}
		}
		return hits;
	}

	private static JsonObject getJson(String url) throws IOException {
		return com.google.gson.JsonParser.parseString(getRaw(url)).getAsJsonObject();
	}

	private static String getRaw(String url) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(20))
				.GET().build();
		try {
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new IOException("HTTP " + response.statusCode() + " from PhantomMarket: " + url);
			}
			return response.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted contacting PhantomMarket", e);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
