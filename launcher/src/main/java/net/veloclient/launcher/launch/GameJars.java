package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Installs the bundled velo-client jar for a given Minecraft version into a
 * profile's mods folder. The jars themselves are baked into this launcher's
 * own classpath at build time (see launcher/build.gradle's
 * {@code bundleGameJars} task, which copies each version's built jar from
 * {@code versions/<version>/build/libs/} into
 * {@code net/veloclient/launcher/gamejars/<version>.jar}), so the launcher
 * works fully offline. Newer jars fetched by the in-launcher updater
 * ({@link UpdateService}) take precedence over the bundled ones.
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
			byte[] bundled = currentBytes(version.id());
			if (bundled == null) {
				throw new IllegalStateException("No bundled velo-client jar for Minecraft " + version.id()
						+ " (was the launcher built with ./gradlew :launcher:build?)");
			}
			installAddons(modsDir, version);
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

	/**
	 * The jar that would be installed for {@code key} ({@code "26.2"} or {@code "26.2-addons"}): a
	 * newer one the in-launcher updater downloaded (see {@link UpdateService}) if there is one for
	 * this launcher's version or later, else the copy bundled into the launcher. Null if neither.
	 */
	static byte[] currentBytes(String key) throws IOException {
		Path cached = UpdateService.gameJarsDir().resolve(key + ".jar");
		if (Files.exists(cached) && cacheIsCurrent()) {
			return Files.readAllBytes(cached);
		}
		try (InputStream in = GameJars.class.getResourceAsStream("/net/veloclient/launcher/gamejars/" + key + ".jar")) {
			return in == null ? null : in.readAllBytes();
		}
	}

	/** Downloaded jars from an older release than this launcher (it was updated since) are ignored. */
	private static boolean cacheIsCurrent() {
		Path props = UpdateService.gameJarsDir().resolve("cache.properties");
		try (var reader = Files.newBufferedReader(props)) {
			java.util.Properties p = new java.util.Properties();
			p.load(reader);
			String version = p.getProperty("version", "0");
			return UpdateService.compare(version, net.veloclient.launcher.AppVersion.VERSION) >= 0;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Keeps an installed "Velo Client Addons" jar on the current build: if the profile has one
	 * (enabled or {@code .jar.disabled}), it's replaced with the current addons jar, keeping its
	 * enabled/disabled state. Profiles without addons are left alone - addons stay opt-in.
	 */
	private static void installAddons(Path modsDir, GameVersion version) {
		try (Stream<Path> existing = Files.list(modsDir)) {
			List<Path> installed = existing.filter(GameJars::isAddonsJar).toList();
			if (installed.isEmpty()) {
				return;
			}
			byte[] current = currentBytes(version.id() + "-addons");
			if (current == null) {
				return;
			}
			boolean disabled = installed.stream().allMatch(p -> p.getFileName().toString().endsWith(".disabled"));
			Path dest = modsDir.resolve("velo-addons-" + version.id() + ".jar" + (disabled ? ".disabled" : ""));
			if (installed.size() == 1 && installed.get(0).equals(dest) && Arrays.equals(current, Files.readAllBytes(dest))) {
				return;
			}
			for (Path file : installed) {
				try {
					Files.delete(file);
				} catch (IOException e) {
					LauncherLog.warn("Couldn't remove old addons jar " + file.getFileName(), e);
				}
			}
			Files.write(dest, current, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			LauncherLog.warn("Couldn't update the Velo Client Addons jar in " + modsDir, e);
		}
	}

	private static boolean isAddonsJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("velo-addons-") && (name.endsWith(".jar") || name.endsWith(".jar.disabled"));
	}

	private static boolean isVeloJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("velo-client-") && name.endsWith(".jar");
	}
}
