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

	@Override
	public MediaState poll() throws Exception {
		ensureRunning();
		// No line for a while: the helper hung (or the session manager did) - start over.
		if (process != null && lineAt > 0 && System.nanoTime() - lineAt > 8_000_000_000L) {
			close();
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
		process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
				"-File", script.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
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
				line = line.trim();
				if (line.equals("none")) {
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

	private synchronized void send(String command) throws IOException {
		ensureRunning();
		if (commands != null) {
			commands.write(command + "\n");
			commands.flush();
		}
	}

	@Override
	public void playPause(MediaState on) throws Exception {
		send("toggle");
	}

	@Override
	public void next(MediaState on) throws Exception {
		send("next");
	}

	@Override
	public void previous(MediaState on) throws Exception {
		send("prev");
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
		}
		latest = null;
	}
}
