package net.veloclient.launcher.launch;

import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
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
 */
public final class JavaRuntimeFallback {

	private JavaRuntimeFallback() {
	}

	/** @return a working "java"/"java.exe" path, copying the running JVM's own runtime into a cache dir the first time this is needed */
	public static Path ensureAvailable() throws IOException {
		boolean isWindows = OsRules.currentOsName().equals("windows");
		String exeName = isWindows ? "java.exe" : "java";
		Path cachedRuntimeDir = VeloPaths.root().resolve("java-runtime");
		Path cachedJava = cachedRuntimeDir.resolve("bin").resolve(exeName);
		if (Files.exists(cachedJava)) {
			return cachedJava;
		}

		// "java" needs its whole runtime tree alongside it (lib/, the linked
		// module image, ...) - the running app's own java.home already has
		// all of that (it's running off it right now), just not the bin/
		// launcher itself, so clone the whole thing into our cache once
		// rather than trying to figure out the minimal subset.
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
		return cachedJava;
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
