package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * In-launcher updates from the GitHub releases of {@code velo345/velo-client}, so nobody has to go
 * back to the website for a new build.
 *
 * <p>Two parts, both from the latest release:
 * <ul>
 *   <li><b>Mod jars</b> ({@code velo-client-<v>+<mc>.jar} and {@code velo-addons-<v>+<mc>.jar}) are
 *   downloaded into {@code updates/gamejars/} and {@link GameJars} prefers them over the copies
 *   bundled into this launcher - every profile gets the new mod on its next launch, no reinstall.
 *   A re-release under the same version number (a hotfix) is picked up too: assets are compared by
 *   their SHA-256 against what's bundled/cached, not just by version.</li>
 *   <li><b>The launcher itself</b>: the release's installer for this OS ({@code .exe} on Windows,
 *   {@code .deb} on Linux) is downloaded and started; the launcher then closes so the installer can
 *   replace it, and the next start is the new version.</li>
 * </ul>
 * Calls block - run them off the FX thread.
 */
public final class UpdateService {

	private static final String RELEASES = "https://api.github.com/repos/velo345/velo-client/releases?per_page=15";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** One downloadable file of a release. */
	public record Asset(String name, String url, long size, String sha256, long updatedAt, long id) {
	}

	/**
	 * What the latest release offers compared to this install. {@code modAssets} are only the jars
	 * that differ from what this launcher would install today.
	 */
	public record Release(String version, String notes, String pageUrl, boolean launcherNewer, Asset installer,
			Map<String, Asset> modAssets) {

		public boolean anything() {
			return launcherNewer && installer != null || !modAssets.isEmpty();
		}
	}

	private UpdateService() {
	}

	/**
	 * The installed launcher (jpackage sets {@code jpackage.app-path} to its executable). Started from the source tree
	 * ({@code ./gradlew :launcher:run}, launch-launcher.sh) there's no installation to replace - only mod jars update then.
	 */
	public static boolean packaged() {
		return System.getProperty("jpackage.app-path") != null;
	}

	public static Path updatesDir() {
		return VeloPaths.root().resolve("updates");
	}

	static Path gameJarsDir() {
		return updatesDir().resolve("gamejars");
	}

