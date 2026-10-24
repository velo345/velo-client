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
 * Tells whether Discord is sharing your screen (or the Minecraft window) right now, checked every ~0.6 s on its own
 * thread while {@link #watch} is on - so Anti Coords Leak kicks in within about a second of going live.
 *
 * <ul>
 * <li><b>Linux</b>: screen sharing goes through PipeWire (the desktop portal hands Discord a video stream). A Discord
 * client (Discord, Canary/PTB, Vesktop, Equibop, WebCord, Legcord/ArmCord) with a video input stream that isn't
 * suspended means it's capturing. Read from {@code pw-dump}. (Discord on X11 without PipeWire captures directly and
 * can't be seen from outside - there only "On" mode helps.)</li>
 * <li><b>Windows</b>: Windows keeps a live record of which app uses screen capture (Windows Graphics Capture, what
 * Discord uses for screens and windows): {@code CapabilityAccessManager\ConsentStore\graphicsCapture*}. An entry for a
 * Discord exe whose {@code LastUsedTimeStop} is 0 is capturing right now.</li>
 * </ul>
 */
public final class ScreenShareDetector {

	private static final long INTERVAL_MS = 600;
	private static final String[] DISCORD_NAMES = {"discord", "vesktop", "equibop", "webcord", "legcord", "armcord"};

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
		for (JsonElement element : (JsonArray) root) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			if (!"PipeWire:Interface:Node".equals(string(object, "type")) || !object.has("info") || !object.get("info").isJsonObject()) {
				continue;
			}
			JsonObject info = object.getAsJsonObject("info");
			JsonObject props = info.has("props") && info.get("props").isJsonObject() ? info.getAsJsonObject("props") : new JsonObject();
			String mediaClass = string(props, "media.class");
			if (mediaClass == null || !mediaClass.startsWith("Stream/Input/Video")) {
				continue;
			}
			String who = (string(props, "application.process.binary") + " " + string(props, "application.name") + " "
					+ string(props, "node.name")).toLowerCase(Locale.ROOT);
			String state = string(info, "state");
			if (isDiscord(who) && !"suspended".equals(state) && !"error".equals(state)) {
				source = "Discord (PipeWire)";
				return true;
			}
		}
		return false;
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
						source = "Discord (Windows capture)";
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
				source = "Discord (Windows capture)";
				return true;
			}
		}
		return false;
	}

	private static boolean activeCapture(String key, long start, long stop) {
		return isDiscord(key) && start > 0 && stop == 0;
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

	private static boolean isDiscord(String text) {
		for (String name : DISCORD_NAMES) {
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
