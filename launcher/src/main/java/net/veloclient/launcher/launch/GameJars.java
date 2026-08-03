package net.veloclient.launcher.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Installs the bundled velo-client jar for a given Minecraft version into a
 * profile's mods folder. The jars themselves are baked into this launcher's
 * own classpath at build time (see launcher/build.gradle's
 * {@code bundleGameJars} task, which copies each version's built jar from
 * {@code versions/<version>/build/libs/} into
 * {@code net/veloclient/launcher/gamejars/<version>.jar}), so the launcher
 * stays self-contained and never needs to download or locate it elsewhere.
 */
public final class GameJars {

	private GameJars() {
	}

	/**
	 * Removes any previously-installed {@code velo-client-*.jar} from
	 * {@code modsDir} (same dedup logic {@code build-and-deploy.sh} uses for
	 * the real {@code .minecraft/mods}, so a stale version never lingers),
	 * then copies the current bundled jar in.
	 */
	public static void installInto(Path modsDir, GameVersion version) {
		try {
			Files.createDirectories(modsDir);
			try (Stream<Path> existing = Files.list(modsDir)) {
				for (Path file : existing.filter(GameJars::isVeloJar).toList()) {
					Files.delete(file);
				}
			}
			String resourcePath = "/net/veloclient/launcher/gamejars/" + version.id() + ".jar";
			try (InputStream in = GameJars.class.getResourceAsStream(resourcePath)) {
				if (in == null) {
					throw new IllegalStateException("No bundled velo-client jar for Minecraft " + version.id()
							+ " (missing " + resourcePath + " - was the launcher built with ./gradlew :launcher:build?)");
				}
				Path dest = modsDir.resolve("velo-client-" + version.id() + ".jar");
				Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to install velo-client jar into " + modsDir, e);
		}
	}

	private static boolean isVeloJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("velo-client-") && name.endsWith(".jar");
	}
}