	/** Fetches the latest release and works out what's new for this install. */
	public static Release check() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES)).timeout(Duration.ofSeconds(15))
				.header("Accept", "application/vnd.github+json").header("User-Agent", "VeloClientLauncher/" + AppVersion.VERSION)
				.GET().build();
		HttpResponse<String> response = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (response.statusCode() == 200) {
					break;
				}
				// 403/429 from GitHub is usually its limit of 60 checks per hour and IP (shared networks hit it)
				log("Update check: GitHub answered " + response.statusCode() + " (rate limit left "
						+ response.headers().firstValue("x-ratelimit-remaining").orElse("?") + ")");
			} catch (IOException e) {
				log("Update check attempt " + attempt + " failed: " + e);
				if (attempt == 2) {
					throw e;
				}
			}
			if (attempt == 1) {
				Thread.sleep(2000);
			}
		}
		if (response == null || response.statusCode() != 200) {
			throw new IOException("GitHub answered " + (response == null ? "nothing" : response.statusCode()));
		}
		// Releases are published as pre-releases, so /releases/latest doesn't see them: take the
		// highest version among the recent non-draft releases instead.
		JsonObject json = null;
		for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
			JsonObject candidate = element.getAsJsonObject();
			if (candidate.get("draft").getAsBoolean()) {
				continue;
			}
			// Update-test releases ("updatetest-1.2.4") exist only for testers on the test channel; everyone else skips
			// them (older launchers read such a tag as version 0.1.2.4, below any real release, so they skip it too).
			boolean testRelease = candidate.get("tag_name").getAsString().startsWith(TEST_TAG);
			if (testRelease != testChannel) {
				continue;
			}
			if (json == null || compare(tag(candidate), tag(json)) > 0) {
				json = candidate;
			}
		}
		if (json == null) {
			throw new IOException("No releases published yet");
		}
		String version = tag(json);
		String notes = json.has("body") && !json.get("body").isJsonNull() ? json.get("body").getAsString() : "";
		String page = json.get("html_url").getAsString();

		List<Asset> assets = new ArrayList<>();
		JsonArray array = json.getAsJsonArray("assets");
		for (JsonElement element : array) {
			JsonObject a = element.getAsJsonObject();
			String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : null;
			String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7) : null;
			long updated = 0;
			try {
				updated = java.time.Instant.parse(a.get("updated_at").getAsString()).toEpochMilli();
			} catch (RuntimeException ignored) {
				// Unknown - treated as old.
			}
			assets.add(new Asset(a.get("name").getAsString(), a.get("browser_download_url").getAsString(), a.get("size").getAsLong(), sha,
					updated, a.has("id") ? a.get("id").getAsLong() : 0));
		}

		boolean launcherNewer = compare(version, AppVersion.VERSION) > 0;
		Asset installer = null;
		if (!packaged()) {
			// From the source tree: nothing to install over (git pull instead) - only the mod jars below.
			return new Release(version, notes, page, false, null, modJars(assets, version));
		}
		String os = System.getProperty("os.name", "").toLowerCase();
		// A .deb only where dpkg installs it. Arch & co. run the tar.gz build from their package manager (AUR
		// velo-client-launcher): updates come through it, the launcher must not install around it.
		boolean debian = !os.contains("win") && !os.contains("mac") && (onPath("dpkg") || onPath("apt"));
		// macOS: one disk image per CPU (...-macos-arm64.dmg for Apple Silicon, ...-macos-x64.dmg for Intel).
		String macDmg = os.contains("mac") ? "-macos-" + macArch() + ".dmg" : null;
		for (Asset asset : assets) {
			if (os.contains("win") && asset.name().endsWith(".exe")
					|| debian && asset.name().endsWith(".deb")
					|| macDmg != null && asset.name().endsWith(macDmg)) {
				installer = asset;
			}
		}

		Map<String, Asset> mods = testChannel ? Map.of() : modJars(assets, version);
		if (testChannel) {
			log("Test channel: " + version + " (" + (installer == null ? "no installer for this OS" : installer.name()) + ")");
		}
		return new Release(version, notes, page, launcherNewer, installer, mods);
	}

	/** The mod jars of {@code version} that differ from what this launcher would install today. */
	private static Map<String, Asset> modJars(List<Asset> assets, String version) {
		// Mod jars: a newer release always counts; the same version only when its jars were uploaded
		// after this launcher bundled its own (a hotfix re-release) - and never twice for one upload.
		Map<String, Asset> mods = new LinkedHashMap<>();
		int versionOrder = compare(version, AppVersion.VERSION);
		if (versionOrder >= 0) {
			long bundledAt = GameJars.bundledAt();
			java.util.Set<String> downloaded = downloadedAssetIds();
			for (GameVersion game : GameVersion.values()) {
				// The Addons aren't released on GitHub any more: they're for Velo+ members, from the server.
				for (boolean addons : new boolean[] {false}) {
					String name = (addons ? "velo-addons-" : "velo-client-") + version + "+" + game.id() + ".jar";
					String key = game.id() + (addons ? "-addons" : "");
					for (Asset asset : assets) {
						if (!asset.name().equals(name) || downloaded.contains(asset.id() + ":" + asset.updatedAt())) {
							continue;
						}
						boolean hotfix = asset.updatedAt() > bundledAt + 15 * 60_000L;
						if ((versionOrder > 0 || hotfix) && !alreadyHave(key, asset)) {
							mods.put(key, asset);
						}
					}
				}
			}
		}
		return mods;
	}

	/** Release assets already downloaded ("id:updatedAt"), so the same upload is never offered again. */
	private static java.util.Set<String> downloadedAssetIds() {
		java.util.Set<String> ids = new java.util.LinkedHashSet<>();
		Path file = gameJarsDir().resolve("cache.properties");
		if (Files.exists(file)) {
			try (var reader = Files.newBufferedReader(file)) {
				Properties props = new Properties();
				props.load(reader);
				for (String id : props.getProperty("assets", "").split(",")) {
					if (!id.isBlank()) {
						ids.add(id.strip());
					}
				}
			} catch (IOException ignored) {
				// Treat as none.
			}
		}
		return ids;
	}

	private static String tag(JsonObject release) {
		return release.get("tag_name").getAsString().replaceFirst("^v", "").replaceFirst("^" + TEST_TAG, "");
	}

	private static final String TEST_TAG = "updatetest-";

	/**
	 * Update test channel (Shift + click on "Check for updates" in Settings): only "updatetest-&lt;version&gt;" pre-releases,
	 * launcher installer only (no mod jars), so a tester can run the whole update - download, passive install, restart,
	 * check - without a public release. Logged like every update in update.log.
	 */
	public static volatile boolean testChannel;

	/** True if the jar GameJars would install for {@code key} already matches {@code asset}. */
	private static boolean alreadyHave(String key, Asset asset) {
		try {
			byte[] current = GameJars.currentBytes(key);
			if (current == null) {
				return false;
			}
			if (asset.sha256() != null) {
				return asset.sha256().equalsIgnoreCase(sha256(current));
			}
			return current.length == asset.size();
		} catch (Exception e) {
			return false;
		}
	}

	/** Downloads the changed mod jars into the updates cache; GameJars uses them from the next launch on. */
	public static void installModUpdates(Release release, Consumer<String> status) throws IOException, InterruptedException {
		installModUpdates(release, status, f -> { });
	}

	/** {@code progress}: 0..1 of the current download (or -1 when its size is unknown). */
	public static void installModUpdates(Release release, Consumer<String> status, java.util.function.DoubleConsumer progress)
			throws IOException, InterruptedException {
		installModUpdatesTracked(release, status, t -> progress.accept(t.fraction()));
	}

	public static void installModUpdatesTracked(Release release, Consumer<String> status, Consumer<Transfer> progress)
			throws IOException, InterruptedException {
		log("Mod update to " + release.version() + ": " + release.modAssets().keySet());
		Path dir = gameJarsDir();
		Files.createDirectories(dir);
		int done = 0;
		for (Map.Entry<String, Asset> entry : release.modAssets().entrySet()) {
			status.accept("Downloading " + entry.getValue().name() + " (" + (++done) + "/" + release.modAssets().size() + ")");
			Path tmp = dir.resolve(entry.getKey() + ".jar.part");
			download(entry.getValue(), tmp, progress);
			Files.move(tmp, dir.resolve(entry.getKey() + ".jar"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		Properties props = new Properties();
		props.setProperty("version", release.version());
		StringBuilder ids = new StringBuilder(String.join(",", downloadedAssetIds()));
		for (Asset asset : release.modAssets().values()) {
			ids.append(ids.isEmpty() ? "" : ",").append(asset.id()).append(':').append(asset.updatedAt());
		}
		props.setProperty("assets", ids.toString());
		try (var out = Files.newBufferedWriter(dir.resolve("cache.properties"))) {
			props.store(out, "Velo Client mod jars downloaded by the launcher updater");
		}
	}

	/**
	 * Downloads this OS's installer and starts it. Returns true when the installer is running and the
	 * launcher should now close; false if this OS has no installer (open the release page instead).
	 */
	/** "arm64" on Apple Silicon, "x64" on Intel Macs - matches the release's dmg names (see launcher/build.gradle). */
	static String macArch() {
		String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
		return arch.contains("aarch64") || arch.contains("arm") ? "arm64" : "x64";
	}

	public static boolean runInstaller(Release release, Consumer<String> status) throws IOException, InterruptedException {
		return runInstallerTracked(release, status, t -> { });
	}

	public static boolean runInstaller(Release release, Consumer<String> status, java.util.function.DoubleConsumer progress)
			throws IOException, InterruptedException {
		return runInstallerTracked(release, status, t -> progress.accept(t.fraction()));
	}

	/**
	 * Downloads this OS's installer and starts it. Returns true when the installer is running and the launcher should now
	 * close; false if this OS has no installer (open the release page instead).
	 */
	public static boolean runInstallerTracked(Release release, Consumer<String> status, Consumer<Transfer> progress)
			throws IOException, InterruptedException {
		Asset installer = release.installer();
		if (installer == null) {
			return false;
		}
		log("Launcher update " + AppVersion.VERSION + " -> " + release.version() + " (" + installer.name() + ", " + installer.size() + " bytes)");
		Files.createDirectories(updatesDir());
		Path file = updatesDir().resolve(installer.name());
		boolean complete = Files.exists(file) && Files.size(file) == installer.size()
				&& (installer.sha256() == null || installer.sha256().equalsIgnoreCase(sha256Of(file)));
		if (!complete) {
			status.accept("Downloading the new launcher");
			Path tmp = updatesDir().resolve(installer.name() + ".part");
			download(installer, tmp, progress);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} else {
			log("Installer already downloaded and verified");
		}
		String os = System.getProperty("os.name", "").toLowerCase();
		markPending(release.version());
		if (os.contains("win")) {
			status.accept("Installing - Windows may ask once for permission");
			startWindowsInstall(file);
			return true;
		}
		status.accept(os.contains("mac") ? "Opening the update..." : "Installing - confirm the password prompt");
		if (os.contains("mac")) {
			// Mounts the disk image and shows it in Finder; the player drags the new app over the old one.
			new ProcessBuilder("open", file.toString()).start();
			return true;
		}
		// Linux: a graphical sudo prompt installs the .deb; without pkexec hand it to the package app.
		if (onPath("pkexec") && (onPath("apt") || onPath("dpkg"))) {
			List<String> command = onPath("apt") ? List.of("pkexec", "apt", "install", "-y", "--reinstall", file.toString())
					: List.of("pkexec", "dpkg", "-i", file.toString());
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			log("Package install exited with " + process.waitFor() + ": " + output.strip().lines().reduce((a, b) -> b).orElse(""));
			if (process.exitValue() != 0) {
				clearPending();
				throw new IOException("The update couldn't be installed (was the password prompt cancelled?)");
			}
			return true;
		}
		new ProcessBuilder("xdg-open", file.toString()).start();
		return true;
	}

	/**
	 * Windows: no clicking through the installer any more. A small script waits until this launcher has closed (so no file
	 * is in use), runs jpackage's installer passively - a progress bar only, into the folder the launcher is installed in,
	 * with a verbose Windows Installer log next to update.log - and starts the new launcher when it's done. jpackage's .exe
	 * hands its arguments to msiexec; should an installer ever ignore them, it simply shows its normal wizard as before.
	 * The script logs the installer's exit code, which the next start reads (see {@link #afterRestart()}).
	 */
	private static void startWindowsInstall(Path installer) throws IOException {
		String app = System.getProperty("jpackage.app-path");
		Path appExe = app == null ? null : Path.of(app);
		Path installDir = appExe == null ? null : appExe.getParent();
		Path msiLog = updatesDir().resolve("installer.log");
		Path script = updatesDir().resolve("run-update.cmd");
		Path log = logFile();
		StringBuilder cmd = new StringBuilder();
		cmd.append("@echo off\r\n");
		cmd.append("chcp 65001 >nul\r\n");
		cmd.append("echo [%date% %time%] script: waiting for the launcher to close>>\"").append(log).append("\"\r\n");
		cmd.append("timeout /t 3 /nobreak >nul\r\n");
		cmd.append("echo [%date% %time%] script: starting the installer>>\"").append(log).append("\"\r\n");
		cmd.append("\"").append(installer).append("\" /passive /norestart /l*v \"").append(msiLog).append("\"");
		if (installDir != null) {
			// the folder the launcher runs from now, so a player's own install location survives the update
			cmd.append(" INSTALLDIR=\"").append(installDir).append("\"");
		}
		cmd.append("\r\n");
		cmd.append("set code=%errorlevel%\r\n");
		cmd.append("echo [%date% %time%] script: installer exit code %code%>>\"").append(log).append("\"\r\n");
		cmd.append("echo %code%>\"").append(updatesDir().resolve("installer-exit.txt")).append("\"\r\n");
		if (appExe != null) {
			cmd.append("start \"\" \"").append(appExe).append("\"\r\n");
		}
		Files.writeString(script, cmd.toString(), StandardCharsets.UTF_8);
		Files.deleteIfExists(updatesDir().resolve("installer-exit.txt"));
		log("Starting " + script + " (install dir " + installDir + ")");
		new ProcessBuilder("cmd.exe", "/c", "start", "\"Velo update\"", "/min", "cmd.exe", "/c", script.toString())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
	}

	private static Path pendingFile() {
		return updatesDir().resolve("pending-update.properties");
	}

	private static void markPending(String version) {
		Properties props = new Properties();
		props.setProperty("from", AppVersion.VERSION);
		props.setProperty("to", version);
		props.setProperty("startedAt", String.valueOf(System.currentTimeMillis()));
		try (var out = Files.newBufferedWriter(pendingFile())) {
			props.store(out, "Launcher update in progress");
		} catch (IOException e) {
			log("Couldn't write the pending marker: " + e);
		}
	}

	private static void clearPending() {
		try {
			Files.deleteIfExists(pendingFile());
		} catch (IOException ignored) {
			// next check simply finds it again
		}
	}

	/**
	 * Called once at launcher start: if an update was started, did it arrive? Returns null when there's nothing to say,
	 * else a short message for the player (the details are in update.log / installer.log). Cleans the installer up on
	 * success.
	 */
	public static String afterRestart() {
		Path pending = pendingFile();
		if (!Files.exists(pending)) {
			return null;
		}
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(pending)) {
			props.load(in);
		} catch (IOException e) {
			clearPending();
			return null;
		}
		String to = props.getProperty("to", "");
		String exit = null;
		try {
			Path exitFile = updatesDir().resolve("installer-exit.txt");
			if (Files.exists(exitFile)) {
				exit = Files.readString(exitFile).strip();
			}
		} catch (IOException ignored) {
			// unknown
		}
		clearPending();
		if (compare(AppVersion.VERSION, to) >= 0) {
			log("Update to " + to + " arrived (installer exit " + exit + ")");
			try (var files = Files.list(updatesDir())) {
				files.filter(f -> {
					String n = f.getFileName().toString();
					return n.endsWith(".exe") || n.endsWith(".deb") || n.endsWith(".dmg") || n.endsWith(".part");
				}).forEach(f -> {
					try {
						Files.deleteIfExists(f);
					} catch (IOException ignored) {
						// left for next time
					}
				});
			} catch (IOException ignored) {
				// nothing to clean
			}
			return null;
		}
		long age = System.currentTimeMillis() - Long.parseLong(props.getProperty("startedAt", "0"));
		log("Still on " + AppVersion.VERSION + " after trying to update to " + to + " (installer exit " + exit + ", " + age / 1000 + " s ago)");
		String why = exit == null ? "the installer didn't finish"
				: exit.equals("1602") ? "the installation was cancelled (or Windows' permission prompt was declined)"
				: exit.equals("1618") ? "another installation was running at the same time"
				: exit.equals("0") ? "the installer finished but the old version is still here" : "the installer stopped with code " + exit;
		return "The update to " + to + " didn't install - " + why + ". Try again from the bell; the details are in " + logFile() + ".";
	}

	/**
	 * Linux: after the .deb replaced the files, start the launcher again. It must start <em>after</em> this one has exited:
	 * started right away it found this launcher still holding the single-instance lock, asked it to come to the front and quit -
	 * and then this one closed too, so nothing was left running. A detached shell waits a moment, then starts it.
	 */
	public static void relaunch() {
		try {
			String app = System.getProperty("jpackage.app-path");
			if (app == null || !Files.isExecutable(Path.of(app))) {
				LauncherLog.warn("No installed launcher to restart (jpackage.app-path " + app + ")", null);
				return;
			}
			List<String> command = new ArrayList<>();
			if (onPath("setsid")) {
				command.add("setsid");
			}
			command.addAll(List.of("sh", "-c", "sleep 2; exec \"$0\" >/dev/null 2>&1", app));
			new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			LauncherLog.warn("Couldn't restart the launcher after updating", e);
		}
	}

	/** Where a download stands, for the progress display. {@code total} is -1 when unknown. */
	public record Transfer(String file, long done, long total, double bytesPerSecond, int attempt) {

		public double fraction() {
			return total > 0 ? Math.min(1.0, done / (double) total) : -1;
		}
	}

	private static final int ATTEMPTS = 5;
	/** No byte for this long = the connection hangs: dropped and tried again (resuming). */
	private static final long STALL_MS = 30_000;

	/**
	 * Downloads with resume and retries: the partial file stays between attempts and the next one asks only for the rest
	 * (HTTP Range - GitHub's release storage supports it), up to {@value #ATTEMPTS} attempts with growing pauses, and a
	 * connection that delivers nothing for 30 s is dropped instead of hanging forever (a plain read has no timeout). The
	 * whole file is checked against the release's SHA-256 at the end; a mismatch starts over once from zero. Every attempt
	 * is written to update.log.
	 */
	private static void download(Asset asset, Path target, Consumer<Transfer> progress) throws IOException, InterruptedException {
		IOException last = null;
		boolean restartedAfterBadHash = false;
		for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
			try {
				downloadOnce(asset, target, progress, attempt);
				if (asset.sha256() != null) {
					String actual = sha256Of(target);
					if (!asset.sha256().equalsIgnoreCase(actual)) {
						log("Checksum mismatch for " + asset.name() + " (expected " + asset.sha256() + ", got " + actual + ")");
						Files.deleteIfExists(target);
						if (restartedAfterBadHash) {
							throw new IOException("Download of " + asset.name() + " was corrupted twice - check your connection or antivirus");
						}
						restartedAfterBadHash = true;
						continue;
					}
				}
				log("Downloaded " + asset.name() + " (" + Files.size(target) + " bytes, attempt " + attempt + ")");
				return;
			} catch (IOException e) {
				last = e;
				log("Attempt " + attempt + "/" + ATTEMPTS + " for " + asset.name() + " failed: " + e);
				if (attempt < ATTEMPTS) {
					Thread.sleep(Math.min(15_000L, 1000L << (attempt - 1)));
				}
			}
		}
		throw new IOException("Download of " + asset.name() + " failed after " + ATTEMPTS + " attempts: "
				+ (last == null ? "unknown error" : last.getMessage()), last);
	}

	private static void downloadOnce(Asset asset, Path target, Consumer<Transfer> progress, int attempt) throws IOException, InterruptedException {
		long have = Files.exists(target) ? Files.size(target) : 0;
		if (asset.size() > 0 && have > asset.size()) {
			Files.delete(target);
			have = 0;
		}
		if (asset.size() > 0 && have == asset.size()) {
			return;   // complete from an earlier attempt - only the checksum is left
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(asset.url())).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "VeloClientLauncher/" + AppVersion.VERSION).GET();
		if (have > 0) {
			builder.header("Range", "bytes=" + have + "-");
		}
		HttpResponse<InputStream> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		int code = response.statusCode();
		boolean append = code == 206 && have > 0;
		if (code != 200 && code != 206) {
			response.body().close();
			throw new IOException("server answered " + code);
		}
		if (!append) {
			have = 0;
		}
		long total = asset.size() > 0 ? asset.size()
				: response.headers().firstValueAsLong("Content-Length").orElse(-1L) + (append ? have : 0);
		log("Attempt " + attempt + ": " + asset.name() + " HTTP " + code + (append ? ", resuming at " + have : "") + ", total " + total);
		InputStream in = response.body();
		long[] lastByte = {System.currentTimeMillis()};
		java.util.concurrent.ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "velo-update-watchdog");
			t.setDaemon(true);
			return t;
		});
		boolean[] stalled = {false};
		watchdog.scheduleAtFixedRate(() -> {
			if (System.currentTimeMillis() - lastByte[0] > STALL_MS) {
				stalled[0] = true;
				try {
					in.close();
				} catch (IOException ignored) {
					// closing is all we want
				}
			}
		}, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
		long started = System.nanoTime();
		long startBytes = have;
		long done = have;
		try (in; var out = append ? Files.newOutputStream(target, java.nio.file.StandardOpenOption.APPEND)
				: Files.newOutputStream(target)) {
			byte[] buffer = new byte[64 * 1024];
			long lastReport = 0;
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				done += read;
				lastByte[0] = System.currentTimeMillis();
				if (done - lastReport > 256 * 1024 || done == total) {
					lastReport = done;
					double seconds = (System.nanoTime() - started) / 1e9;
					progress.accept(new Transfer(asset.name(), done, total, seconds > 0.2 ? (done - startBytes) / seconds : 0, attempt));
				}
			}
		} catch (IOException e) {
			throw stalled[0] ? new IOException("no data for " + STALL_MS / 1000 + " s (at " + done + " bytes)", e) : e;
		} finally {
			watchdog.shutdownNow();
		}
		if (total > 0 && done < total) {
			throw new IOException("connection ended at " + done + " of " + total + " bytes");
		}
	}

	private static String sha256Of(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** update.log in the updates folder (kept across the installer run and restarts) plus the launcher log. */
	public static Path logFile() {
		return updatesDir().resolve("update.log");
	}

	static synchronized void log(String message) {
		LauncherLog.info("Updater: " + message);
		try {
			Files.createDirectories(updatesDir());
			Path file = logFile();
			if (Files.exists(file) && Files.size(file) > 512 * 1024) {
				Files.move(file, updatesDir().resolve("update.log.1"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(file, "[" + java.time.LocalDateTime.now().withNano(0) + "] " + message + System.lineSeparator(),
					StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// the launcher log still has it
		}
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean onPath(String binary) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir, binary))) {
				return true;
			}
		}
		return false;
	}

	/** The owner's minimum version ({@code GET /v1/update-policy}): {@code minimum} null when nothing is required. */
	public record Policy(String minimum, String message) {
		/** Whether this launcher is older than the minimum and has to update. */
		public boolean required() {
			return minimum != null && compare(AppVersion.VERSION, minimum) < 0;
		}
	}

	/** Asks the Velo server whether an update is required; never throws (no answer = nothing required). */
	public static Policy policy() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(net.veloclient.launcher.social.LauncherSocial.baseUrl() + "/v1/update-policy"))
					.timeout(Duration.ofSeconds(8)).header("User-Agent", "VeloClientLauncher/" + AppVersion.VERSION).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() == 200) {
				JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
				String min = json.has("minimum") && !json.get("minimum").isJsonNull() ? json.get("minimum").getAsString() : null;
				String message = json.has("message") && !json.get("message").isJsonNull() ? json.get("message").getAsString() : null;
				return new Policy(min, message);
			}
		} catch (Exception e) {
			LauncherLog.info("Update policy check failed: " + e);
		}
		return new Policy(null, null);
	}

	/** Compares dotted versions numerically ("0.8.10" > "0.8.9"); non-numeric parts count as 0. */
	public static int compare(String a, String b) {
		String[] pa = a.split("[.+-]");
		String[] pb = b.split("[.+-]");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int x = i < pa.length ? parse(pa[i]) : 0;
			int y = i < pb.length ? parse(pb[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static int parse(String part) {
		try {
			return Integer.parseInt(part);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
