package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Other clients' own internal mods (Feather/Dawn's {@code net.digitalingot.feather}, Lunar's {@code com.moonsworth}) only
 * run inside their launchers: with Velo they crash the game at startup (Feather's mixin plugin can't find its bundled
 * libraries). They get into profiles by copying a mods folder over - often named nothing like the client - so they're
 * recognised by what's inside: the import leaves them out, and Play turns them off in an existing profile.
 */
public final class ForeignClientJars {

	private static final String[] INTERNAL_PACKAGES = {"net/digitalingot/", "com/moonsworth/", "gg/dawn/"};

	private ForeignClientJars() {
	}

	/** Whether {@code jar} is another client's internal mod. */
	public static boolean isForeignClient(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				for (String prefix : INTERNAL_PACKAGES) {
					if (name.startsWith(prefix)) {
						return true;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return false;
	}

	/** Renames every other client's internal mod in {@code modsDir} to {@code .disabled}; returns their file names. */
	public static List<String> disableIn(Path modsDir) {
		List<String> disabled = new ArrayList<>();
		try (var files = Files.list(modsDir)) {
			for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
				if (isForeignClient(jar)) {
					Files.move(jar, jar.resolveSibling(jar.getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
					disabled.add(jar.getFileName().toString());
				}
			}
		} catch (IOException e) {
			LauncherLog.warn("Couldn't check for other clients' mods in " + modsDir, e);
		}
		if (!disabled.isEmpty()) {
			LauncherLog.info("Turned off other clients' internal mods (they crash outside their own launcher): " + disabled);
		}
		return disabled;
	}
}
