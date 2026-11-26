package net.veloclient.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tester reports in YouTrack (velo-client.youtrack.cloud, project VELO - set up by tools/youtrack/setup.py): every report
 * from the Tester window becomes an issue with its screenshots, videos and logs attached; its state and public comments
 * come back to the tester's window, and their replies go in as comments. The token (YOUTRACK_TOKEN) stays on this
 * server - the launcher never sees it.
 */
final class YouTrack {

	private static final String BASE = envOr("YOUTRACK_URL", "https://velo-client.youtrack.cloud").replaceAll("/+$", "");
	private static final String TOKEN = envOr("YOUTRACK_TOKEN", null);
	/** What people open (links in the owner's Results): the API may go to a local address on the same machine. */
	private static final String PUBLIC = envOr("YOUTRACK_PUBLIC_URL", BASE).replaceAll("/+$", "");
	private static final String PROJECT = envOr("YOUTRACK_PROJECT", "VELO");
	/** Everything is assigned to the owner (login), so it all shows up in "Assigned to me". */
	private static final String ASSIGNEE = envOr("YOUTRACK_ASSIGNEE", "admin");

	private static JsonObject assignee() {
		JsonObject f = new JsonObject();
		f.addProperty("name", "Assignee");
		f.addProperty("$type", "SingleUserIssueCustomField");
		JsonObject v = new JsonObject();
		v.addProperty("login", ASSIGNEE);
		f.add("value", v);
		return f;
	}
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private static volatile String projectId;
	private static final Map<String, String> TAGS = new ConcurrentHashMap<>();

	private YouTrack() {
	}

	static boolean enabled() {
		return TOKEN != null && !TOKEN.isBlank();
	}

	/** The issue's page (for the owner's Results and Discord). */
	static String url(String idReadable) {
		return PUBLIC + "/issue/" + idReadable;
	}

	/** One public comment as the tester sees it. */
	record Comment(String id, String author, String text, long at, boolean tester) {
	}

	/** What the tester window shows of an issue. */
	record Status(String state, boolean resolved, long updated, List<Comment> comments) {
	}

	/**
	 * Creates the issue for a report; returns its readable id ("VELO-12"). {@code fields}: Type, Platform, Minecraft,
	 * Subsystem (area) - anything unknown is left out instead of failing the whole report.
	 */
	static String create(String summary, String description, Map<String, String> enums, Map<String, String> texts, List<String> tags)
			throws IOException {
		JsonObject body = new JsonObject();
		JsonObject project = new JsonObject();
		project.addProperty("id", projectId());
		body.add("project", project);
		body.addProperty("summary", summary);
		body.addProperty("description", description);
		JsonArray fields = new JsonArray();
		enums.forEach((name, value) -> {
			if (value != null && !value.isBlank()) {
				JsonObject f = new JsonObject();
				f.addProperty("name", name);
				f.addProperty("$type", name.equals("Subsystem") ? "SingleOwnedIssueCustomField" : "SingleEnumIssueCustomField");
				JsonObject v = new JsonObject();
				v.addProperty("name", value);
				f.add("value", v);
				fields.add(f);
			}
		});
		texts.forEach((name, value) -> {
			if (value != null && !value.isBlank()) {
				JsonObject f = new JsonObject();
				f.addProperty("name", name);
				f.addProperty("$type", "SimpleIssueCustomField");
				f.addProperty("value", value);
				fields.add(f);
			}
		});
		fields.add(assignee());
		body.add("customFields", fields);
		JsonObject created;
		try {
			created = call("POST", "issues?fields=idReadable", body).getAsJsonObject();
		} catch (IOException e) {
			// an enum value the project doesn't know (a new Minecraft version...): try again with the text fields only
			JsonArray plain = new JsonArray();
			for (JsonElement f : fields) {
				if ("SimpleIssueCustomField".equals(f.getAsJsonObject().get("$type").getAsString())
						|| "Type".equals(f.getAsJsonObject().get("name").getAsString())
						|| "Assignee".equals(f.getAsJsonObject().get("name").getAsString())) {
					plain.add(f);
				}
			}
			body.add("customFields", plain);
			created = call("POST", "issues?fields=idReadable", body).getAsJsonObject();
		}
		String id = created.get("idReadable").getAsString();
		for (String tag : tags) {
			tag(id, tag);
		}
		return id;
	}

