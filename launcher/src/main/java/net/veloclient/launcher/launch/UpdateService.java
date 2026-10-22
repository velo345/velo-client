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
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() != 200) {
			throw new IOException("GitHub answered " + response.statusCode());
		}
		// Releases are published as pre-releases, so /releases/latest doesn't see them: take the
		// highest version among the recent non-draft releases instead.
		JsonObject json = null;
		for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
			JsonObject candidate = element.getAsJsonObject();
			if (candidate.get("draft").getAsBoolean()) {
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
		for (Asset asset : assets) {
			if (os.contains("win") && asset.name().endsWith(".exe")
					|| !os.contains("win") && !os.contains("mac") && asset.name().endsWith(".deb")) {
				installer = asset;
			}
		}

		Map<String, Asset> mods = modJars(assets, version);
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
		return release.get("tag_name").getAsString().replaceFirst("^v", "");
	}

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
	public static boolean runInstaller(Release release, Consumer<String> status) throws IOException, InterruptedException {
		return runInstaller(release, status, f -> { });
	}

	public static boolean runInstaller(Release release, Consumer<String> status, java.util.function.DoubleConsumer progress)
			throws IOException, InterruptedException {
		Asset installer = release.installer();
		if (installer == null) {
			return false;
		}
		Files.createDirectories(updatesDir());
		Path file = updatesDir().resolve(installer.name());
		if (!Files.exists(file) || Files.size(file) != installer.size()) {
			status.accept("Downloading " + installer.name());
			Path tmp = updatesDir().resolve(installer.name() + ".part");
			download(installer, tmp, progress);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		progress.accept(-1);
		status.accept("Installing - confirm the password prompt");
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			new ProcessBuilder(file.toString()).start();
			return true;
		}
		// Linux: a graphical sudo prompt installs the .deb; without pkexec hand it to the package app.
		if (onPath("pkexec") && (onPath("apt") || onPath("dpkg"))) {
			List<String> command = onPath("apt") ? List.of("pkexec", "apt", "install", "-y", "--reinstall", file.toString())
					: List.of("pkexec", "dpkg", "-i", file.toString());
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor() != 0) {
				LauncherLog.warn("Installer exited with " + process.exitValue() + ": " + output, null);
				throw new IOException("The update couldn't be installed (was the password prompt cancelled?)");
			}
			return true;
		}
		new ProcessBuilder("xdg-open", file.toString()).start();
		return true;
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

	private static void download(Asset asset, Path target, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url())).timeout(Duration.ofMinutes(10))
				.header("User-Agent", "VeloClientLauncher/" + AppVersion.VERSION).GET().build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IOException("Download of " + asset.name() + " failed (" + response.statusCode() + ")");
		}
		long total = response.headers().firstValueAsLong("Content-Length").orElse(asset.size());
		try (InputStream in = response.body(); var out = Files.newOutputStream(target)) {
			byte[] buffer = new byte[64 * 1024];
			long done = 0;
			long lastReport = 0;
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				done += read;
				if (done - lastReport > 256 * 1024 || done == total) {
					lastReport = done;
					progress.accept(total > 0 ? Math.min(1.0, done / (double) total) : -1);
				}
			}
		}
		if (asset.sha256() != null && !asset.sha256().equalsIgnoreCase(sha256(Files.readAllBytes(target)))) {
			Files.deleteIfExists(target);
			throw new IOException("Download of " + asset.name() + " was corrupted - try again");
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
