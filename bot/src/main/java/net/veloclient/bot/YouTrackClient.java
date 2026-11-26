package net.veloclient.bot;

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

/** The few YouTrack REST calls the forum sync needs (project VELO on youtrack.velo-client.com, token from bot.env). */
final class YouTrackClient {

	private final String base;
	private final String token;
	private final String project;
	private final String assignee;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private volatile String projectId;
	private final Map<String, String> tags = new ConcurrentHashMap<>();

	YouTrackClient(String base, String token, String project, String assignee) {
		this.base = base.replaceAll("/+$", "");
		this.token = token;
		this.project = project;
		this.assignee = assignee;
	}

	record Comment(String id, String text, long created, boolean limited, List<String[]> files, String author) {
	}

	record Status(String state, boolean resolved, List<Comment> comments, String summary) {
	}

	static JsonObject enumField(String name, String value) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("$type", name.equals("Subsystem") ? "SingleOwnedIssueCustomField" : name.equals("State") ? "StateIssueCustomField"
				: "SingleEnumIssueCustomField");
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

	private JsonObject assigneeField() {
		JsonObject f = new JsonObject();
		f.addProperty("name", "Assignee");
		f.addProperty("$type", "SingleUserIssueCustomField");
		JsonObject v = new JsonObject();
		v.addProperty("login", assignee);
		f.add("value", v);
		return f;
	}

	/** Creates an issue (unknown enum values are dropped on a second try); returns "VELO-12". */
	String create(String summary, String description, List<JsonObject> fields, List<String> tagNames) throws IOException {
		JsonObject body = new JsonObject();
		JsonObject p = new JsonObject();
		p.addProperty("id", projectId());
		body.add("project", p);
		body.addProperty("summary", summary.length() > 250 ? summary.substring(0, 250) : summary);
		body.addProperty("description", description);
		JsonArray arr = new JsonArray();
		fields.forEach(arr::add);
		arr.add(assigneeField());
		body.add("customFields", arr);
		JsonObject created;
		try {
			created = call("POST", "issues?fields=idReadable", body).getAsJsonObject();
		} catch (IOException e) {
			JsonArray safe = new JsonArray();
			for (JsonObject f : fields) {
				String name = f.get("name").getAsString();
				if (f.get("$type").getAsString().equals("SimpleIssueCustomField") || name.equals("Type") || name.equals("State")) {
					safe.add(f);
				}
			}
			safe.add(assigneeField());
			body.add("customFields", safe);
			created = call("POST", "issues?fields=idReadable", body).getAsJsonObject();
		}
		String id = created.get("idReadable").getAsString();
		for (String t : tagNames) {
			tag(id, t);
		}
		return id;
	}

	void update(String issue, String summary, String description) throws IOException {
		JsonObject body = new JsonObject();
		if (summary != null) {
			body.addProperty("summary", summary.length() > 250 ? summary.substring(0, 250) : summary);
		}
		if (description != null) {
			body.addProperty("description", description);
		}
		call("POST", "issues/" + issue + "?fields=id", body);
	}

	void setFields(String issue, List<JsonObject> fields) throws IOException {
		JsonObject body = new JsonObject();
		JsonArray arr = new JsonArray();
		fields.forEach(arr::add);
		body.add("customFields", arr);
		call("POST", "issues/" + issue + "?fields=id", body);
	}

	/** Adds a tag, creating it (shared, in the "origin" blue) the first time a forum tag shows up. */
	void tag(String issue, String name) {
		try {
			String id = tags.get(name);
			if (id == null) {
				for (JsonElement t : call("GET", "tags?fields=id,name&$top=500", null).getAsJsonArray()) {
					tags.put(t.getAsJsonObject().get("name").getAsString(), t.getAsJsonObject().get("id").getAsString());
				}
				id = tags.get(name);
			}
			if (id == null) {
				JsonObject t = new JsonObject();
				t.addProperty("name", name);
				JsonObject color = new JsonObject();
				color.addProperty("id", "4");
				t.add("color", color);
				id = call("POST", "tags?fields=id", t).getAsJsonObject().get("id").getAsString();
				tags.put(name, id);
			}
			JsonObject ref = new JsonObject();
			ref.addProperty("id", id);
			call("POST", "issues/" + issue + "/tags?fields=id", ref);
		} catch (IOException e) {
			System.out.println("[forums] tag " + name + " on " + issue + ": " + e.getMessage());
		}
	}

