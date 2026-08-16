package net.veloclient.launcher.schematic;

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
 * Read-only client for PhantomMarket (market.phantom-node.com), a public
 * Minecraft schematic-sharing site, used to power the "Browse online"
 * search grid in the launcher's Schematics tab.
 *
 * <p>Deliberately narrow: this only calls {@code /api/posts/search}, the one
 * endpoint the site's own {@code /robots.txt} explicitly allows for general
 * (non-search-engine) automated use alongside a handful of others (comments,
 * stats, tag/category listings). Its per-post detail endpoint and its
 * file-download endpoint are both left un-allowlisted there on purpose - the
 * site gates the real download behind its own page (ads fund its hosting),
 * so this client only ever surfaces enough to discover and preview a
 * schematic (title, thumbnail, author, tags); {@link #postUrl} is what
 * callers should send the user to for the full description and the actual
 * download, exactly as if they'd clicked through from a search engine.
 */
public final class PhantomMarketClient {

	private static final String API_BASE = "https://market.phantom-node.com/api";
	private static final String SITE_BASE = "https://market.phantom-node.com";
	private static final Gson GSON = new GsonBuilder().create();
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final String USER_AGENT = "VeloClientLauncher/1.0 (github.com/veloclient - contact via repo issues)";

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

		/** Absolute CDN URL for {@link #thumbnail}, or {@code null} if this post has none. */
		public String thumbnailUrl() {
			return thumbnail == null || thumbnail.isBlank() ? null : "https://cdn.phantom-node.com/" + thumbnail;
		}
	}

	public record SearchResult(List<SearchHit> posts) {
	}

	/** The site's own post page for {@code slug} - open this in the system browser for the full description, images, and the actual (ad-supported) download. Path is singular ({@code /post/<slug>}) - the plural {@code /posts/<slug>} 404s (that's the site's own page route, unrelated to the plural {@code /api/posts/...} API routes this client calls). */
	public static String postUrl(String slug) {
		return SITE_BASE + "/post/" + slug;
	}

	/**
	 * @param query free-text search, blank/empty for "recent" browsing
	 */
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
