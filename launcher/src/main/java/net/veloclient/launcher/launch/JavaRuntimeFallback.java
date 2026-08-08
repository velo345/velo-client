package net.veloclient.launcher.launch;

import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Self-heals a missing "java" binary in the running app's own runtime.
 *
 * <p>A jpackage-installed build's bundled runtime doesn't reliably keep the
 * standalone {@code java}/{@code java.exe} launcher (GameLauncher needs one
 * to start Minecraft as a child process, but the app itself doesn't - its
 * native launcher stub starts its own JVM without it, so jpackage sees no
 * reason to keep it, and every attempt at making jpackage's build keep it
 * anyway broke the Windows installer for reasons that never surfaced in its
 * own output). Rather than fight jpackage/WiX over the installer's contents,
 * the app carries a spare copy of the binary as an ordinary bundled resource
 * (see launcher/build.gradle's {@code bundleJavaRuntimeFallback} task) and
 * copies it into a cache directory of its own the first time it's actually
 * needed - never into the install directory itself, which might not be
 * writable by a normal user (e.g. Program Files on Windows).
 *
 * <p>The cached copy is stamped with the launcher version that produced it
 * (a {@code .source-version} marker file next to it) and re-copied whenever
 * that no longer matches {@link AppVersion#VERSION} - this used to cache
 * forever after the very first launch, so a machine whose cache was created
 * by an old launcher build stayed stuck on that old runtime image even after
 * installing every later update. That's a real, confirmed bug, not a
 * hypothetical one: when {@code --add-modules ALL-MODULE-PATH} was added to
 * fix Minecraft's own DNS SRV redirect resolver needing {@code
 * jdk.naming.dns} (see launcher/build.gradle), every machine that had
 * already run an older launcher build kept right on missing that module
 * forever, since nothing ever told this cache to refresh.
 */
public final class JavaRuntimeFallback {

	private static final String VERSION_MARKER_FILE = ".source-version";

	private JavaRuntimeFallback() {
	}

	/** @return a working "java"/"java.exe" path, copying the running JVM's own runtime into a cache dir the first time this is needed (or whenever the launcher itself has been updated since it was last cached) */
	public static Path ensureAvailable() throws IOException {
		boolean isWindows = OsRules.currentOsName().equals("windows");
		String exeName = isWindows ? "java.exe" : "java";
		Path cachedRuntimeDir = VeloPaths.root().resolve("java-runtime");
		Path cachedJava = cachedRuntimeDir.resolve("bin").resolve(exeName);
		Path versionMarker = cachedRuntimeDir.resolve(VERSION_MARKER_FILE);
		if (Files.exists(cachedJava) && Files.exists(versionMarker)
				&& AppVersion.VERSION.equals(readMarker(versionMarker))) {
			return cachedJava;
		}

		// Either never cached, or cached by a launcher version other than
		// this one - wipe and re-clone rather than risk mixing an old
		// runtime image with whatever this version now needs.
		deleteRecursively(cachedRuntimeDir);

		// "java" needs its whole runtime tree alongside it (lib/, the linked
		// module image, ...) - the running app's own java.home already has
		// all of that (it's running off it right now), just not the bin/
		// launcher itself, so clone the whole thing into our cache rather
		// than trying to figure out the minimal subset.
		Path runningRuntime = Path.of(System.getProperty("java.home"));
		copyRuntimeTree(runningRuntime, cachedRuntimeDir);

		Files.createDirectories(cachedJava.getParent());
		String resourcePath = "/net/veloclient/launcher/javaruntime/" + exeName;
		try (InputStream in = JavaRuntimeFallback.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IOException("No bundled java runtime fallback found (missing " + resourcePath
						+ " - was the launcher built with ./gradlew :launcher:build?)");
			}
			Files.copy(in, cachedJava, StandardCopyOption.REPLACE_EXISTING);
		}
		if (!isWindows) {
			cachedJava.toFile().setExecutable(true);
		}
		Files.writeString(versionMarker, AppVersion.VERSION, StandardCharsets.UTF_8);
		return cachedJava;
	}

	private static String readMarker(Path marker) {
		try {
			return Files.readString(marker, StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			return "";
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var files = Files.walk(dir)) {
			for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void copyRuntimeTree(Path source, Path dest) throws IOException {
		Files.createDirectories(dest);
		try (var files = Files.walk(source)) {
			for (Path path : files.toList()) {
				Path relative = source.relativize(path);
				Path target = dest.resolve(relative.toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
