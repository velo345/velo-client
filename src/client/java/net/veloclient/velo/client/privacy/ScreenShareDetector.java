package net.veloclient.velo.client.privacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Tells whether your screen (or the Minecraft window) is being shared or recorded right now - Discord, OBS, a browser,
 * Teams, anything - checked every ~0.6 s on its own thread while {@link #watch} is on, so Anti Coords Leak kicks in
 * within about a second.
 *
 * <ul>
 * <li><b>OBS</b> (every OS): OBS writes "Recording Start/Stop" and "Streaming Start/Stop" into its log - recording or
 * streaming while OBS runs counts; an open OBS that's only previewing doesn't.</li>
 * <li><b>Linux</b>: screen sharing goes through PipeWire (the desktop portal hands the app a video stream). Any app with
 * a video input stream that's running counts - unless that stream comes from a camera. Read from {@code pw-dump}.
 * (Apps that capture X11 directly can't be seen from outside - there only "On" mode helps.)</li>
 * <li><b>Windows</b>: Windows keeps a live record of which app uses screen capture (Windows Graphics Capture):
 * {@code CapabilityAccessManager\ConsentStore\graphicsCapture*}. An entry with {@code LastUsedTimeStop} 0, for an exe
 * that's running and started that capture in this run, is capturing right now.</li>
 * </ul>
 */
public final class ScreenShareDetector {

	private static final long INTERVAL_MS = 600;
	/** The desktop itself reads window streams for live taskbar / overview thumbnails - not sharing. */
	private static final String[] DESKTOP_SHELLS = {"plasmashell", "kwin", "gnome-shell", "xdg-desktop-portal", "krunner", "spectacle"};

	private static volatile boolean sharing;
	private static volatile boolean watching;
	private static volatile String source = "";
	private static Thread thread;
	/** The check failed hard (no pw-dump / reg): stop trying every 0.6 s, retry rarely. */
	private static volatile long backoffUntil;

	private ScreenShareDetector() {
	}

	/** True while Discord is screen sharing (only meaningful while watching). */
	public static boolean sharing() {
		return watching && sharing;
	}

	/** What was seen ("Discord (PipeWire)"...), for the module's status line. */
	public static String source() {
		return source;
	}

	/** Starts / stops the background check (it costs nothing while off). */
	public static synchronized void watch(boolean on) {
		watching = on;
		if (!on) {
			sharing = false;
			return;
		}
		if (thread != null && thread.isAlive()) {
			return;
		}
		thread = new Thread(ScreenShareDetector::loop, "velo-screenshare-detect");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	private static void loop() {
		while (watching) {
			if (System.currentTimeMillis() >= backoffUntil) {
				try {
					sharing = check();
				} catch (Exception e) {
					sharing = false;
					backoffUntil = System.currentTimeMillis() + 30_000;
					VeloClient.LOGGER.debug("Screen share check failed: {}", e.toString());
				}
			}
			try {
				Thread.sleep(INTERVAL_MS);
			} catch (InterruptedException e) {
				return;
			}
		}
		sharing = false;
	}

	private static boolean check() throws IOException, InterruptedException {
		if (obsLive()) {
			source = "OBS";
			return true;
		}
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return checkWindows();
		}
		if (os.contains("linux")) {
			return checkPipeWire();
		}
		return false;
	}

	// ---- Linux ----

	private static boolean checkPipeWire() throws IOException, InterruptedException {
		String json = run(2, "pw-dump");
		if (json == null || json.isBlank()) {
			return false;
		}
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonArray()) {
			return false;
		}
		java.util.Map<Integer, JsonObject> nodeProps = new java.util.HashMap<>();
		java.util.Map<Integer, java.util.List<Integer>> sourcesOf = new java.util.HashMap<>();
		java.util.List<int[]> streams = new java.util.ArrayList<>();
		java.util.Map<Integer, String> names = new java.util.HashMap<>();
		for (JsonElement element : (JsonArray) root) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			if (!object.has("info") || !object.get("info").isJsonObject() || !object.has("id")) {
				continue;
			}
			int id = object.get("id").getAsInt();
			JsonObject info = object.getAsJsonObject("info");
			String type = string(object, "type");
			if ("PipeWire:Interface:Link".equals(type)) {
				if (info.has("input-node-id") && info.has("output-node-id")) {
					sourcesOf.computeIfAbsent(info.get("input-node-id").getAsInt(), k -> new java.util.ArrayList<>()).add(info.get("output-node-id").getAsInt());
				}
				continue;
			}
			if (!"PipeWire:Interface:Node".equals(type)) {
				continue;
			}
			JsonObject props = info.has("props") && info.get("props").isJsonObject() ? info.getAsJsonObject("props") : new JsonObject();
			nodeProps.put(id, props);
			String mediaClass = string(props, "media.class");
			// A stream an app is reading video from, frames actually moving (an idle, prepared node isn't a share).
			String who = (string(props, "application.process.binary") + " " + string(props, "application.name")).toLowerCase(Locale.ROOT);
			if (mediaClass != null && mediaClass.startsWith("Stream/Input/Video") && "running".equals(string(info, "state")) && !isDesktopShell(who)
					&& !alwaysOnClipper(who.trim())) {
				streams.add(new int[] {id});
				String app = string(props, "application.name");
				names.put(id, app != null ? app : String.valueOf(string(props, "application.process.binary")));
			}
		}
		for (int[] stream : streams) {
			boolean camera = false;
			for (int sourceId : sourcesOf.getOrDefault(stream[0], java.util.List.of())) {
				camera |= isCamera(nodeProps.get(sourceId));
			}
			if (!camera) {
				source = names.get(stream[0]) + " (PipeWire)";
				return true;
			}
		}
		return false;
	}

	/** A webcam / capture card node (v4l2 or libcamera) - not the screen. */
	private static boolean isCamera(JsonObject props) {
		if (props == null) {
			return false;
		}
		String text = (string(props, "device.api") + " " + string(props, "node.name") + " " + string(props, "media.role") + " "
				+ string(props, "api.v4l2.path") + " " + string(props, "api.libcamera.path")).toLowerCase(Locale.ROOT);
		return text.contains("v4l2") || text.contains("libcamera") || text.contains("camera");
	}

	// ---- OBS (every OS) ----

	private static java.nio.file.Path obsLog;
	private static long obsLogModified = -1;
	private static long obsLogSize = -1;
	private static boolean obsLogLive;

	/** OBS is recording or streaming: the last Start in its newest log has no Stop after it, and OBS is running. */
	private static boolean obsLive() {
		java.nio.file.Path newest = null;
		long newestTime = 0;
		for (java.nio.file.Path dir : obsLogDirs()) {
			try (var files = java.nio.file.Files.list(dir)) {
				for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
					long time = java.nio.file.Files.getLastModifiedTime(file).toMillis();
					if (file.getFileName().toString().endsWith(".txt") && time > newestTime) {
						newest = file;
						newestTime = time;
					}
				}
			} catch (IOException ignored) {
				// no OBS here
			}
		}
		if (newest == null) {
			return false;
		}
		try {
			long size = java.nio.file.Files.size(newest);
			if (!newest.equals(obsLog) || newestTime != obsLogModified || size != obsLogSize) {
				obsLog = newest;
				obsLogModified = newestTime;
				obsLogSize = size;
				obsLogLive = startedNotStopped(tail(newest, 512 * 1024));
			}
		} catch (IOException e) {
			return false;
		}
		return obsLogLive && obsRunning();
	}

	private static boolean startedNotStopped(String log) {
		return log.lastIndexOf("==== Recording Start") > log.lastIndexOf("==== Recording Stop")
				|| log.lastIndexOf("==== Streaming Start") > log.lastIndexOf("==== Streaming Stop");
	}

	private static String tail(java.nio.file.Path file, int bytes) throws IOException {
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
			long from = Math.max(0, raf.length() - bytes);
			byte[] data = new byte[(int) (raf.length() - from)];
			raf.seek(from);
			raf.readFully(data);
			return new String(data, StandardCharsets.UTF_8);
		}
	}

	private static java.util.List<java.nio.file.Path> obsLogDirs() {
		String home = System.getProperty("user.home", "");
		java.util.List<java.nio.file.Path> dirs = new java.util.ArrayList<>();
		String appData = System.getenv("APPDATA");
		if (appData != null) {
			dirs.add(java.nio.file.Path.of(appData, "obs-studio", "logs"));
		}
		dirs.add(java.nio.file.Path.of(home, ".config", "obs-studio", "logs"));
		dirs.add(java.nio.file.Path.of(home, ".var", "app", "com.obsproject.Studio", "config", "obs-studio", "logs"));
		dirs.add(java.nio.file.Path.of(home, "Library", "Application Support", "obs-studio", "logs"));
		dirs.removeIf(d -> !java.nio.file.Files.isDirectory(d));
		return dirs;
	}

	private static boolean obsRunning() {
		return ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> {
			String name = c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
			return name.equals("obs") || name.startsWith("obs64") || name.startsWith("obs32") || name.equals("obs.exe");
		}).orElse(false));
	}

	// ---- Windows ----

	private static final String CONSENT = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\";

	private static boolean checkWindows() throws IOException, InterruptedException {
		for (String store : new String[] {"graphicsCaptureProgrammatic", "graphicsCaptureWithoutBorder"}) {
			String out = run(2, "reg", "query", CONSENT + store, "/s");
			if (out == null) {
				continue;
			}
			// Blocks of: key line, then "    LastUsedTimeStart    REG_QWORD    0x..." / "LastUsedTimeStop ..." values.
			String key = null;
			long start = -1;
			long lastStop = -1;
			for (String line : out.split("\r?\n")) {
				if (line.startsWith("HKEY_")) {
					if (key != null && activeCapture(key, start, lastStop)) {
						source = appOf(key) + " (Windows capture)";
						return true;
					}
					key = line.toLowerCase(Locale.ROOT);
					start = -1;
					lastStop = -1;
				} else if (line.contains("LastUsedTimeStart")) {
					start = qword(line);
				} else if (line.contains("LastUsedTimeStop")) {
					lastStop = qword(line);
				}
			}
			if (key != null && activeCapture(key, start, lastStop)) {
				source = appOf(key) + " (Windows capture)";
				return true;
			}
		}
		return false;
	}

	/**
	 * Background clip recorders (Medal, Outplayed, Insights...) capture all the time as an instant-replay buffer, like
	 * Velo's own Clips - that's not sharing your screen, so they don't count.
	 */
	private static final String[] ALWAYS_ON_CLIPPERS = {"medal", "outplayed", "insights", "overwolf"};

	private static boolean alwaysOnClipper(String app) {
		String name = app.toLowerCase(Locale.ROOT);
		for (String clipper : ALWAYS_ON_CLIPPERS) {
			if (name.startsWith(clipper)) {
				return true;
			}
		}
		return false;
	}

	/** The exe's file name from a consent-store key, for the status line. */
	private static String appOf(String key) {
		String exe = key.substring(key.lastIndexOf('#') + 1);
		return exe.endsWith(".exe") ? exe.substring(0, exe.length() - 4) : exe;
	}

	/**
	 * An entry still capturing - from the very exe that's running now, started while it runs. A capture that ended with a
	 * crash or an update keeps LastUsedTimeStop at 0 for good (and Discord moves to a new app-1.0.x folder with every
	 * update), so "the app is running" alone read such leftovers as "sharing" forever. OBS is judged by its log instead:
	 * its preview captures all the time without recording anything.
	 */
	private static boolean activeCapture(String key, long start, long stop) {
		if (start <= 0 || stop != 0 || key.endsWith("#obs64.exe") || key.endsWith("#obs32.exe") || alwaysOnClipper(appOf(key))) {
			return false;
		}
		// The key's last part is the exe path with '#' for '\' (NonPackaged#C:#Users#...#Discord.exe).
		String exe = key.substring(key.lastIndexOf('\\') + 1).replace('#', '\\');
		long startedMillis = start / 10_000L - 11_644_473_600_000L; // FILETIME (100 ns since 1601) -> epoch ms
		return ProcessHandle.allProcesses().anyMatch(p -> {
			String command = p.info().command().map(c -> c.toLowerCase(Locale.ROOT)).orElse("");
			if (command.isEmpty() || !command.endsWith(exe) && !exe.endsWith(command)) {
				return false;
			}
			// A capture that began before this process started belongs to an earlier run.
			return p.info().startInstant().map(i -> startedMillis >= i.toEpochMilli() - 5_000).orElse(true);
		});
	}

	private static long qword(String line) {
		int hex = line.lastIndexOf("0x");
		if (hex < 0) {
			return -1;
		}
		try {
			return Long.parseUnsignedLong(line.substring(hex + 2).trim(), 16);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// ---- helpers ----

	private static boolean isDesktopShell(String text) {
		for (String name : DESKTOP_SHELLS) {
			if (text.contains(name)) {
				return true;
			}
		}
		return false;
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	/** Runs a command and returns its output, or null if it isn't there / timed out. */
	private static String run(int timeoutSeconds, String... command) throws IOException, InterruptedException {
		Process process;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			throw new IOException(command[0] + " isn't available", e);
		}
		byte[] bytes;
		try (InputStream in = process.getInputStream()) {
			bytes = in.readAllBytes();
		}
		if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return null;
		}
		return process.exitValue() == 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
	}
}
