package net.veloclient.velo.client.nowplaying;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Windows: one long-lived PowerShell helper ({@code assets/velo-client/nowplaying/smtc.ps1}) that reads the system media
 * session (GlobalSystemMediaTransportControls - what the Windows media flyout shows) and streams it as JSON lines, and
 * takes commands on stdin. Starting PowerShell costs ~0.5 s, so it's started once, not per poll.
 */
final class WindowsSmtc implements MediaBackend {

	private Process process;
	private Writer commands;
	private volatile MediaState latest;
	private volatile long lineAt;
	private long startedAt;
	/** The helper said this Windows has no media session API: controls fall back to the keyboard's media keys. */
	private volatile boolean unsupported;
	/** The helper printed "ready" (the media session API works); until then commands use the media keys. */
	private volatile boolean ready;
	/** When the first helper of this session was started (System.nanoTime), 0 before. */
	private long firstStart;

	@Override
	public MediaState poll() throws Exception {
		ensureRunning();
		// No line for a while: the helper hung (or the session manager did) - start over.
		if (process != null && !unsupported && lineAt > 0 && System.nanoTime() - lineAt > 8_000_000_000L) {
			close();
		}
		boolean silent = !ready && firstStart != 0 && System.nanoTime() - firstStart > 6_000_000_000L;
		if (unsupported || silent) {
			// No session to read (API missing, or the helper never got going: blocked PowerShell, AV, crash loop) - still offer play/pause and skip, sent as media key presses.
			return new MediaState("windows", "Media keys", "Play, pause and skip whatever is playing", null, null, 0, 0, System.nanoTime(), true, -1,
					true, true, false);
		}
		return latest;
	}

	private synchronized void ensureRunning() throws IOException {
		if (process != null && process.isAlive()) {
			return;
		}
		long now = System.nanoTime();
		if (startedAt != 0 && now - startedAt < 10_000_000_000L) {
			return;
		}
		startedAt = now;
		if (firstStart == 0) {
			firstStart = now;
		}
		Path script = Files.createTempFile("velo-nowplaying", ".ps1");
		script.toFile().deleteOnExit();
		try (InputStream in = WindowsSmtc.class.getResourceAsStream("/assets/velo-client/nowplaying/smtc.ps1")) {
			if (in == null) {
				throw new IOException("smtc.ps1 missing from the jar");
			}
			// A BOM so Windows PowerShell 5.1 reads the script as UTF-8.
			byte[] body = in.readAllBytes();
			byte[] withBom = new byte[body.length + 3];
			withBom[0] = (byte) 0xEF;
			withBom[1] = (byte) 0xBB;
			withBom[2] = (byte) 0xBF;
			System.arraycopy(body, 0, withBom, 3, body.length);
			Files.write(script, withBom);
		}
		// The helper's errors go to logs/velo-nowplaying.log, so a machine where it can't read the media session says why.
		Path log = Path.of("logs", "velo-nowplaying.log");
		try {
			Files.createDirectories(log.getParent());
		} catch (IOException ignored) {
			log = null;
		}
		ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
				"-File", script.toString());
		builder.redirectError(log == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(log.toFile()));
		process = builder.start();
		OutputStream stdin = process.getOutputStream();
		commands = new java.io.OutputStreamWriter(stdin, StandardCharsets.UTF_8);
		lineAt = 0;
		Process mine = process;
		Thread reader = new Thread(() -> read(mine), "Velo Now Playing (Windows)");
		reader.setDaemon(true);
		reader.start();
	}

	private void read(Process mine) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(mine.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				lineAt = System.nanoTime();
				line = line.replace("\uFEFF", "").trim();
				if (line.equals("unsupported")) {
					unsupported = true;
					latest = null;
				} else if (line.equals("ready")) {
					unsupported = false;
					ready = true;
				} else if (line.equals("none")) {
					latest = null;
				} else if (line.startsWith("{")) {
					latest = parse(JsonParser.parseString(line).getAsJsonObject());
				}
			}
		} catch (Exception ignored) {
			// The helper went away; poll() starts a new one.
		}
		if (mine == process) {
			latest = null;
		}
	}

	private static MediaState parse(JsonObject o) {
		double volume = num(o, "volume", -1);
		return new MediaState(str(o, "player"), str(o, "title"), str(o, "artist"), str(o, "album"), str(o, "art"), (long) num(o, "length", 0),
				(long) num(o, "position", 0), System.nanoTime(), bool(o, "playing"), volume < 0 ? -1 : Math.min(1, volume), bool(o, "next"),
				bool(o, "prev"), bool(o, "seek"));
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		String s = e.getAsString();
		return s.isBlank() ? null : s;
	}

	private static double num(JsonObject o, String key, double fallback) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
	}

	private static boolean bool(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	private synchronized boolean send(String command) throws IOException {
		ensureRunning();
		if (commands != null && process != null && process.isAlive() && ready && !unsupported) {
			try {
				commands.write(command + "\n");
				commands.flush();
				return true;
			} catch (IOException e) {
				// helper died between the check and the write: fall through to the media keys
			}
		}
		return false;
	}

	@Override
	public void playPause(MediaState on) throws Exception {
		if (!send("toggle")) {
			MediaKeys.press(MediaKeys.PLAY_PAUSE);
		}
	}

	@Override
	public void next(MediaState on) throws Exception {
		if (!send("next")) {
			MediaKeys.press(MediaKeys.NEXT);
		}
	}

	@Override
	public void previous(MediaState on) throws Exception {
		if (!send("prev")) {
			MediaKeys.press(MediaKeys.PREVIOUS);
		}
	}

	@Override
	public void setVolume(MediaState on, double volume) throws Exception {
		send(String.format(Locale.ROOT, "vol %.3f", volume));
	}

	@Override
	public void seek(MediaState on, long positionMs) throws Exception {
		send("seek " + positionMs);
	}

	@Override
	public synchronized void close() {
		if (process != null) {
			try {
				// Closing stdin ends the helper's loop cleanly; destroy covers a hung one.
				commands.close();
			} catch (IOException ignored) {
				// Already gone.
			}
			process.destroy();
			process = null;
			commands = null;
			startedAt = 0;
			unsupported = false;
		}
		latest = null;
	}
}
