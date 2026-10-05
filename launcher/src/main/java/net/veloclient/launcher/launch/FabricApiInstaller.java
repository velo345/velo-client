package net.veloclient.launcher.launch;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Downloads (once per version, cached under the shared {@link GameDataPaths#libraries()})
 * and installs the exact Fabric API build velo-client was built against for
 * that Minecraft version - velo-client is {@code modImplementation}-only on
 * Fabric API (see {@code build.gradle.kts}), so it won't load without Fabric
 * API's own jar also present in the mods folder.
 */
public final class FabricApiInstaller {

	private static final String MAVEN_BASE_URL = "https://maven.fabricmc.net/";

	private FabricApiInstaller() {
	}

	public static void installInto(Path modsDir, GameVersion version) {
		try {
			Files.createDirectories(modsDir);
			String apiVersion = version.fabricApiVersion();
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

	private static boolean isFabricApiJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("fabric-api-") && name.endsWith(".jar");
	}
}
