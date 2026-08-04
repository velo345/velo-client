package net.veloclient.launcher.modrinth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.Optional;

/**
 * Thin client for the parts of Modrinth's public v2 API the mod-profile
 * editor needs: searching mods/resource packs/shaders (scoped to a profile's
 * Minecraft version + the Fabric loader this launcher always uses), listing
 * a project's compatible versions, and looking a file up by its sha1 (to
 * backfill metadata for jars a user dropped in manually rather than
 * installing through here). No API key required for these read endpoints.
 */
public final class ModrinthClient {

	private static final String API_BASE = "https://api.modrinth.com/v2";
	private static final Gson GSON = new GsonBuilder().create();
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	// Modrinth asks integrations to identify themselves with a descriptive User-Agent.
	private static final String USER_AGENT = "VeloClientLauncher/1.0 (github.com/veloclient - contact via repo issues)";

	private ModrinthClient() {
	}

	public record SearchHit(
			@SerializedName("project_id") String projectId,
			String slug,
			String title,
			String description,
			String author,
			@SerializedName("icon_url") String iconUrl,
			long downloads,
			@SerializedName("project_type") String projectType,
			@SerializedName("display_categories") List<String> displayCategories) {
	}

	public record SearchResult(List<SearchHit> hits, int totalHits) {
	}

	public record FileHashes(String sha1, String sha512) {
	}

	public record VersionFile(String url, String filename, FileHashes hashes, boolean primary, long size) {
		public String sha1() {
			return hashes != null ? hashes.sha1() : null;
		}
	}

	public record Dependency(
			@SerializedName("version_id") String versionId,
			@SerializedName("project_id") String projectId,
			@SerializedName("dependency_type") String dependencyType) {

		public boolean required() {
			return "required".equals(dependencyType);
		}
	}

	public record ProjectVersion(
			String id,
			@SerializedName("project_id") String projectId,
			@SerializedName("version_number") String versionNumber,
			String name,
			@SerializedName("game_versions") List<String> gameVersions,
			List<String> loaders,
			List<VersionFile> files,
			@SerializedName("date_published") String datePublished,
			List<Dependency> dependencies) {

		public Optional<VersionFile> primaryFile() {
			return files.stream().filter(VersionFile::primary).findFirst().or(() -> files.stream().findFirst());
		}

		public List<Dependency> requiredDependencies() {
			return dependencies == null ? List.of() : dependencies.stream().filter(Dependency::required).toList();
		}
	}

	/**
	 * @param projectType one of Modrinth's project types: "mod", "resourcepack", "shader"
	 * @param sortIndex   "relevance", "downloads", "follows", "newest", "updated"
	 */
	public static SearchResult search(String query, String projectType, String gameVersion, String sortIndex,
			int offset, int limit) throws IOException {
		JsonArray facets = new JsonArray();
		facets.add(singleFacet("project_type:" + projectType));
		facets.add(singleFacet("versions:" + gameVersion));
		if ("mod".equals(projectType)) {
			facets.add(singleFacet("categories:fabric"));
		}

		String url = API_BASE + "/search"
				+ "?query=" + encode(query == null ? "" : query)
				+ "&facets=" + encode(facets.toString())
				+ "&index=" + encode(sortIndex)
				+ "&offset=" + offset
				+ "&limit=" + limit;
		JsonObject response = getJson(url);
		List<SearchHit> hits = new ArrayList<>();
		for (var element : response.getAsJsonArray("hits")) {
			hits.add(GSON.fromJson(element, SearchHit.class));
		}
		int total = response.has("total_hits") ? response.get("total_hits").getAsInt() : hits.size();
		return new SearchResult(hits, total);
	}

	/** Versions of {@code projectId} compatible with {@code gameVersion} on Fabric, newest first (Modrinth's own default order). */
	public static List<ProjectVersion> versions(String projectId, String gameVersion) throws IOException {
		String url = API_BASE + "/project/" + encode(projectId) + "/version"
				+ "?game_versions=" + encode("[\"" + gameVersion + "\"]")
				+ "&loaders=" + encode("[\"fabric\"]");
		JsonObject response = wrapArray(getRaw(url));
		List<ProjectVersion> versions = new ArrayList<>();
		for (var element : response.getAsJsonArray("array")) {
			versions.add(GSON.fromJson(element, ProjectVersion.class));
		}
		return versions;
	}

	/** Resolves one specific version by id - used to follow a dependency's exact pinned {@code version_id} rather than guessing "newest compatible". */
	public static Optional<ProjectVersion> getVersion(String versionId) {
		try {
			return Optional.of(GSON.fromJson(getJson(API_BASE + "/version/" + encode(versionId)), ProjectVersion.class));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	/** Looks up which project/version a file belongs to by its sha1, for backfilling metadata on manually-added jars. */
	public static Optional<ProjectVersion> versionByFileSha1(String sha1) {
		try {
			String url = API_BASE + "/version_file/" + encode(sha1) + "?algorithm=sha1";
			JsonObject response = getJson(url);
			return Optional.of(GSON.fromJson(response, ProjectVersion.class));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public record Project(
			String id,
			String slug,
			String title,
			String description,
			@SerializedName("icon_url") String iconUrl) {
	}

	public static Optional<Project> getProject(String projectId) {
		try {
			return Optional.of(GSON.fromJson(getJson(API_BASE + "/project/" + encode(projectId)), Project.class));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	private static JsonArray singleFacet(String value) {
		JsonArray group = new JsonArray();
		group.add(value);
		return group;
	}

	/** {@code getJson} assumes an object body; this wraps a top-level JSON array response under an "array" key so callers can still use JsonObject. */
	private static JsonObject wrapArray(String rawJsonArray) {
		JsonObject wrapper = new JsonObject();
		wrapper.add("array", com.google.gson.JsonParser.parseString(rawJsonArray).getAsJsonArray());
		return wrapper;
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
				throw new IOException("HTTP " + response.statusCode() + " from Modrinth: " + url);
			}
			return response.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted contacting Modrinth", e);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
