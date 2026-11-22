package net.veloclient.launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Launcher updates without an installer. Each release carries {@code velo-client-launcher-<v>-app-<platform>.zip}: the
 * launcher's own files (the jpackage "app" folder) and a manifest. The updater unpacks it into
 * {@code <velo root>/launcher-app/<v>/} - the player's own folder, so no admin rights, no Windows permission prompt,
 * no Linux password prompt, no installer window - and points {@code current.txt} at it. On every start the installed
 * launcher {@link #hop hops} straight into the newest unpacked version (same bundled Java runtime, its files instead
 * of the installed ones), before any window opens.
 *
 * <p>Why: the installer route failed for many Windows players (bug reports 2026-11: "installer exit null" - the quiet
 * install never finished, the wizard came up, players had to update 2-3 times).
 *
 * <p>Safety: a version is only used when its manifest asks for the Java version this install bundles (else the
 * installer is used once more); a version that doesn't reach its first window within 90 s twice in a row is marked
 * failed and the installed version starts again. A newer installed version (a fresh installer) always wins over an
 * older unpacked one.
 */
public final class AppUpdate {

	private static final String MANIFEST = "velo-launcher-app.json";
	private static final long BOOT_TIMEOUT_MS = 90_000;

	private AppUpdate() {
	}

	public static Path root() {
		return VeloPaths.root().resolve("launcher-app");
	}

