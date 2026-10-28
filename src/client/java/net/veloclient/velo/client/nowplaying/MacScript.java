package net.veloclient.velo.client.nowplaying;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * macOS: AppleScript to Spotify or Apple Music (the system-wide "Now Playing" API is private and closed to other apps
 * since macOS 15.4). Each poll asks Spotify first, then Music, and only if that app is already running - a
 * {@code tell} to an app that isn't running would launch it.
 */
final class MacScript implements MediaBackend {

	private static final String SEP = "\u001F";

	private static String query(String app) {
		String art = app.equals("Spotify") ? "artwork url of current track" : "\"\"";
		return "if application \"" + app + "\" is running then\n"
				+ "tell application \"" + app + "\"\n"
				+ "if player state is stopped then return \"\"\n"
				+ "set s to (ASCII character 31)\n"
				+ "return (name of current track) & s & (artist of current track) & s & (album of current track) & s & " + art + " & s & "
				+ "(duration of current track) & s & (player position) & s & (player state as string) & s & (sound volume)\n"
				+ "end tell\n"
				+ "end if\n"
				+ "return \"\"";
	}

	@Override
	public MediaState poll() throws Exception {
		for (String app : new String[] {"Spotify", "Music"}) {
			String out = osascript(query(app)).trim();
			if (out.isEmpty()) {
				continue;
			}
			String[] f = out.split(SEP, -1);
			if (f.length < 8) {
				continue;
			}
			// Spotify gives the duration in ms, Music in seconds; both give the position in seconds.
			double duration = parse(f[4]);
			long lengthMs = (long) (app.equals("Spotify") ? duration : duration * 1000);
			return new MediaState(app, f[0], blank(f[1]), blank(f[2]), blank(f[3]), lengthMs, (long) (parse(f[5]) * 1000), System.nanoTime(),
					f[6].trim().toLowerCase(Locale.ROOT).equals("playing"), parse(f[7]) / 100.0, true, true, true);
		}
		return null;
	}

	private static String blank(String s) {
		return s == null || s.isBlank() || s.equals("missing value") ? null : s;
	}

	private static double parse(String s) {
		try {
			return Double.parseDouble(s.trim().replace(',', '.'));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static void tell(MediaState on, String what) throws Exception {
		osascript("tell application \"" + on.player() + "\" to " + what);
	}

	@Override
	public void playPause(MediaState on) throws Exception {
		tell(on, "playpause");
	}

	@Override
	public void next(MediaState on) throws Exception {
		tell(on, "next track");
	}

	@Override
	public void previous(MediaState on) throws Exception {
		tell(on, "previous track");
	}

	@Override
	public void setVolume(MediaState on, double volume) throws Exception {
		tell(on, "set sound volume to " + Math.round(volume * 100));
	}

	@Override
	public void seek(MediaState on, long positionMs) throws Exception {
		tell(on, String.format(Locale.ROOT, "set player position to %.2f", positionMs / 1000.0));
	}

	private static String osascript(String script) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("osascript", "-e", script).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		byte[] out = process.getInputStream().readAllBytes();
		if (!process.waitFor(3, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("osascript timed out");
		}
		return new String(out, StandardCharsets.UTF_8);
	}
}
