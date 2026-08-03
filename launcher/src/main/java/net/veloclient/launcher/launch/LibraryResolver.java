package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the vanilla + Fabric library lists into concrete downloadable
 * files under the shared {@link GameDataPaths#libraries()} maven-layout
 * cache, filtering out libraries that don't apply to the current OS/arch and
 * de-duplicating by {@code group:artifact} (Fabric's copy of a coordinate
 * wins over vanilla's, since Fabric libraries are resolved first).
 */
public final class LibraryResolver {

	public record ResolvedLibrary(Path path, URI url, String sha1, long size) {
	}

	private LibraryResolver() {
	}

	/** @param librarySources merged in priority order - first occurrence of a {@code group:artifact} coordinate wins */
	public static List<ResolvedLibrary> resolve(JsonArray... librarySources) {
		Map<String, ResolvedLibrary> byCoordinate = new LinkedHashMap<>();
		for (JsonArray libraries : librarySources) {
			if (libraries == null) {
				continue;
			}
			for (var element : libraries) {
				JsonObject library = element.getAsJsonObject();
				if (library.has("rules") && !OsRules.isAllowed(library.getAsJsonArray("rules"), OsRules.NO_FEATURES)) {
					continue;
				}
				String name = library.get("name").getAsString();
				String coordinate = coordinateKey(name);
				byCoordinate.computeIfAbsent(coordinate, k -> resolveOne(library, name));
			}
		}
		return new ArrayList<>(byCoordinate.values());
	}

	/**
	 * {@code group:artifact}, plus the classifier when present (e.g.
	 * {@code natives-linux}) - a natives-classified jar and its unclassified
	 * base jar are different files that both belong on the classpath (the
	 * base jar has the actual API classes, e.g. LWJGL's, the natives jar just
	 * bundles the native libraries), so they must never collapse onto the
	 * same dedup key even though they share group:artifact.
	 */
	private static String coordinateKey(String mavenName) {
		String[] parts = mavenName.split(":");
		if (parts.length >= 4) {
			return parts[0] + ":" + parts[1] + ":" + parts[3];
		}
		return parts.length >= 2 ? parts[0] + ":" + parts[1] : mavenName;
	}

	private static ResolvedLibrary resolveOne(JsonObject library, String mavenName) {
		if (library.has("downloads")) {
			JsonObject downloads = library.getAsJsonObject("downloads");
			if (downloads.has("artifact")) {
				JsonObject artifact = downloads.getAsJsonObject("artifact");
				String relativePath = artifact.get("path").getAsString();
				return new ResolvedLibrary(
						GameDataPaths.libraries().resolve(relativePath),
						URI.create(artifact.get("url").getAsString()),
						artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
						artifact.has("size") ? artifact.get("size").getAsLong() : -1);
			}
		}
		// Fabric-style entry: a maven coordinate + repository base url, with sha1/size given directly rather than nested under "downloads".
		String relativePath = mavenPath(mavenName);
		String baseUrl = library.has("url") ? library.get("url").getAsString() : "https://maven.fabricmc.net/";
		if (!baseUrl.endsWith("/")) {
			baseUrl = baseUrl + "/";
		}
		return new ResolvedLibrary(GameDataPaths.libraries().resolve(relativePath), URI.create(baseUrl + relativePath),
				library.has("sha1") ? library.get("sha1").getAsString() : null,
				library.has("size") ? library.get("size").getAsLong() : -1);
	}

	private static String mavenPath(String mavenName) {
		String[] parts = mavenName.split(":");
		String group = parts[0].replace('.', '/');
		String artifact = parts[1];
		String version = parts[2];
		String classifier = parts.length > 3 ? "-" + parts[3] : "";
		return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
	}
}
