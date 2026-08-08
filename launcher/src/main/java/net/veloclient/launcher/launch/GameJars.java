package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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
	 * then copies the current bundled jar in - unless the file already there
	 * is byte-identical to what would be copied, in which case nothing is
	 * touched at all.
	 *
	 * <p>That skip isn't just an optimization: this runs at the start of
	 * <em>every</em> launch, including a second concurrent launch of the
	 * same profile (multi-instancing) or a Quick Launch while an earlier
	 * launch of it is still running - and a running JVM keeps its own mod
	 * jars open for the whole session. Windows (unlike Linux) refuses to
	 * delete or overwrite a file that's still open elsewhere
	 * ({@code FileSystemException}: "the process cannot access the file
	 * because it is being used by another process" - confirmed in a real
	 * crash log, not hypothetical), which used to take down the entire
	 * launch with an uncaught exception. Since a relaunch of the same
	 * profile is normally installing the exact same bytes it already
	 * installed moments earlier, skipping the no-op delete+copy avoids ever
	 * touching the locked file in the first place for that common case. If
	 * the content genuinely differs (a real upgrade landed while an old
	 * instance of the same profile is still open) and the file is locked,
	 * the failure is now logged and the stale jar is left in place rather
	 * than crashing the launch - the user just won't see the new version
	 * until they close the older instance and relaunch.
	 */
	public static void installInto(Path modsDir, GameVersion version) {
		try {
			Files.createDirectories(modsDir);
			String resourcePath = "/net/veloclient/launcher/gamejars/" + version.id() + ".jar";
			byte[] bundled = readResource(resourcePath);
			Path dest = modsDir.resolve("velo-client-" + version.id() + ".jar");
			if (Files.exists(dest) && Arrays.equals(bundled, Files.readAllBytes(dest))) {
				return;
			}
			try (Stream<Path> existing = Files.list(modsDir)) {
				for (Path file : existing.filter(GameJars::isVeloJar).toList()) {
					try {
						Files.delete(file);
					} catch (IOException e) {
						LauncherLog.warn("Couldn't remove old mod jar " + file.getFileName()
								+ " (likely still open in another running instance of this profile) - leaving it in place", e);
					}
				}
			}
			try {
				Files.write(dest, bundled, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				LauncherLog.warn("Couldn't update " + dest.getFileName()
						+ " (likely locked by another running instance of this profile) - it'll stay on its current version until that instance is closed", e);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to install velo-client jar into " + modsDir, e);
		}
	}

	private static byte[] readResource(String resourcePath) throws IOException {
		try (InputStream in = GameJars.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IllegalStateException("No bundled velo-client jar for this Minecraft version"
						+ " (missing " + resourcePath + " - was the launcher built with ./gradlew :launcher:build?)");
			}
			return in.readAllBytes();
		}
	}

	private static boolean isVeloJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("velo-client-") && name.endsWith(".jar");
	}
}
