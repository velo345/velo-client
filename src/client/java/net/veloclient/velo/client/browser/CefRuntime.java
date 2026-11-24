package net.veloclient.velo.client.browser;

import net.veloclient.velo.config.VeloPaths;

import java.io.BufferedInputStream;
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
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Chromium for the Velo Browser: CinemaMod's java-cef build (CEF with the H.264 / AAC codecs, so TikTok, Twitch and
 * Instagram videos play), downloaded the first time the browser opens (~120 MB, checked against the published SHA-256),
 * started once per game and pumped every frame.
 *
 * <p>Private by design: no cache folder is given, so Chromium keeps cookies, history, logins and the page cache in
 * memory only - closing the game forgets all of it. The only thing on disk is Chromium itself (and the Widevine
 * module it fetches for Spotify), never anything about what you visited. The Java classes in the Velo jar
 * (libs/jcef-cinemamod-*.jar) must be from the same commit as these natives.
 */
public final class CefRuntime {

	/** CinemaMod java-cef commit - the same as libs/jcef-cinemamod-eaeb3d4.jar. */
	static final String COMMIT = "eaeb3d4370aa3526ee237ad1981ad59af3de4dd1";
	private static final String HOST = "https://mcef-download.cinemamod.com/java-cef-builds/";

	public enum State { NOT_INSTALLED, DOWNLOADING, STARTING, READY, FAILED }

	private static volatile State state;
	private static volatile String error;
	private static volatile String errorDetail;
	private static volatile double progress;
	private static volatile long downloadedBytes;
	private static volatile long totalBytes;
	private static Object app;
	private static long lastPump;

	private CefRuntime() {
	}

	public static State state() {
		if (state == null) {
			state = State.NOT_INSTALLED;
		}
		return state;
	}

	public static boolean installed() {
		return Files.isRegularFile(platformDir().resolve(".velo-ok"));
	}

	public static String error() {
		return error;
	}

	/** The technical side of the last failure (step, exception, message) - shown small under the error. */
	public static String errorDetail() {
		return errorDetail;
	}

	/** 0..1 while downloading. */
	public static double progress() {
		return progress;
	}

	public static String progressText() {
		return totalBytes <= 0 ? "" : (downloadedBytes / 1_048_576) + " / " + (totalBytes / 1_048_576) + " MB";
	}

