package net.veloclient.launcher.launch;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Downloads (once per version, cached under the shared {@link GameDataPaths#libraries()})
 * and installs the newest Fabric API build for that Minecraft version, read from Fabric's
 * Maven index on each launcher start (other mods often need a recent one). Offline it uses
 * the newest build already cached, and never goes below the build velo-client was built
 * against ({@link GameVersion#fabricApiVersion()}).
 */
public final class FabricApiInstaller {

	private static final String MAVEN_BASE_URL = "https://maven.fabricmc.net/";
	private static final String METADATA = MAVEN_BASE_URL + "net/fabricmc/fabric-api/fabric-api/maven-metadata.xml";
	private static final java.util.Map<String, String> NEWEST = new java.util.concurrent.ConcurrentHashMap<>();

	private FabricApiInstaller() {
	}

	public static void installInto(Path modsDir, GameVersion version) {
		try {
			Files.createDirectories(modsDir);
			String apiVersion = newestFor(version);
			String targetName = "fabric-api-" + apiVersion + ".jar";
			String relativePath = "net/fabricmc/fabric-api/fabric-api/" + apiVersion + "/" + targetName;
			Path cached = GameDataPaths.libraries().resolve(relativePath);
			Downloader.ensure(URI.create(MAVEN_BASE_URL + relativePath), cached, null, -1, b -> { });
			Path target = modsDir.resolve(targetName);
			// Already the right file: leave it alone. Rewriting it on every launch failed on Windows
			// whenever the same profile was already running (the jar is locked by that game).
			boolean upToDate = Files.exists(target) && Files.size(target) == Files.size(cached);
			try (Stream<Path> existing = Files.list(modsDir)) {
				for (Path file : existing.filter(FabricApiInstaller::isFabricApiJar).toList()) {
					if (!file.getFileName().toString().equals(targetName)) {
						try {
							Files.delete(file);
						} catch (IOException locked) {
							// In use by a running game - it's cleaned up on a later launch.
						}
					}
				}
			}
			if (!upToDate) {
				Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to install Fabric API into " + modsDir, e);
		}
	}

	/** The newest Fabric API build for this Minecraft version (looked up once per launcher run). */
	static String newestFor(GameVersion version) {
		return NEWEST.computeIfAbsent(version.id(), id -> {
			String best = version.fabricApiVersion();
			try {
				var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8))
						.followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
				var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create(METADATA))
						.timeout(java.time.Duration.ofSeconds(10)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					var m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(response.body());
					while (m.find()) {
						best = newer(best, m.group(1), id);
					}
					return best;
				}
			} catch (Exception e) {
				// offline: fall through to what's cached
			}
			Path dir = GameDataPaths.libraries().resolve("net/fabricmc/fabric-api/fabric-api");
			try (Stream<Path> cached = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
				for (Path p : cached.toList()) {
					if (Files.exists(p.resolve("fabric-api-" + p.getFileName() + ".jar"))) {
						best = newer(best, p.getFileName().toString(), id);
					}
				}
			} catch (IOException ignored) {
			}
			return best;
		});
	}

	/** {@code candidate} if it's a "...+{mc}" build newer than {@code current}, else {@code current}. */
	private static String newer(String current, String candidate, String mc) {
		if (!candidate.endsWith("+" + mc)) {
			return current;
		}
		int[] a = numbers(current);
		int[] b = numbers(candidate);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) {
				return y > x ? candidate : current;
			}
		}
		return current;
	}

	private static int[] numbers(String version) {
		String core = version.split("\\+")[0];
		String[] parts = core.split("\\.");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
			} catch (NumberFormatException e) {
				out[i] = 0;
			}
		}
		return out;
	}

	private static boolean isFabricApiJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("fabric-api-") && name.endsWith(".jar");
	}
}
