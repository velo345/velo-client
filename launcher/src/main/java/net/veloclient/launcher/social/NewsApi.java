package net.veloclient.launcher.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.data.VeloPaths;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The launcher's client for news posts, polls and bug reports on the Velo server. Calls block -
 * run them off the FX thread. The last feed is kept on disk ({@code cache/news.json}) so the Home
 * banner has something to show straight away (and offline), and images are cached forever by id.
 */
public final class NewsApi {

	public static final class NewsError extends Exception {
		public final int status;

		NewsError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final Gson GSON = new Gson();
	/** Dev-only: the screenshot tour shows sample posts instead of talking to the real server. */
	private static final boolean DEMO = System.getProperty("velo.launcherTour") != null;

	private NewsApi() {
	}

	private static Path cacheDir() {
		return VeloPaths.root().resolve("cache");
	}

	/** Fresh feed from the server (cached to disk); falls back to the cached copy when offline. */
	public static JsonObject feed() throws NewsError {
		if (DEMO) {
			return demoFeed();
		}
		try {
			JsonObject feed = send(request("/v1/news").GET().build());
			try {
				Files.createDirectories(cacheDir());
				Files.writeString(cacheDir().resolve("news.json"), feed.toString(), StandardCharsets.UTF_8);
			} catch (Exception ignored) {
				// Cache is best-effort.
			}
			return feed;
		} catch (NewsError e) {
			JsonObject cached = cachedFeed();
			if (cached != null) {
				return cached;
			}
			throw e;
		}
	}

	/** The last feed seen, without a network call (null if none). */
	public static JsonObject cachedFeed() {
		if (DEMO) {
			return demoFeed();
		}
		try {
			Path file = cacheDir().resolve("news.json");
			return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	public static JsonObject vote(String pollId, List<Integer> options) throws NewsError {
		JsonObject body = new JsonObject();
		body.addProperty("pollId", pollId);
		body.add("options", GSON.toJsonTree(options));
		return post("/v1/news/vote", body);
	}

	/** Image bytes for an id (disk-cached; ids are content hashes so they never change). */
	public static byte[] image(String id) {
		if (id == null || !id.matches("[0-9a-f]{32}\\.(png|jpg)")) {
			return null;
		}
		Path file = cacheDir().resolve("news-images").resolve(id);
		try {
			if (Files.exists(file)) {
				return Files.readAllBytes(file);
			}
			if (DEMO || LauncherSocial.baseUrl() == null) {
				return null;
			}
			HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(LauncherSocial.baseUrl() + "/v1/news/image/" + id))
					.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				return null;
			}
			Files.createDirectories(file.getParent());
			Files.write(file, response.body());
			return response.body();
		} catch (Exception e) {
			return null;
		}
	}

	// ---- Owner ----

	public static String uploadImage(byte[] bytes) throws NewsError {
		JsonObject result = send(request("/v1/admin/news/image").header("Content-Type", "application/octet-stream")
				.timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build());
		String id = result.get("id").getAsString();
		try {
			Path file = cacheDir().resolve("news-images").resolve(id);
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.write(file, bytes);
			}
		} catch (Exception ignored) {
			// Re-downloaded when needed.
		}
		return id;
	}

	public static JsonObject savePost(JsonObject post) throws NewsError {
		return post("/v1/admin/news/post", post);
	}

	public static void deletePost(String id) throws NewsError {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		post("/v1/admin/news/post/delete", body);
	}

	public static JsonObject savePoll(JsonObject poll) throws NewsError {
		return post("/v1/admin/news/poll", poll);
	}

	public static void deletePoll(String id) throws NewsError {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		post("/v1/admin/news/poll/delete", body);
	}

	public static JsonArray reports() throws NewsError {
		return send(request("/v1/admin/reports").GET().build()).getAsJsonArray("reports");
	}

	public static JsonObject report(String id) throws NewsError {
		return send(request("/v1/admin/reports/get?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8)).GET().build());
	}

	public static void reportStatus(String id, String status) throws NewsError {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("status", status);
		post("/v1/admin/reports/status", body);
	}

	public static void deleteReport(String id) throws NewsError {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		post("/v1/admin/reports/delete", body);
	}

	// ---- Bug reports (anyone) ----

	public static String sendReport(JsonObject report) throws NewsError {
		return send(request("/v1/reports").header("Content-Type", "application/json").timeout(Duration.ofSeconds(60))
				.POST(HttpRequest.BodyPublishers.ofString(report.toString())).build()).get("id").getAsString();
	}

	// ---- HTTP ----

	private static JsonObject post(String path, JsonObject body) throws NewsError {
		return send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
	}

	private static HttpRequest.Builder request(String path) throws NewsError {
		String base = LauncherSocial.baseUrl();
		if (base == null) {
			throw new NewsError(0, "The Velo server is turned off in the launcher settings");
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json")
				.timeout(Duration.ofSeconds(20));
		String token = LauncherSocial.sessionToken();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder;
	}

	private static JsonObject send(HttpRequest request) throws NewsError {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				String message = "Request failed (HTTP " + response.statusCode() + ")";
				try {
					JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject();
					if (error.has("error")) {
						message = error.get("error").getAsString();
					}
				} catch (Exception ignored) {
					// Not JSON.
				}
				throw new NewsError(response.statusCode(), message);
			}
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (NewsError e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new NewsError(0, "Interrupted");
		} catch (Exception e) {
			throw new NewsError(0, "Couldn't reach the Velo server");
		}
	}

	private static JsonObject demoFeed() {
		String json = """
				{"owner":true,"posts":[
				{"id":"demo1","title":"Velo Client 0.8.4 is here","summary":"World map, a skin changer with 3D previews, custom advancements and a brand new R-Shift menu.",
				"tag":"Update","version":"0.8.4","pinned":true,"published":true,"publishedAt":1759600000000,"author":"Velo Team","blocks":[
				{"type":"heading","text":"A real world map"},{"type":"text","text":"Press **M** to open a full-screen map of everywhere you've been - with layers, cave mode and waypoints."},
				{"type":"callout","style":"info","text":"Hide your coordinates on the map when you stream."},
				{"type":"list","items":["Surface, topography and biome layers","Cave layer while underground","Right-click to add a waypoint"]}]},
				{"id":"demo2","title":"Vote on what comes next","summary":"Tell us which feature we should build next - the poll is open for a week.","tag":"Community","published":true,"publishedAt":1759400000000,"blocks":[]},
				{"id":"demo3","title":"Spooky season cosmetics","summary":"Four new capes in the Store, only until November.","tag":"Event","published":true,"publishedAt":1759200000000,"blocks":[]}],
				"polls":[{"id":"p1","question":"What should we add next?","description":"Pick up to two.","options":[
				{"text":"Minimap","votes":182},{"text":"Replay recording","votes":96},{"text":"Chat translation","votes":41}],
				"multi":true,"closesAt":0,"closed":false,"resultsVisible":true,"totalVotes":260,"myVote":[0],"createdAt":1759500000000}]}""";
		return JsonParser.parseString(json).getAsJsonObject();
	}
}