	void updateComment(String issue, String commentId, String text) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		call("POST", "issues/" + issue + "/comments/" + commentId + "?fields=id", body);
	}

	void deleteComment(String issue, String commentId) throws IOException {
		call("DELETE", "issues/" + issue + "/comments/" + commentId, null);
	}

	/** Takes a tag off an issue (no-op when it isn't there). */
	void untag(String issue, String name) {
		try {
			for (JsonElement t : call("GET", "issues/" + issue + "/tags?fields=id,name&$top=100", null).getAsJsonArray()) {
				if (t.getAsJsonObject().get("name").getAsString().equals(name)) {
					call("DELETE", "issues/" + issue + "/tags/" + t.getAsJsonObject().get("id").getAsString(), null);
				}
			}
		} catch (IOException e) {
			System.out.println("[forums] untag " + name + " on " + issue + ": " + e.getMessage());
		}
	}

	/** The issue's tag names. */
	List<String> tagsOf(String issue) throws IOException {
		List<String> out = new ArrayList<>();
		for (JsonElement t : call("GET", "issues/" + issue + "/tags?fields=name&$top=100", null).getAsJsonArray()) {
			out.add(t.getAsJsonObject().get("name").getAsString());
		}
		return out;
	}

	String comment(String issue, String text) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		return call("POST", "issues/" + issue + "/comments?fields=id", body).getAsJsonObject().get("id").getAsString();
	}

	Status status(String issue) throws IOException {
		JsonObject o = call("GET", "issues/" + issue + "?fields=summary,resolved,customFields(name,value(name)),"
				+ "comments(id,text,created,deleted,author(login),attachments(name,url),visibility($type,permittedGroups(name),permittedUsers(login)))", null).getAsJsonObject();
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
				JsonElement vis = comment.get("visibility");
				boolean limited = vis != null && vis.isJsonObject() && (vis.getAsJsonObject().has("permittedGroups")
						&& !vis.getAsJsonObject().getAsJsonArray("permittedGroups").isEmpty()
						|| vis.getAsJsonObject().has("permittedUsers") && !vis.getAsJsonObject().getAsJsonArray("permittedUsers").isEmpty());
				String text = comment.has("text") && !comment.get("text").isJsonNull() ? comment.get("text").getAsString() : "";
				List<String[]> files = new ArrayList<>();
				if (comment.has("attachments")) {
					for (JsonElement a : comment.getAsJsonArray("attachments")) {
						JsonObject at = a.getAsJsonObject();
						if (at.has("url") && !at.get("url").isJsonNull()) {
							files.add(new String[] {at.get("name").getAsString(), at.get("url").getAsString()});
						}
					}
				}
				String author = comment.has("author") && comment.get("author").isJsonObject() && comment.getAsJsonObject("author").has("login")
						? comment.getAsJsonObject("author").get("login").getAsString() : "";
				comments.add(new Comment(comment.get("id").getAsString(), text, comment.get("created").getAsLong(), limited, files, author));
			}
		}
		return new Status(state, o.has("resolved") && !o.get("resolved").isJsonNull(), comments,
				o.has("summary") && !o.get("summary").isJsonNull() ? o.get("summary").getAsString() : "");
	}

	/** File names already attached to an issue. */
	java.util.Set<String> attachmentNames(String issue) throws IOException {
		java.util.Set<String> names = new java.util.HashSet<>();
		for (JsonElement a : call("GET", "issues/" + issue + "/attachments?fields=name&$top=500", null).getAsJsonArray()) {
			names.add(a.getAsJsonObject().get("name").getAsString());
		}
		return names;
	}

	/** Issues (readable ids) changed since {@code since} (ms) among those with the tag. */
	java.util.Set<String> changedSince(String tag, long since) throws IOException {
		java.util.Set<String> out = new java.util.HashSet<>();
		String q = URLEncoder.encode("project: " + project + " tag: " + tag, StandardCharsets.UTF_8);
		for (JsonElement i : call("GET", "issues?fields=idReadable,updated&$top=2000&query=" + q, null).getAsJsonArray()) {
			JsonObject o = i.getAsJsonObject();
			if (o.has("updated") && !o.get("updated").isJsonNull() && o.get("updated").getAsLong() >= since) {
				out.add(o.get("idReadable").getAsString());
			}
		}
		return out;
	}

	/** A YouTrack file (comment attachment) as bytes; {@code url} as the API returns it (relative, signed). */
	byte[] download(String url) throws IOException {
		String full = url.startsWith("http") ? url : base + url;
		HttpRequest r = HttpRequest.newBuilder(URI.create(full)).header("Authorization", "Bearer " + token).timeout(Duration.ofMinutes(2)).GET().build();
		try {
			HttpResponse<byte[]> res = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() >= 300) {
				throw new IOException("download " + res.statusCode());
			}
			return res.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	/** Uploads a file (streamed) as an attachment. */
	void attach(String issue, Path file, String name) throws IOException {
		String boundary = "----velo" + Long.toHexString(System.nanoTime());
		String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "")
				+ "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
		String tail = "\r\n--" + boundary + "--\r\n";
		long length = head.getBytes(StandardCharsets.UTF_8).length + Files.size(file) + tail.getBytes(StandardCharsets.UTF_8).length;
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/issues/" + issue + "/attachments?fields=id"))
				.header("Authorization", "Bearer " + token).header("Accept", "application/json")
				.header("Content-Type", "multipart/form-data; boundary=" + boundary).timeout(Duration.ofMinutes(10))
				.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
					try {
						return new SequenceInputStream(Collections.enumeration(List.<InputStream>of(
								new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)), Files.newInputStream(file),
								new ByteArrayInputStream(tail.getBytes(StandardCharsets.UTF_8)))));
					} catch (IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}), length)).build();
		send(request);
	}

	/** Downloads a URL (a Discord attachment) and attaches it. */
	void attachUrl(String issue, String url, String name) throws IOException {
		Path tmp = Files.createTempFile("velo-forum", ".bin");
		try {
			HttpRequest get = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
			try {
				HttpResponse<Path> r = http.send(get, HttpResponse.BodyHandlers.ofFile(tmp));
				if (r.statusCode() >= 300) {
					throw new IOException("download " + r.statusCode());
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted");
			}
			attach(issue, tmp, name);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private String projectId() throws IOException {
		if (projectId == null) {
			for (JsonElement p : call("GET", "admin/projects?fields=id,shortName&$top=100", null).getAsJsonArray()) {
				if (project.equals(p.getAsJsonObject().get("shortName").getAsString())) {
					projectId = p.getAsJsonObject().get("id").getAsString();
				}
			}
			if (projectId == null) {
				throw new IOException("project " + project + " not found");
			}
		}
		return projectId;
	}

	private JsonElement call(String method, String path, JsonObject body) throws IOException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/" + path.replace("$", URLEncoder.encode("$", StandardCharsets.UTF_8))))
				.header("Authorization", "Bearer " + token).header("Accept", "application/json").timeout(Duration.ofSeconds(30));
		if (body != null) {
			b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		String text = send(b.build());
		return text.isBlank() ? new JsonObject() : JsonParser.parseString(text);
	}

	private String send(HttpRequest request) throws IOException {
		try {
			HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() >= 300) {
				String b = r.body() == null ? "" : r.body();
				throw new IOException(request.method() + " " + request.uri().getPath() + " -> " + r.statusCode() + ": " + (b.length() > 300 ? b.substring(0, 300) : b));
			}
			return r.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}
}