	/** windows_amd64, linux_arm64, macos_arm64... - CinemaMod's platform names. */
	static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "amd64";
		if (os.contains("win")) {
			return "windows_" + cpu;
		}
		if (os.contains("mac")) {
			return "macos_" + cpu;
		}
		return "linux_" + cpu;
	}

	static Path baseDir() {
		return VeloPaths.root().resolve("browser").resolve(COMMIT.substring(0, 12));
	}

	static Path platformDir() {
		return baseDir().resolve(platform());
	}

	/** Makes sure Chromium is there and running; safe to call every frame. Returns true once it's ready. */
	public static synchronized boolean ensure() {
		State s = state();
		if (s == State.READY) {
			return true;
		}
		if (s == State.DOWNLOADING || s == State.STARTING || s == State.FAILED) {
			return false;
		}
		if (installed()) {
			start();
			return state == State.READY;
		}
		state = State.DOWNLOADING;
		Thread t = new Thread(CefRuntime::download, "velo-browser-download");
		t.setDaemon(true);
		t.start();
		return false;
	}

	/** After a failure: try again (the player pressed Retry). */
	public static synchronized void retry() {
		if (state == State.FAILED) {
			state = null;
			error = null;
			errorDetail = null;
			progress = 0;
			downloadedBytes = 0;
			totalBytes = 0;
		}
	}

	// ------------------------------------------------------------------ download

	/** A failure we already put into words for the player (the step it happened in goes into the detail line). */
	private static final class SetupException extends IOException {
		SetupException(String message) {
			super(message);
		}
	}

	private static void download() {
		// a retry starts from zero (the old counters made the bar start full and run past the end)
		downloadedBytes = 0;
		totalBytes = 0;
		progress = 0;
		String step = "checksum";
		Path archive = null;
		try {
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
			String url = HOST + COMMIT + "/" + platform() + ".tar.gz";
			HttpResponse<String> shaResponse = http.send(HttpRequest.newBuilder(URI.create(url + ".sha256")).timeout(Duration.ofSeconds(20)).build(),
					HttpResponse.BodyHandlers.ofString());
			if (shaResponse.statusCode() != 200) {
				throw new SetupException("The download server answered HTTP " + shaResponse.statusCode() + " - try again later");
			}
			// Linux/macOS publish "<hash>  <file>", Windows a PowerShell Get-FileHash table ("Algorithm Hash Path ...") -
			// taking the first word made every Windows download "damaged". The hash is the only 64-hex-digit word.
			var hex = java.util.regex.Pattern.compile("\\b[0-9a-fA-F]{64}\\b").matcher(shaResponse.body());
			if (!hex.find()) {
				throw new SetupException("The download server sent no checksum - try again later");
			}
			String expected = hex.group();

			step = "download";
			Files.createDirectories(baseDir());
			archive = baseDir().resolve(platform() + ".tar.gz");
			HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new SetupException("The download server answered HTTP " + response.statusCode() + " - try again later");
			}
			totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1);
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			try (InputStream in = response.body(); var out = Files.newOutputStream(archive)) {
				byte[] buffer = new byte[1 << 16];
				int n;
				while ((n = in.read(buffer)) > 0) {
					out.write(buffer, 0, n);
					sha.update(buffer, 0, n);
					downloadedBytes += n;
					progress = totalBytes > 0 ? Math.min(1, downloadedBytes / (double) totalBytes) : 0;
				}
			}
			if (totalBytes > 0 && downloadedBytes != totalBytes) {
				throw new SetupException("The connection dropped after " + mb(downloadedBytes) + " of " + mb(totalBytes) + " MB - try again");
			}
			String actual = HexFormat.of().formatHex(sha.digest());
			if (!actual.equalsIgnoreCase(expected)) {
				throw new SetupException("The file arrived changed (checksum differs) - an antivirus, proxy or VPN may be altering downloads");
			}

			step = "unpack";
			extract(archive, baseDir());
			Files.deleteIfExists(archive);
			makeExecutable();
			Files.writeString(platformDir().resolve(".velo-ok"), COMMIT, StandardCharsets.UTF_8);
			synchronized (CefRuntime.class) {
				state = State.NOT_INSTALLED; // installed: the next ensure() starts it on the render thread
			}
		} catch (Exception e) {
			if (archive != null) {
				try {
					Files.deleteIfExists(archive);
				} catch (IOException ignored) {
					// locked (antivirus scanning it) - the next try overwrites it
				}
			}
			errorDetail = step + " step: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage());
			fail(explain(e, step), e);
		}
	}

	private static long mb(long bytes) {
		return bytes / 1_048_576;
	}

	/** What went wrong, in words a player can act on. */
	private static String explain(Throwable e, String step) {
		if (e instanceof SetupException) {
			return e.getMessage();
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			String msg = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
			if (t instanceof java.net.UnknownHostException) {
				return "No connection to the download server - check your internet, VPN or DNS";
			}
			if (t instanceof java.net.http.HttpConnectTimeoutException || t instanceof java.net.ConnectException) {
				return "The download server can't be reached - a firewall, VPN or antivirus may be blocking it";
			}
			if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
				return "The download timed out - your connection may be too slow or unstable, try again";
			}
			if (t instanceof javax.net.ssl.SSLException) {
				return "The secure connection failed - an antivirus or proxy is intercepting HTTPS";
			}
			if (t instanceof java.nio.file.AccessDeniedException || msg.contains("access is denied") || msg.contains("being used by another process")) {
				return "Velo isn't allowed to write the browser files - an antivirus may be blocking them, or another game is using them";
			}
			if (msg.contains("not enough space") || msg.contains("no space left") || msg.contains("disk full")) {
				return "Not enough disk space - the browser needs about 400 MB";
			}
			if (t instanceof java.util.zip.ZipException || t instanceof java.io.EOFException) {
				return "The download arrived broken - try again";
			}
		}
		return switch (step) {
			case "unpack" -> "Couldn't unpack the browser - try again (antivirus?)";
			case "checksum" -> "Couldn't reach the download server - try again";
			default -> "The download failed - try again";
		};
	}

	/** A plain tar reader (the archive is a gzipped ustar/GNU tar) - no library needed. */
	private static void extract(Path archive, Path into) throws IOException {
		try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive), 1 << 16))) {
			byte[] header = new byte[512];
			String longName = null;
			while (true) {
				if (in.readNBytes(header, 0, 512) < 512) {
					return;
				}
				boolean empty = true;
				for (byte b : header) {
					if (b != 0) {
						empty = false;
						break;
					}
				}
				if (empty) {
					return;
				}
				String name = longName != null ? longName : string(header, 0, 100);
				longName = null;
				String prefix = string(header, 345, 155);
				if (!prefix.isEmpty() && name.equals(string(header, 0, 100))) {
					name = prefix + "/" + name;
				}
				long size = Long.parseLong(string(header, 124, 12).trim().isEmpty() ? "0" : string(header, 124, 12).trim(), 8);
				char type = (char) header[156];
				int mode = (int) Long.parseLong(string(header, 100, 8).trim().isEmpty() ? "644" : string(header, 100, 8).trim(), 8);
				if (type == 'L') {
					byte[] data = in.readNBytes((int) size);
					longName = new String(data, StandardCharsets.UTF_8).replace("\0", "");
					skipPadding(in, size);
					continue;
				}
				Path out = into.resolve(name).normalize();
				if (!out.startsWith(into)) {
					throw new IOException("Bad path in the browser download: " + name);
				}
				if (type == '5') {
					Files.createDirectories(out);
				} else if (type == '2') {
					String target = string(header, 157, 100);
					Files.createDirectories(out.getParent());
					Files.deleteIfExists(out);
					try {
						Files.createSymbolicLink(out, Path.of(target));
					} catch (UnsupportedOperationException | IOException e) {
						// no symlinks here (Windows without the right): the macOS framework works without them
					}
				} else if (type == '0' || type == 0) {
					Files.createDirectories(out.getParent());
					try (var o = Files.newOutputStream(out)) {
						long left = size;
						byte[] buffer = new byte[1 << 16];
						while (left > 0) {
							int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
							if (n < 0) {
								throw new IOException("Browser download ended early");
							}
							o.write(buffer, 0, n);
							left -= n;
						}
					}
					if ((mode & 0100) != 0) {
						out.toFile().setExecutable(true, false);
					}
					skipPadding(in, size);
					continue;
				}
				in.skipNBytes(size);
				skipPadding(in, size);
			}
		}
	}

	private static void skipPadding(InputStream in, long size) throws IOException {
		long pad = (512 - size % 512) % 512;
		in.skipNBytes(pad);
	}

	private static String string(byte[] b, int off, int len) {
		int end = off;
		while (end < off + len && b[end] != 0) {
			end++;
		}
		return new String(b, off, end - off, StandardCharsets.UTF_8);
	}

	/** The helper processes must be executable (tar modes don't always survive). */
	private static void makeExecutable() throws IOException {
		try (var walk = Files.walk(platformDir())) {
			for (Path p : walk.toList()) {
				String name = p.getFileName().toString();
				if (Files.isRegularFile(p) && (name.equals("jcef_helper") || name.startsWith("jcef Helper") || name.equals("chrome-sandbox"))) {
					p.toFile().setExecutable(true, false);
				}
			}
		}
	}

	// ------------------------------------------------------------------ start / pump / stop

	private static void start() {
		state = State.STARTING;
		try {
			killOrphanHelpers();
			if (!hookAdded) {
				hookAdded = true;
				// a game that exits without CLIENT_STOPPING (System.exit, crash handler) still takes Chromium's helpers along
				Runtime.getRuntime().addShutdownHook(new Thread(CefRuntime::killOwnHelpers, "velo-browser-cleanup"));
			}
			System.setProperty("jcef.path", platformDir().toString());
			String[] switches = {
					"--autoplay-policy=no-user-gesture-required",
					"--enable-widevine-cdm",
					"--disable-gpu-shader-disk-cache",
					"--component-updater=fast-update",
					"--lang=" + Locale.getDefault().toLanguageTag()};
			if (!org.cef.CefApp.startup(switches)) {
				throw new IllegalStateException("Chromium didn't start");
			}
			org.cef.CefSettings settings = new org.cef.CefSettings();
			settings.windowless_rendering_enabled = true;
			// Normal browsing keeps its data like Chrome - cookies, sign-ins, history, cache - only on this PC
			// (<velo root>/browser/profile, nothing is ever sent anywhere). Incognito windows use their own in-memory
			// context instead (BrowserView). A second game running at the same time can't open the same profile:
			// it browses in memory only.
			Path profile = profileDir();
			boolean persistent = lockProfile(profile);
			settings.cache_path = persistent ? profile.toString() : null;
			settings.persist_session_cookies = persistent;
			net.veloclient.velo.VeloClient.LOGGER.info("Velo Browser: {}", persistent ? "saving data locally in " + profile
					: "browsing in memory only (the profile is in use by another game)");
			settings.background_color = settings.new ColorType(255, 18, 16, 20);
			settings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
			app = org.cef.CefApp.getInstance(switches, settings);
			state = State.READY;
			net.veloclient.velo.VeloClient.LOGGER.info("Velo Browser: Chromium {} started ({})", COMMIT.substring(0, 7), platform());
		} catch (Throwable t) {
			fail("The browser couldn't start: " + t.getMessage(), t);
		}
	}

	/** Where normal (not incognito) browsing keeps its data. */
	static Path profileDir() {
		return net.veloclient.velo.config.VeloPaths.root().resolve("browser").resolve("profile");
	}

	private static java.nio.channels.FileChannel profileLockChannel;

	/** One game at a time per profile; also wipes it first when "Clear browsing data" asked for that. */
	private static boolean lockProfile(Path profile) {
		try {
			Files.createDirectories(profile);
			Path clear = profile.resolveSibling("clear-profile");
			if (Files.exists(clear)) {
				try (var walk = Files.walk(profile)) {
					walk.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(profile)).forEach(p -> p.toFile().delete());
				}
				Files.deleteIfExists(clear);
				net.veloclient.velo.VeloClient.LOGGER.info("Velo Browser: browsing data cleared");
			}
			profileLockChannel = java.nio.channels.FileChannel.open(profile.resolveSibling("profile.lock"),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
			return profileLockChannel.tryLock() != null;
		} catch (Exception e) {
			return false;
		}
	}

	/** Settings: clears cookies, sign-ins, history and cache the next time the browser starts (Chromium holds the files now). */
	public static void clearDataOnNextStart() {
		try {
			Files.createDirectories(profileDir());
			Files.writeString(profileDir().resolveSibling("clear-profile"), "1");
		} catch (Exception ignored) {
			// nothing to clear
		}
	}

	static org.cef.CefApp app() {
		return (org.cef.CefApp) app;
	}

	/** Lets Chromium do its work; called every frame (render thread) and every tick. */
	public static void pump() {
		if (state != State.READY || app == null) {
			return;
		}
		long now = System.nanoTime();
		// on screen ~250 Hz (smooth video), hidden but playing 50 Hz (sound), nothing open 10 Hz (housekeeping)
		long every = !BrowserView.anyOpen() ? 100_000_000L : BrowserView.anySeen() ? 4_000_000L : 20_000_000L;
		if (now - lastPump < every) {
			return;
		}
		lastPump = now;
		try {
			((org.cef.CefApp) app).N_DoMessageLoopWork();
		} catch (Throwable t) {
			fail("The browser stopped: " + t.getMessage(), t);
		}
	}

	/** Game closing: closes every page and Chromium (else its helper processes linger). */
	public static void shutdown() {
		if (app == null) {
			return;
		}
		try {
			BrowserView.closeAll();
			for (int i = 0; i < 40; i++) {
				((org.cef.CefApp) app).N_DoMessageLoopWork();
				Thread.sleep(5);
			}
			((org.cef.CefApp) app).dispose();
		} catch (Throwable ignored) {
			// closing anyway
		}
		app = null;
		state = State.NOT_INSTALLED;
		killOwnHelpers();
	}

	private static boolean hookAdded;

	/** Chromium's helper processes (GPU, network, page renderers) are all {@code jcef_helper} from our browser folder. */
	private static boolean isHelper(ProcessHandle p) {
		String cmd = p.info().command().orElse("");
		String name = Path.of(cmd.isEmpty() ? "x" : cmd).getFileName().toString().toLowerCase(Locale.ROOT);
		return name.startsWith("jcef_helper") || name.startsWith("jcef helper");
	}

	/** Ends every helper this game started (after Chromium closed - or when it never got the chance to). */
	static void killOwnHelpers() {
		try {
			var helpers = ProcessHandle.current().descendants().filter(CefRuntime::isHelper).toList();
			helpers.forEach(ProcessHandle::destroy);
			long until = System.currentTimeMillis() + 1500;
			for (ProcessHandle h : helpers) {
				while (h.isAlive() && System.currentTimeMillis() < until) {
					Thread.sleep(20);
				}
				if (h.isAlive()) {
					h.destroyForcibly();
				}
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	/**
	 * Helpers left behind by a game that crashed or was killed (no clean shutdown): from our browser folder, with no live
	 * Java process above them. A second game that is running right now keeps its own.
	 */
	private static void killOrphanHelpers() {
		try {
			String dir = baseDir().toAbsolutePath().toString();
			ProcessHandle.allProcesses().filter(CefRuntime::isHelper)
					.filter(p -> p.info().command().orElse("").startsWith(dir))
					.filter(p -> !hasLiveJavaParent(p))
					.forEach(p -> {
						p.destroy();
						net.veloclient.velo.VeloClient.LOGGER.info("Velo Browser: ended a helper left over from an earlier game (pid {})", p.pid());
					});
		} catch (Throwable ignored) {
			// best effort
		}
	}

	private static boolean hasLiveJavaParent(ProcessHandle p) {
		var parent = p.parent();
		for (int i = 0; i < 8 && parent.isPresent(); i++) {
			ProcessHandle h = parent.get();
			String cmd = h.info().command().orElse("").toLowerCase(Locale.ROOT);
			if (cmd.contains("java")) {
				return h.isAlive();
			}
			if (isHelper(h)) {
				parent = h.parent();
				continue;
			}
			return false;
		}
		return false;
	}

	private static void fail(String message, Throwable t) {
		if (errorDetail == null && t != null) {
			errorDetail = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " - " + t.getMessage());
		}
		error = message;
		state = State.FAILED;
		net.veloclient.velo.VeloClient.LOGGER.warn("Velo Browser: {}", message, t);
	}
}
