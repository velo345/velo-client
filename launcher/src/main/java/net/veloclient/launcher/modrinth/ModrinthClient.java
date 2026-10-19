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

	/** Modpacks and datapacks are just as game-version-sensitive as mods (a modpack's own mod list, or a datapack's data format, can easily not match a different Minecraft version) - only resource packs/shaders are loose enough to skip this. */
	private static boolean versionSensitive(String projectType) {
		return "mod".equals(projectType) || "modpack".equals(projectType) || "datapack".equals(projectType);
	}

	/** Datapacks aren't Fabric-loader-specific at all (no mod loader involved), so only mods/modpacks get the {@code categories:fabric} facet/loaders filter. */
	private static boolean loaderSensitive(String projectType) {
		return "mod".equals(projectType) || "modpack".equals(projectType);
	}

	/**
	 * @param projectType one of Modrinth's project types: "mod", "modpack", "resourcepack", "shader", "datapack"
	 * @param sortIndex   "relevance", "downloads", "follows", "newest", "updated"
	 */
	public static SearchResult search(String query, String projectType, String gameVersion, String sortIndex,
			int offset, int limit) throws IOException {
		JsonArray facets = new JsonArray();
		facets.add(singleFacet("project_type:" + projectType));
		if (versionSensitive(projectType)) {
			// Resource packs/shaders aren't loader-specific and plenty of them
			// never bother tagging every individual game version (especially
			// this project's own less-common target versions), so a strict
			// versions: facet there returned nothing installable at all - only
			// version-sensitive project types need this filtered this tightly.
			facets.add(singleFacet("versions:" + gameVersion));
		}
		if (loaderSensitive(projectType)) {
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

	/** Versions of {@code projectId} compatible with {@code gameVersion} on Fabric, newest first (Modrinth's own default order). Mods only - see {@link #versions(String, String, String)} for resource packs/shaders. */
	public static List<ProjectVersion> versions(String projectId, String gameVersion) throws IOException {
		return versions(projectId, gameVersion, "mod");
	}

	/**
	 * @param projectType same version/loader-filtering exception as {@link #search}:
	 *                    resource packs and shaders aren't Fabric-loader-specific and
	 *                    often don't tag every game version, so only "mod" actually
	 *                    filters by {@code gameVersion}/{@code fabric} - everything
	 *                    else returns every published version, unfiltered.
	 */
	public static List<ProjectVersion> versions(String projectId, String gameVersion, String projectType) throws IOException {
		StringBuilder url = new StringBuilder(API_BASE).append("/project/").append(encode(projectId)).append("/version");
		List<String> params = new ArrayList<>();
		if (versionSensitive(projectType)) {
			params.add("game_versions=" + encode("[\"" + gameVersion + "\"]"));
		}
		if (loaderSensitive(projectType)) {
			params.add("loaders=" + encode("[\"fabric\"]"));
		}
		if (!params.isEmpty()) {
			url.append('?').append(String.join("&", params));
		}
		JsonObject response = wrapArray(getRaw(url.toString()));
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

	public record GalleryImage(String url, boolean featured, String title, String description) {
	}

	/**
	 * The fuller project record backing {@link net.veloclient.launcher.ui.ProjectDetailView} - everything
	 * {@link Project} has, plus the long-form body (Markdown), the image gallery, and the counters Modrinth's
	 * own project page shows. Kept separate from {@link Project} (used for the lightweight dependency-name
	 * lookups sprinkled through {@link net.veloclient.launcher.ui.InstanceDetailView}) so those callers don't
	 * pay for fields they never use.
	 */
	public record ProjectDetail(
			String id,
			String slug,
			String title,
			String description,
			String body,
			@SerializedName("icon_url") String iconUrl,
			long downloads,
			long followers,
			List<String> categories,
			List<GalleryImage> gallery) {

		public List<GalleryImage> galleryOrEmpty() {
			return gallery == null ? List.of() : gallery;
		}

		public List<String> categoriesOrEmpty() {
			return categories == null ? List.of() : categories;
		}
	}

	public static Optional<ProjectDetail> getProjectDetail(String projectId) {
		try {
			return Optional.of(GSON.fromJson(getJson(API_BASE + "/project/" + encode(projectId)), ProjectDetail.class));
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
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), url);
	}

	private static String postRaw(String url, String json) throws IOException {
		return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json)), url);
	}

	/**
	 * Sends with the User-Agent; on "429 Too Many Requests" waits until Modrinth's rate limit resets (its
	 * X-Ratelimit-Reset / Retry-After header, at most 20 s) and tries again - a big import used to hit the limit and
	 * every mod after that silently counted as "not on Modrinth".
	 */
	private static String send(HttpRequest.Builder builder, String url) throws IOException {
		HttpRequest request = builder.header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(20)).build();
		try {
			for (int attempt = 0; ; attempt++) {
				HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 429 && attempt < 3) {
					long wait = response.headers().firstValue("X-Ratelimit-Reset")
							.or(() -> response.headers().firstValue("Retry-After"))
							.map(v -> {
								try {
									return Long.parseLong(v.trim());
								} catch (NumberFormatException e) {
									return 5L;
								}
							}).orElse(5L);
					Thread.sleep(Math.min(20, Math.max(1, wait)) * 1000L);
					continue;
				}
				if (response.statusCode() >= 400) {
					throw new IOException("HTTP " + response.statusCode() + " from Modrinth: " + url);
				}
				return response.body();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted contacting Modrinth", e);
		}
	}

	/** sha1 -> the version that file belongs to, for many files in one request ({@code POST /version_files}). */
	public static java.util.Map<String, ProjectVersion> versionsByFileSha1(java.util.Collection<String> sha1s) throws IOException {
		java.util.Map<String, ProjectVersion> out = new java.util.HashMap<>();
		if (sha1s.isEmpty()) {
			return out;
		}
		JsonObject body = new JsonObject();
		body.add("hashes", GSON.toJsonTree(List.copyOf(sha1s)));
		body.addProperty("algorithm", "sha1");
		JsonObject response = com.google.gson.JsonParser.parseString(postRaw(API_BASE + "/version_files", body.toString())).getAsJsonObject();
		for (var entry : response.entrySet()) {
			out.put(entry.getKey(), GSON.fromJson(entry.getValue(), ProjectVersion.class));
		}
		return out;
	}

	/**
	 * sha1 -> the newest version of that file's project for these loaders / game versions, for many files in one
	 * request ({@code POST /version_files/update}) - a Forge or 1.20 jar's hash comes back as its Fabric build for
	 * the target version.
	 */
	public static java.util.Map<String, ProjectVersion> latestForFiles(java.util.Collection<String> sha1s, String loader, String gameVersion)
			throws IOException {
		java.util.Map<String, ProjectVersion> out = new java.util.HashMap<>();
		if (sha1s.isEmpty()) {
			return out;
		}
		JsonObject body = new JsonObject();
		body.add("hashes", GSON.toJsonTree(List.copyOf(sha1s)));
		body.addProperty("algorithm", "sha1");
		body.add("loaders", GSON.toJsonTree(List.of(loader)));
		body.add("game_versions", GSON.toJsonTree(List.of(gameVersion)));
		JsonObject response = com.google.gson.JsonParser.parseString(postRaw(API_BASE + "/version_files/update", body.toString())).getAsJsonObject();
		for (var entry : response.entrySet()) {
			out.put(entry.getKey(), GSON.fromJson(entry.getValue(), ProjectVersion.class));
		}
		return out;
	}

	/** Projects by id or slug, many in one request ({@code GET /projects?ids=[...]}); unknown ones are just missing. */
	public static List<Project> projects(java.util.Collection<String> idsOrSlugs) throws IOException {
		List<Project> out = new ArrayList<>();
		List<String> all = List.copyOf(idsOrSlugs);
		for (int from = 0; from < all.size(); from += 100) {
			List<String> part = all.subList(from, Math.min(all.size(), from + 100));
			String url = API_BASE + "/projects?ids=" + encode(GSON.toJson(part));
			for (var element : com.google.gson.JsonParser.parseString(getRaw(url)).getAsJsonArray()) {
				out.add(GSON.fromJson(element, Project.class));
			}
		}
		return out;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
