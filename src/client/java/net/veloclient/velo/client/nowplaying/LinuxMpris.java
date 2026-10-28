package net.veloclient.velo.client.nowplaying;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linux: MPRIS over the session D-Bus, through {@code gdbus} (part of GLib, there on GNOME and KDE alike) so we don't
 * ship a D-Bus library. Spotify, Firefox/Chrome tabs, VLC, mpv (with mpris), KDE Connect... all show up here.
 */
final class LinuxMpris implements MediaBackend {

	private static final String PREFIX = "org.mpris.MediaPlayer2.";
	private static final String PATH = "/org/mpris/MediaPlayer2";
	private static final Pattern NAME = Pattern.compile("'(org\\.mpris\\.MediaPlayer2\\.[^']+)'");

	private List<String> names = List.of();
	private long namesAt;
	/** The last player we showed: kept while it's as good a pick as any other, so two paused players don't flip-flop. */
	private String sticky;

	@Override
	public MediaState poll() throws Exception {
		long now = System.nanoTime();
		if (now - namesAt > 3_000_000_000L || names.isEmpty() && now - namesAt > 1_000_000_000L) {
			namesAt = now;
			List<String> found = new ArrayList<>();
			Matcher m = NAME.matcher(gdbus("--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus", "--method",
					"org.freedesktop.DBus.ListNames"));
			while (m.find()) {
				found.add(m.group(1));
			}
			names = found;
		}
		MediaState best = null;
		int bestScore = Integer.MIN_VALUE;
		for (String name : names) {
			MediaState state;
			try {
				state = read(name);
			} catch (IOException e) {
				namesAt = 0;
				continue;
			}
			if (state == null) {
				continue;
			}
			String id = name.substring(PREFIX.length()).toLowerCase(Locale.ROOT);
			int score = (state.playing() ? 100 : 0) + (name.equals(sticky) ? 10 : 0) + (id.startsWith("spotify") ? 5 : 0)
					- (id.startsWith("kdeconnect") ? 20 : 0);
			if (score > bestScore) {
				bestScore = score;
				best = state;
			}
		}
		sticky = best == null ? null : PREFIX + best.player();
		return best;
	}

	private MediaState read(String name) throws Exception {
		String out = gdbus("--dest", name, "--object-path", PATH, "--method", "org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2.Player");
		String status = string(out, "PlaybackStatus");
		String title = string(out, "xesam:title");
		if (title == null || title.isBlank() || "Stopped".equals(status)) {
			return null;
		}
		long sampled = System.nanoTime();
		double volume = number(out, "Volume", -1);
		return new MediaState(name.substring(PREFIX.length()), title, string(out, "xesam:artist"), string(out, "xesam:album"),
				artPath(string(out, "mpris:artUrl")), (long) number(out, "mpris:length", 0) / 1000L, (long) number(out, "Position", 0) / 1000L,
				sampled, "Playing".equals(status), volume < 0 ? -1 : Math.min(1, volume), bool(out, "CanGoNext"), bool(out, "CanGoPrevious"),
				bool(out, "CanSeek"));
	}

	private static String artPath(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		if (url.startsWith("file://")) {
			return java.net.URLDecoder.decode(url.substring(7).replace("+", "%2B"), StandardCharsets.UTF_8);
		}
		return url;
	}

	@Override
	public void playPause(MediaState on) throws Exception {
		call(on, "PlayPause");
	}

	@Override
	public void next(MediaState on) throws Exception {
		call(on, "Next");
	}

	@Override
	public void previous(MediaState on) throws Exception {
		call(on, "Previous");
	}

	@Override
	public void setVolume(MediaState on, double volume) throws Exception {
		gdbus("--dest", PREFIX + on.player(), "--object-path", PATH, "--method", "org.freedesktop.DBus.Properties.Set",
				"org.mpris.MediaPlayer2.Player", "Volume", "<" + String.format(Locale.ROOT, "%.3f", volume) + ">");
	}

	@Override
	public void seek(MediaState on, long positionMs) throws Exception {
		// Relative Seek needs no track id (SetPosition does, and players disagree on its type).
		long delta = (positionMs - on.positionNow()) * 1000L;
		gdbus("--dest", PREFIX + on.player(), "--object-path", PATH, "--method", "org.mpris.MediaPlayer2.Player.Seek", "int64 " + delta);
	}

	private static void call(MediaState on, String method) throws Exception {
		gdbus("--dest", PREFIX + on.player(), "--object-path", PATH, "--method", "org.mpris.MediaPlayer2.Player." + method);
	}

	private static String gdbus(String... args) throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>(List.of("gdbus", "call", "--session"));
		cmd.addAll(List.of(args));
		Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		byte[] out = process.getInputStream().readAllBytes();
		if (!process.waitFor(3, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("gdbus timed out");
		}
		String text = new String(out, StandardCharsets.UTF_8);
		if (process.exitValue() != 0) {
			throw new IOException(text.trim());
		}
		return text;
	}

	// ---- GVariant text, just enough of it: 'key': <'str'>, <['a', 'b']>, <int64 5>, <0.5>, <true> ----

	private static int valueStart(String text, String key) {
		int at = text.indexOf("'" + key + "': <");
		if (at < 0) {
			return -1;
		}
		int i = at + key.length() + 5;
		// A type annotation in front of the value: "int64 5", "@as []", "objectpath '/x'".
		if (i < text.length() && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '@') && !text.startsWith("true", i)
				&& !text.startsWith("false", i)) {
			int space = text.indexOf(' ', i);
			if (space > 0) {
				i = space + 1;
			}
		}
		return i;
	}

	static String string(String text, String key) {
		int i = valueStart(text, key);
		if (i < 0 || i >= text.length()) {
			return null;
		}
		if (text.charAt(i) == '[') {
			List<String> parts = new ArrayList<>();
			i++;
			while (i < text.length() && text.charAt(i) != ']') {
				char c = text.charAt(i);
				if (c == '\'' || c == '"') {
					StringBuilder sb = new StringBuilder();
					i = quoted(text, i, sb);
					parts.add(sb.toString());
				} else {
					i++;
				}
			}
			return parts.isEmpty() ? null : String.join(", ", parts);
		}
		char c = text.charAt(i);
		if (c != '\'' && c != '"') {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		quoted(text, i, sb);
		return sb.toString();
	}

	/** Reads the quoted string starting at {@code i} into {@code sb}, returns the index after its closing quote. */
	private static int quoted(String text, int i, StringBuilder sb) {
		char quote = text.charAt(i++);
		while (i < text.length()) {
			char c = text.charAt(i++);
			if (c == quote) {
				return i;
			}
			if (c == '\\' && i < text.length()) {
				char e = text.charAt(i++);
				switch (e) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (i + 4 <= text.length()) {
							sb.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
							i += 4;
						}
					}
					case 'U' -> {
						if (i + 8 <= text.length()) {
							sb.appendCodePoint(Integer.parseInt(text.substring(i, i + 8), 16));
							i += 8;
						}
					}
					default -> sb.append(e);
				}
			} else {
				sb.append(c);
			}
		}
		return i;
	}

	static double number(String text, String key, double fallback) {
		int i = valueStart(text, key);
		if (i < 0) {
			return fallback;
		}
		int end = i;
		while (end < text.length() && "-+.0123456789eE".indexOf(text.charAt(end)) >= 0) {
			end++;
		}
		try {
			return end > i ? Double.parseDouble(text.substring(i, end)) : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean bool(String text, String key) {
		int i = valueStart(text, key);
		return i >= 0 && text.startsWith("true", i);
	}
}
