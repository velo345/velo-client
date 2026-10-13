package net.veloclient.velo.client.schematics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.client.network.VeloServerClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Talks to the Velo Network server's community schematics ({@code /v1/schematics}, see the
 * server's {@code SchematicShareService}): browse, upload, vote, delete and download. Blocking -
 * call from a background thread. Downloads go straight into the right folder: {@code .litematic}
 * into {@code schematics/} (where Litematica finds them), {@code .veloschem} into
 * {@code schematics/velo}.
 */
public final class CommunitySchematicsClient {

	public record Item(String id, String title, String description, List<String> tags, String format, long fileSize, String author,
			long createdAt, int votes, long downloads, boolean voted, boolean mine) {
	}

	public record Page(List<Item> items, int total, int page, int pageSize) {
	}

	private CommunitySchematicsClient() {
	}

	/** The server's base URL, or null when Velo Network isn't set up. */
	public static String base() {
		return VeloServerClient.serverBaseUrl();
	}

	public static boolean signedIn() {
		return VeloServerClient.sessionToken() != null;
	}

	public static String previewUrl(String id) {
		String base = base();
		return base == null ? null : base + "/v1/schematics/" + id + "/preview";
	}

	public static Page list(String sort, String query, String tag, int page) throws Exception {
		String base = requireBase();
		String url = base + "/v1/schematics?sort=" + enc(sort) + "&page=" + page + (query == null || query.isBlank() ? "" : "&q=" + enc(query))
				+ (tag == null || tag.isBlank() ? "" : "&tag=" + enc(tag));
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
		String token = VeloServerClient.sessionToken();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		JsonObject json = send(request.build());
		List<Item> items = new ArrayList<>();
		for (JsonElement e : json.getAsJsonArray("items")) {
			items.add(item(e.getAsJsonObject()));
		}
		return new Page(items, json.get("total").getAsInt(), json.get("page").getAsInt(), json.get("pageSize").getAsInt());
	}

	public static Item upload(Path file, String title, String description, List<String> tags, byte[] previewPng) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("title", title);
		body.addProperty("description", description);
		JsonArray tagArray = new JsonArray();
		tags.forEach(tagArray::add);
		body.add("tags", tagArray);
		body.addProperty("fileName", file.getFileName().toString());
		body.addProperty("file", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
		body.addProperty("preview", Base64.getEncoder().encodeToString(previewPng));
		return item(post("/v1/schematics/upload", body, Duration.ofSeconds(60)));
	}

	public static Item vote(String id, boolean up) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("up", up);
		return item(post("/v1/schematics/" + id + "/vote", body, Duration.ofSeconds(15)));
	}

	public static void delete(String id) throws Exception {
		post("/v1/schematics/" + id + "/delete", new JsonObject(), Duration.ofSeconds(15));
	}

	/** Downloads into the schematics folder for its format and returns the new file (never overwrites one). */
	public static Path download(Item item) throws Exception {
		String base = requireBase();
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/schematics/" + item.id() + "/file"))
				.timeout(Duration.ofSeconds(60)).GET().build();
		HttpResponse<byte[]> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() / 100 != 2) {
			throw new java.io.IOException("Download failed (HTTP " + response.statusCode() + ")");
		}
		// Only the two formats the server accepts can land here, under a name we build ourselves.
		String extension = "litematic".equals(item.format()) ? ".litematic" : ".veloschem";
		Path folder = extension.equals(".litematic") ? net.veloclient.velo.client.redstone.SchematicIO.litematicFolder()
				: net.veloclient.velo.client.redstone.SchematicIO.veloFolder();
		Files.createDirectories(folder);
		String name = item.title().replaceAll("[^A-Za-z0-9 _.-]", "_").replace("..", "_").strip();
		if (name.isEmpty()) {
			name = "schematic";
		}
		Path target = folder.resolve(name + extension);
		for (int n = 2; Files.exists(target); n++) {
			target = folder.resolve(name + " (" + n + ")" + extension);
		}
		Files.write(target, response.body());
		return target;
	}

	/** A short message for a failed call ("Links aren't allowed...", "Not connected..."). */
	public static String describe(Throwable t) {
		if (t instanceof VeloServerClient.HttpStatusException status) {
			try {
				JsonObject json = JsonParser.parseString(status.getMessage().substring(status.getMessage().indexOf('{'))).getAsJsonObject();
				if (json.has("error")) {
					return json.get("error").getAsString();
				}
			} catch (RuntimeException ignored) {
				// Not a JSON error body.
			}
			return status.status >= 500 ? "The Velo server is unavailable right now" : "Request failed (HTTP " + status.status + ")";
		}
		if (base() == null) {
			return "Velo Network isn't set up - turn on the Velo Network module";
		}
		return t.getMessage() != null && t.getMessage().startsWith("Sign in") ? t.getMessage() : "Couldn't reach the Velo server";
	}

	private static Item item(JsonObject o) {
		List<String> tags = new ArrayList<>();
		if (o.has("tags") && o.get("tags").isJsonArray()) {
			for (JsonElement t : o.getAsJsonArray("tags")) {
				tags.add(t.getAsString());
			}
		}
		return new Item(o.get("id").getAsString(), o.get("title").getAsString(), o.has("description") ? o.get("description").getAsString() : "",
				tags, o.get("format").getAsString().toLowerCase(Locale.ROOT), o.get("fileSize").getAsLong(), o.get("author").getAsString(),
				o.get("createdAt").getAsLong(), o.get("votes").getAsInt(), o.get("downloads").getAsLong(), o.get("voted").getAsBoolean(),
				o.get("mine").getAsBoolean());
	}

	private static String requireBase() throws java.io.IOException {
		String base = base();
		if (base == null) {
			throw new java.io.IOException("Velo Network isn't set up");
		}
		return base;
	}

	private static JsonObject post(String path, JsonObject body, Duration timeout) throws Exception {
		String base = requireBase();
		String token = VeloServerClient.sessionToken();
		if (token == null) {
			throw new java.io.IOException("Sign in to Velo Network first - it connects once you're in the game with a Microsoft account");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json")
				.timeout(timeout)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return send(request);
	}

	private static JsonObject send(HttpRequest request) throws Exception {
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new VeloServerClient.HttpStatusException(response.statusCode(), response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}
}
