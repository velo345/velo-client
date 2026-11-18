package net.veloclient.velo.client.lyrics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Video mode: with {@code -Dvelo.lyricsDemo=<file.json>} the lyrics (lines, style, clock) come from a file that is read again
 * whenever it changes, so footage can be made with exactly chosen words: {@code {"preset": "Chrome", "style": {"size": 1.4},
 * "lines": [{"t": 0, "text": "VELO"}], "length": 9000, "clock": "replay" | "wall"}}. "replay" locks the time to the open
 * Rewind clip (frame-exact exports), "wall" starts at 0 when the file is loaded. Does nothing without the property.
 */
public final class LyricsDev {

	private static final Path FILE = System.getProperty("velo.lyricsDemo") == null ? null : Path.of(System.getProperty("velo.lyricsDemo"));
	private static long checkedAt;
	private static long modified = -1;
	private static volatile boolean on;
	private static volatile List<LyricsFetcher.Line> lines = List.of();
	private static volatile long length = 10000;
	private static volatile boolean replayClock;
	private static volatile long startedAt;

	private LyricsDev() {
	}

	/** Reads the file again when it changed; called every frame, looks at the disk twice a second. */
	static void poll() {
		if (FILE == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - checkedAt < 500) {
			return;
		}
		checkedAt = now;
		try {
			if (!Files.exists(FILE)) {
				on = false;
				modified = -1;
				return;
			}
			long m = Files.getLastModifiedTime(FILE).toMillis();
			if (m == modified) {
				return;
			}
			modified = m;
			JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			LyricsStyle base = LyricsStyle.PRESETS.getOrDefault(o.has("preset") ? o.get("preset").getAsString() : "Concert Sky", new LyricsStyle()).copy();
			if (o.has("style")) {
				// any style field by name, on top of the preset
				JsonObject merged = JsonParser.parseString(base.save()).getAsJsonObject();
				for (var e : o.getAsJsonObject("style").entrySet()) {
					merged.add(e.getKey(), e.getValue());
				}
				base = LyricsStyle.load(merged.toString());
			}
			base.songCard = false;
			LyricsModule.style = base;
			List<LyricsFetcher.Line> list = new ArrayList<>();
			if (o.has("lines")) {
				for (JsonElement el : o.getAsJsonArray("lines")) {
					JsonObject l = el.getAsJsonObject();
					list.add(new LyricsFetcher.Line(l.get("t").getAsLong(), l.get("text").getAsString()));
				}
			}
			lines = list;
			length = o.has("length") ? o.get("length").getAsLong() : 10000;
			replayClock = o.has("clock") && "replay".equals(o.get("clock").getAsString());
			startedAt = now;
			on = !list.isEmpty();
		} catch (Exception e) {
			on = false;
		}
	}

	static boolean on() {
		return on;
	}

	static List<LyricsFetcher.Line> lines() {
		return lines;
	}

	static long length() {
		return length;
	}

	/** The position in the lines, in ms. */
	static long now() {
		if (replayClock) {
			long clip = net.veloclient.velo.devbuild.rewind.RewindClock.clipMillis();
			if (clip >= 0) {
				return clip;
			}
		}
		return (System.currentTimeMillis() - startedAt) % Math.max(1000, length);
	}
}
