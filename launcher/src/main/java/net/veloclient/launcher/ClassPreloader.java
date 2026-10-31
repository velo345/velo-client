package net.veloclient.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Loads the launcher's own classes on a background thread while JavaFX starts and the splash plays, so building Home
 * on the FX thread finds them (and the JavaFX classes they extend) already loaded and verified. Loads without
 * initialising ({@code Class.forName(name, false, ...)}) - no static initialiser runs off the FX thread. UI classes go
 * first. {@code -Dvelo.noPreload} turns it off (for measuring).
 */
final class ClassPreloader {

	private ClassPreloader() {
	}

	static void start() {
		if (System.getProperty("velo.noPreload") != null) {
			return;
		}
		Thread thread = new Thread(ClassPreloader::run, "velo-preload");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run() {
		long started = System.nanoTime();
		List<String> names = classNames();
		ClassLoader loader = ClassPreloader.class.getClassLoader();
		int loaded = 0;
		for (String name : names) {
			try {
				Class.forName(name, false, loader);
				loaded++;
			} catch (Throwable ignored) {
				// Optional dependency missing, or a class that can't load here - the app will say so if it ever needs it.
			}
		}
		LauncherLog.info("Preloaded " + loaded + " classes in " + (System.nanoTime() - started) / 1_000_000 + " ms");
	}

	/** The launcher's classes in its jar: LauncherApp and ui/ first, the rest after; devtools / tour code last. */
	private static List<String> classNames() {
		List<String> first = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		try {
			Path jar = Path.of(ClassPreloader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isRegularFile(jar)) {
				return first; // running from a classes folder (gradle run): nothing worth doing
			}
			try (JarFile file = new JarFile(jar.toFile())) {
				file.stream().forEach(entry -> {
					String path = entry.getName();
					if (!path.endsWith(".class") || !path.startsWith("net/veloclient/launcher/") || path.contains("module-info")) {
						return;
					}
					String name = path.substring(0, path.length() - 6).replace('/', '.');
					if (name.startsWith("net.veloclient.launcher.ui.") || name.startsWith("net.veloclient.launcher.LauncherApp")) {
						first.add(name);
					} else {
						rest.add(name);
					}
				});
			}
		} catch (Exception | LinkageError e) {
			return first;
		}
		first.addAll(rest);
		return first;
	}
}