	// ---- raw fields (player reports: State, Occurrences...) ----

	static JsonObject enumField(String name, String value) {
		return valued(name, name.equals("Subsystem") ? "SingleOwnedIssueCustomField" : name.equals("State") ? "StateIssueCustomField"
				: "SingleEnumIssueCustomField", value);
	}

	private static JsonObject valued(String name, String type, String value) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("$type", type);
		JsonObject v = new JsonObject();
		v.addProperty("name", value);
		f.add("value", v);
		return f;
	}

	static JsonObject textField(String name, String value) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("$type", "SimpleIssueCustomField");
		f.addProperty("value", value);
		return f;
	}

	static JsonObject numberField(String name, int value) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("$type", "SimpleIssueCustomField");
		f.addProperty("value", value);
		return f;
	}

	/** Creates an issue from ready-made fields; unknown enum values are dropped on a second try. */
	static String createRaw(String summary, String description, List<JsonObject> fieldList, List<String> tags) throws IOException {
		JsonObject body = new JsonObject();
		JsonObject project = new JsonObject();
		project.addProperty("id", projectId());
		body.add("project", project);
		body.addProperty("summary", summary.length() > 250 ? summary.substring(0, 250) : summary);
		body.addProperty("description", description);
		JsonArray fields = new JsonArray();
		fieldList.forEach(fields::add);
		fields.add(assignee());
		body.add("customFields", fields);
		JsonObject created;
		try {
			created = call("POST", "issues?fields=idReadable", body).getAsJsonObject();
		} catch (IOException e) {
			JsonArray safe = new JsonArray();
			for (JsonObject f : fieldList) {
				String type = f.get("$type").getAsString();
				String name = f.get("name").getAsString();
				if (type.equals("SimpleIssueCustomField") || name.equals("Type") || name.equals("State")) {
					safe.add(f);
				}
			}
			safe.add(assignee());
			body.add("customFields", safe);
			created = call("POST", "issues?fields=idReadable", body).getAsJsonObject();
		}
		String id = created.get("idReadable").getAsString();
		for (String tag : tags) {
			tag(id, tag);
		}
		return id;
	}

	/** Updates fields of an issue. */
	static void setFields(String issue, List<JsonObject> fieldList) throws IOException {
		JsonObject body = new JsonObject();
		JsonArray fields = new JsonArray();
		fieldList.forEach(fields::add);
		body.add("customFields", fields);
		call("POST", "issues/" + issue + "?fields=id", body);
	}

	/** A text as an attachment (crash report, log) - through a temp file. */
	static void attachText(String issue, String name, String text) throws IOException {
		Path dir = Files.createTempDirectory("velo-yt");
		Path file = dir.resolve(name);
		try {
			Files.writeString(file, text, StandardCharsets.UTF_8);
			attach(issue, file);
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(dir);
		}
	}

	/** Adds a tag (created by setup.py) to an issue. */
	static void tag(String issue, String name) {
		try {
			String tagId = TAGS.computeIfAbsent(name, n -> {
				try {
					for (JsonElement t : call("GET", "tags?fields=id,name&$top=200", null).getAsJsonArray()) {
						if (t.getAsJsonObject().get("name").getAsString().equals(n)) {
							return t.getAsJsonObject().get("id").getAsString();
						}
					}
				} catch (IOException ignored) {
					// no tag then
				}
				return "";
			});
			if (tagId.isEmpty()) {
				return;
			}
			JsonObject ref = new JsonObject();
			ref.addProperty("id", tagId);
			call("POST", "issues/" + issue + "/tags?fields=id", ref);
		} catch (IOException e) {
			System.out.println("[youtrack] tag " + name + " on " + issue + " failed: " + e.getMessage());
		}
	}

	/** Uploads one file as an attachment (streamed: videos can be big). */
	static void attach(String issue, Path file) throws IOException {
		String boundary = "----velo" + Long.toHexString(System.nanoTime());
		String name = file.getFileName().toString();
		String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "")
				+ "\"\r\nContent-Type: " + contentType(name) + "\r\n\r\n";
		String tail = "\r\n--" + boundary + "--\r\n";
		long length = head.getBytes(StandardCharsets.UTF_8).length + Files.size(file) + tail.getBytes(StandardCharsets.UTF_8).length;
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/api/issues/" + issue + "/attachments?fields=id"))
				.header("Authorization", "Bearer " + TOKEN)
				.header("Accept", "application/json")
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.timeout(Duration.ofMinutes(10))
				.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
					try {
						return new SequenceInputStream(Collections.enumeration(List.<InputStream>of(
								new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)), Files.newInputStream(file),
								new ByteArrayInputStream(tail.getBytes(StandardCharsets.UTF_8)))));
					} catch (IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}), length))
				.build();
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300) {
				throw new IOException("attachment " + name + " -> " + response.statusCode() + ": " + clip(response.body()));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	/** A comment on the issue (a tester's reply, posted for them). */
	static void comment(String issue, String text) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		call("POST", "issues/" + issue + "/comments?fields=id", body);
	}

	/** Sets the issue's State (the owner's Results buttons). */
	static void setState(String issue, String state) throws IOException {
		JsonObject body = new JsonObject();
		JsonArray fields = new JsonArray();
		JsonObject f = new JsonObject();
		f.addProperty("name", "State");
		f.addProperty("$type", "StateIssueCustomField");
		JsonObject v = new JsonObject();
		v.addProperty("name", state);
		f.add("value", v);
		fields.add(f);
		body.add("customFields", fields);
		call("POST", "issues/" + issue + "?fields=id", body);
	}

	/** State, resolved, last change and the public comments of an issue. */
	static Status status(String issue) throws IOException {
		JsonObject o = call("GET", "issues/" + issue + "?fields=resolved,updated,customFields(name,value(name)),"
				+ "comments(id,text,created,deleted,author(login,fullName),visibility($type,permittedGroups(name)))", null).getAsJsonObject();
		String state = "New";
		for (JsonElement f : o.getAsJsonArray("customFields")) {
			JsonObject field = f.getAsJsonObject();
			if ("State".equals(field.get("name").getAsString()) && field.get("value") != null && field.get("value").isJsonObject()) {
				state = field.getAsJsonObject("value").get("name").getAsString();
			}
		}
		List<Comment> comments = new ArrayList<>();
		if (o.has("comments")) {
			for (JsonElement c : o.getAsJsonArray("comments")) {
				JsonObject comment = c.getAsJsonObject();
				if (comment.has("deleted") && comment.get("deleted").getAsBoolean()) {
					continue;
				}
				// limited-visibility comments are team notes: never shown to the tester
				JsonElement vis = comment.get("visibility");
				if (vis != null && vis.isJsonObject() && vis.getAsJsonObject().has("permittedGroups")
						&& !vis.getAsJsonObject().getAsJsonArray("permittedGroups").isEmpty()) {
					continue;
				}
				String text = comment.has("text") && !comment.get("text").isJsonNull() ? comment.get("text").getAsString() : "";
				boolean fromTester = text.startsWith(TESTER_MARK);
				String author = fromTester ? "You" : comment.getAsJsonObject("author").has("fullName")
						? comment.getAsJsonObject("author").get("fullName").getAsString() : "Velo team";
				if ("admin".equalsIgnoreCase(author)) {
					author = "Velo team";
				}
				comments.add(new Comment(comment.get("id").getAsString(), author, fromTester ? text.substring(text.indexOf('\n') + 1) : text,
						comment.get("created").getAsLong(), fromTester));
			}
		}
		boolean resolved = o.has("resolved") && !o.get("resolved").isJsonNull();
		long updated = o.has("updated") && !o.get("updated").isJsonNull() ? o.get("updated").getAsLong() : 0;
		return new Status(state, resolved, updated, comments);
	}

	/** One open issue as testers see it: no names, no descriptions, no logs - just what it is and where it stands. */
	record OpenIssue(String id, String summary, String type, String state, String area, String source, int occurrences, long updated) {
	}

	private static volatile List<OpenIssue> openCache = List.of();
	private static volatile long openCachedAt;

	/** Every unresolved issue of the project (cached 2 minutes). */
	static List<OpenIssue> openIssues() throws IOException {
		if (System.currentTimeMillis() - openCachedAt < 120_000) {
			return openCache;
		}
		String q = URLEncoder.encode("project: " + PROJECT + " #Unresolved sort by: updated desc", StandardCharsets.UTF_8);
		List<OpenIssue> out = new ArrayList<>();
		for (JsonElement el : call("GET", "issues?fields=idReadable,summary,updated,customFields(name,value(name))&$top=1000&query=" + q, null)
				.getAsJsonArray()) {
			JsonObject o = el.getAsJsonObject();
			Map<String, String> f = new java.util.HashMap<>();
			int occurrences = 0;
			for (JsonElement cf : o.getAsJsonArray("customFields")) {
				JsonObject field = cf.getAsJsonObject();
				JsonElement v = field.get("value");
				String name = field.get("name").getAsString();
				if (v != null && v.isJsonObject() && v.getAsJsonObject().has("name")) {
					f.put(name, v.getAsJsonObject().get("name").getAsString());
				} else if (v != null && v.isJsonPrimitive() && name.equals("Occurrences")) {
					occurrences = v.getAsInt();
				}
			}
			out.add(new OpenIssue(o.get("idReadable").getAsString(), o.get("summary").getAsString(), f.getOrDefault("Type", "Bug"),
					f.getOrDefault("State", "New"), f.get("Subsystem"), f.get("Source"), occurrences,
					o.has("updated") && !o.get("updated").isJsonNull() ? o.get("updated").getAsLong() : 0));
		}
		openCache = List.copyOf(out);
		openCachedAt = System.currentTimeMillis();
		return openCache;
	}

	/** First line of a tester's comment (so it reads as theirs in YouTrack and can be told apart here). */
	static final String TESTER_MARK = "🧪 ";

	private static String projectId() throws IOException {
		String id = projectId;
		if (id == null) {
			for (JsonElement p : call("GET", "admin/projects?fields=id,shortName&$top=100", null).getAsJsonArray()) {
				if (PROJECT.equals(p.getAsJsonObject().get("shortName").getAsString())) {
					id = p.getAsJsonObject().get("id").getAsString();
				}
			}
			if (id == null) {
				throw new IOException("YouTrack project " + PROJECT + " not found");
			}
			projectId = id;
		}
		return id;
	}

	private static JsonElement call(String method, String path, JsonObject body) throws IOException {
		if (!enabled()) {
			throw new IOException("YouTrack not configured");
		}
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/" + path.replace("$", URLEncoder.encode("$", StandardCharsets.UTF_8))))
				.header("Authorization", "Bearer " + TOKEN)
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(30));
		if (body != null) {
			b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		try {
			HttpResponse<String> response = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300) {
				throw new IOException(method + " " + path + " -> " + response.statusCode() + ": " + clip(response.body()));
			}
			return response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	private static String contentType(String name) {
		String n = name.toLowerCase(java.util.Locale.ROOT);
		return n.endsWith(".png") ? "image/png" : n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg" : n.endsWith(".gif") ? "image/gif"
				: n.endsWith(".webp") ? "image/webp" : n.endsWith(".mp4") ? "video/mp4" : n.endsWith(".webm") ? "video/webm"
				: n.endsWith(".mov") ? "video/quicktime" : n.endsWith(".mkv") ? "video/x-matroska" : n.endsWith(".zip") ? "application/zip"
				: "text/plain";
	}

	private static String clip(String s) {
		return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
	}

	private static String envOr(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v.trim();
	}
}