	/** windows-x64, linux-x64, macos-arm64 or macos-x64 - the suffix of this platform's app zip. */
	public static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return "windows-x64";
		}
		if (os.contains("mac")) {
			return "macos-" + (arch.contains("aarch64") || arch.contains("arm") ? "arm64" : "x64");
		}
		return "linux-x64";
	}

	/** Whether this launcher can update itself this way at all (installed, i.e. started from the jpackage app). */
	public static boolean supported() {
		return UpdateService.packaged() && !Boolean.getBoolean("velo.noAppUpdate");
	}

	/** The app zip of {@code version} for this platform among a release's assets, or null. */
	static UpdateService.Asset pick(List<UpdateService.Asset> assets, String version) {
		String name = "velo-client-launcher-" + version + "-app-" + platform() + ".zip";
		for (UpdateService.Asset asset : assets) {
			if (asset.name().equals(name)) {
				return asset;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ install

	/**
	 * Downloads, checks and unpacks {@code asset} as version {@code version}; afterwards the next start runs it.
	 *
	 * @throws IOException also when the zip asks for a different Java runtime (the caller then uses the installer)
	 */
	static void install(UpdateService.Asset asset, String version, Consumer<String> status, Consumer<UpdateService.Transfer> progress)
			throws IOException, InterruptedException {
		Files.createDirectories(root());
		Path zip = UpdateService.updatesDir().resolve(asset.name());
		status.accept("Downloading Velo Client " + version + "...");
		UpdateService.downloadAsset(asset, zip, progress);
		installZip(zip, version, status);
	}

	/**
	 * Unpacks an app zip that is already on disk (the tester launcher downloads its builds from the Velo server) and
	 * points current.txt at it; deletes the zip afterwards.
	 */
	public static void installZip(Path zip, String version, Consumer<String> status) throws IOException {
		Files.createDirectories(root());
		status.accept("Unpacking the update...");
		Path target = root().resolve(version);
		Path temp = root().resolve(version + ".unpacking");
		deleteTree(temp);
		Files.createDirectories(temp);
		JsonObject manifest = null;
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName();
				// flat folder only: no paths, nothing that could land outside the target
				if (entry.isDirectory() || name.contains("/") || name.contains("\\") || name.contains("..")) {
					continue;
				}
				Path out = temp.resolve(name);
				Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				if (name.equals(MANIFEST)) {
					manifest = JsonParser.parseString(Files.readString(out, StandardCharsets.UTF_8)).getAsJsonObject();
				}
			}
		}
		check(temp, manifest, version);
		deleteTree(target);
		Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
		Files.deleteIfExists(root().resolve("failed-" + version));
		Files.deleteIfExists(root().resolve("booting-" + version));
		writeCurrent(version);
		Files.deleteIfExists(zip);
		UpdateService.log("App update " + version + " unpacked to " + target + " - starts with the next launch");
	}

	/** The unpacked folder must be complete and made for the Java runtime this install bundles. */
	private static void check(Path dir, JsonObject manifest, String version) throws IOException {
		if (manifest == null) {
			throw new IOException("The update has no manifest");
		}
		int java = manifest.has("javaFeature") ? manifest.get("javaFeature").getAsInt() : -1;
		if (java != Runtime.version().feature()) {
			throw new IOException("The update needs Java " + java + ", this install has " + Runtime.version().feature());
		}
		if (!platform().equals(manifest.has("platform") ? manifest.get("platform").getAsString() : "")) {
			throw new IOException("The update is for " + manifest.get("platform") + ", this is " + platform());
		}
		if (manifest.has("files")) {
			for (var f : manifest.getAsJsonArray("files")) {
				if (!Files.isRegularFile(dir.resolve(f.getAsString()))) {
					throw new IOException("The update is missing " + f.getAsString());
				}
			}
		}
		if (!Files.isRegularFile(dir.resolve("launcher.jar"))) {
			throw new IOException("The update has no launcher.jar (version " + version + ")");
		}
		// It runs inside this install's JVM: its JVM options (heap, module flags) must be the ones this install starts with.
		List<String> wanted = new ArrayList<>();
		if (manifest.has("javaOptions")) {
			for (var o : manifest.getAsJsonArray("javaOptions")) {
				if (!o.getAsString().startsWith("-Djpackage.app-version")) {
					wanted.add(o.getAsString());
				}
			}
		}
		List<String> installed = installedJavaOptions();
		if (installed != null && !installed.equals(wanted)) {
			throw new IOException("The update changes the launcher's Java options");
		}
		// and the same JavaFX: it's taken from the installed files
		for (var f : manifest.getAsJsonArray("files")) {
			String name = f.getAsString();
			if (name.startsWith("javafx-") && !Files.isRegularFile(installedDir().resolve(name))) {
				throw new IOException("The update needs another JavaFX (" + name + ")");
			}
		}
	}

	/** Where the installed launcher's files are (jpackage's app folder). */
	private static Path installedDir() throws IOException {
		try {
			return Path.of(AppUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
		} catch (java.net.URISyntaxException e) {
			throw new IOException(e);
		}
	}

	/** The installed launcher's JVM options from jpackage's .cfg (without its version line), or null if unreadable. */
	private static List<String> installedJavaOptions() {
		try (var files = Files.list(installedDir())) {
			for (Path cfg : files.filter(p -> p.toString().endsWith(".cfg")).toList()) {
				List<String> out = new ArrayList<>();
				for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
					if (line.startsWith("java-options=") && !line.startsWith("java-options=-Djpackage.app-version")) {
						out.add(line.substring("java-options=".length()));
					}
				}
				return out;
			}
		} catch (IOException ignored) {
			// unknown
		}
		return null;
	}

	private static void writeCurrent(String version) throws IOException {
		Path tmp = root().resolve("current.txt.tmp");
		Files.writeString(tmp, version, StandardCharsets.UTF_8);
		Files.move(tmp, root().resolve("current.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	// ------------------------------------------------------------------ start

	/**
	 * Call first thing in main(): if a newer unpacked version is ready, starts it (same bundled Java) and exits this
	 * process. Never throws - anything odd just starts the installed version.
	 */
	/** True in the installed launcher's main() after it handed over to the newer version (it must not go on). */
	public static boolean hopped;

	public static boolean hop(String[] args) {
		try {
			if (!supported() || System.getProperty("velo.appHop") != null) {
				return false;
			}
			Path pointer = root().resolve("current.txt");
			if (!Files.isRegularFile(pointer)) {
				return false;
			}
			String version = Files.readString(pointer, StandardCharsets.UTF_8).strip();
			if (UpdateService.compare(version, AppVersion.VERSION) <= 0) {
				// a fresh installer brought this or a newer version: the unpacked ones are old now
				cleanUp(null);
				return false;
			}
			Path dir = root().resolve(version);
			if (!Files.isRegularFile(dir.resolve("launcher.jar")) || Files.exists(root().resolve("failed-" + version))) {
				return false;
			}
			// The last start of this version never showed a window: give it one more try, then fall back for good.
			Path booting = root().resolve("booting-" + version);
			if (Files.exists(booting)) {
				long since = System.currentTimeMillis() - Files.getLastModifiedTime(booting).toMillis();
				int tries = parseInt(Files.readString(booting, StandardCharsets.UTF_8).strip());
				if (since > BOOT_TIMEOUT_MS && tries >= 2) {
					Files.writeString(root().resolve("failed-" + version), "didn't start twice", StandardCharsets.UTF_8);
					UpdateService.log("App update " + version + " didn't start twice - using the installed " + AppVersion.VERSION);
					return false;
				}
				Files.writeString(booting, String.valueOf(tries + 1), StandardCharsets.UTF_8);
			} else {
				Files.writeString(booting, "1", StandardCharsets.UTF_8);
			}
			// Same JVM, the new files: a class loader that prefers the new launcher's classes (JavaFX and the JDK stay the
			// installed ones - same versions, checked when unpacking). jpackage's runtime has no java binary to start a
			// second process with.
			System.setProperty("velo.appHop", version);
			System.setProperty("jpackage.app-version", version);
			java.util.List<java.net.URL> urls = new ArrayList<>();
			try (var files = Files.list(dir)) {
				for (Path jar : files.filter(p -> p.toString().endsWith(".jar") && !p.getFileName().toString().startsWith("javafx-")).sorted().toList()) {
					urls.add(jar.toUri().toURL());
				}
			}
			ClassLoader loader = new NewerFirstLoader(urls.toArray(new java.net.URL[0]), AppUpdate.class.getClassLoader());
			Thread.currentThread().setContextClassLoader(loader);
			Class<?> main = Class.forName("net.veloclient.launcher.LauncherApp", true, loader);
			UpdateService.log("Starting the installer-free update " + version + " (installed " + AppVersion.VERSION + ")");
			hopped = true;
			main.getMethod("main", String[].class).invoke(null, (Object) args);
			return hopped;
		} catch (Throwable t) {
			if (hopped) {
				// the new version itself failed while running: let it fail like any launcher would (its own error dialogs ran)
				throw t instanceof RuntimeException r ? r : new RuntimeException(t);
			}
			try {
				UpdateService.log("App update hop failed: " + t);
			} catch (Throwable ignored) {
				// nothing
			}
			return false;
		}
	}

	/** Child-first for the launcher's own classes; the JDK, JavaFX and this hand-over class come from the installed side. */
	private static final class NewerFirstLoader extends java.net.URLClassLoader {
		NewerFirstLoader(java.net.URL[] urls, ClassLoader parent) {
			super("velo-launcher-" + System.getProperty("velo.appHop"), urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				if (c == null && !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("javafx.")
						&& !name.startsWith("com.sun.") && !name.startsWith("jdk.") && !name.startsWith("sun.")) {
					try {
						c = findClass(name);
					} catch (ClassNotFoundException ignored) {
						// not in the new files
					}
				}
				if (c == null) {
					c = getParent().loadClass(name);
				}
				if (resolve) {
					resolveClass(c);
				}
				return c;
			}
		}

		@Override
		public java.net.URL getResource(String name) {
			java.net.URL own = findResource(name);
			return own != null ? own : super.getResource(name);
		}
	}

	/** The running launcher was started by a hop and its window is up: this version works. */
	public static void started() {
		try {
			String hopped = System.getProperty("velo.appHop");
			if (hopped != null) {
				Files.deleteIfExists(root().resolve("booting-" + hopped));
				cleanUp(hopped);
			}
		} catch (IOException ignored) {
			// next time
		}
	}

	/** Removes unpacked versions other than {@code keep} (and half-unpacked ones). */
	private static void cleanUp(String keep) {
		try (var dirs = Files.list(root())) {
			for (Path p : dirs.toList()) {
				String name = p.getFileName().toString();
				if (Files.isDirectory(p) && !name.equals(keep)) {
					deleteTree(p);
				}
			}
		} catch (IOException ignored) {
			// next time
		}
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** For the update dialogs: whether a release can be applied this way here. */
	static boolean usable(UpdateService.Release release) {
		return supported() && release.appZip() != null;
	}
}
