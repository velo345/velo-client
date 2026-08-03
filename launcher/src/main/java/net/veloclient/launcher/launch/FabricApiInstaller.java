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
			try (Stream<Path> existing = Files.list(modsDir)) {
				for (Path file : existing.filter(FabricApiInstaller::isFabricApiJar).toList()) {
					Files.delete(file);
				}
			}
			String apiVersion = version.fabricApiVersion();
			String relativePath = "net/fabricmc/fabric-api/fabric-api/" + apiVersion + "/fabric-api-" + apiVersion + ".jar";
			Path cached = GameDataPaths.libraries().resolve(relativePath);
			Downloader.ensure(URI.create(MAVEN_BASE_URL + relativePath), cached, null, -1, b -> { });
			Files.copy(cached, modsDir.resolve("fabric-api-" + apiVersion + ".jar"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new RuntimeException("Failed to install Fabric API into " + modsDir, e);
		}
	}

	private static boolean isFabricApiJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("fabric-api-") && name.endsWith(".jar");
	}
}
