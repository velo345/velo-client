package net.veloclient.velo.client.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.config.VeloPaths;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics for a song from LRCLIB (lrclib.net - a free, open lyrics database made for exactly this, no account
 * or key). Songs are matched by title, artist, album and length; the answer (or "none") is kept on disk in
 * .velo-client/lyrics-cache, so a song is only asked for once.
 */
final class LyricsFetcher {

	/** One lyric line: when it starts (ms into the song) and its text ("" = an instrumental gap). */
	record Line(long startMs, String text) {
	}

	/** {@code lines} empty with {@code found} false = no synced lyrics for this song. */
	record Lyrics(List<Line> lines, boolean found, boolean plainOnly) {
		static final Lyrics NONE = new Lyrics(List.of(), false, false);
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	private static final String AGENT = "Velo Client Lyrics (https://velo-client.com)";

	private LyricsFetcher() {
	}

	/** Blocks (network) - call from a worker thread. */
	static Lyrics fetch(String title, String artist, String album, long lengthMs) {
		String cleanTitle = clean(title);
		String cleanArtist = artist == null ? "" : artist.split(",|&| feat\\.| ft\\.")[0].trim();
		Path cache = cacheFile(cleanTitle, cleanArtist);
		try {
			if (Files.isRegularFile(cache)) {
				return parseCached(Files.readString(cache, StandardCharsets.UTF_8));
			}
		} catch (Exception ignored) {
			// fetch again
		}
		Lyrics result = Lyrics.NONE;
		try {
			JsonObject best = null;
			if (!cleanArtist.isEmpty()) {
				String url = "https://lrclib.net/api/get?track_name=" + enc(cleanTitle) + "&artist_name=" + enc(cleanArtist)
						+ (album != null && !album.isBlank() ? "&album_name=" + enc(album) : "")
						+ (lengthMs > 0 ? "&duration=" + Math.round(lengthMs / 1000.0) : "");
				HttpResponse<String> r = get(url);
				if (r.statusCode() == 200) {
					best = JsonParser.parseString(r.body()).getAsJsonObject();
				}
			}
			if (best == null || !has(best, "syncedLyrics")) {
				// looser: search and take the synced result closest in length
				HttpResponse<String> r = get("https://lrclib.net/api/search?track_name=" + enc(cleanTitle)
						+ (cleanArtist.isEmpty() ? "" : "&artist_name=" + enc(cleanArtist)));
				if (r.statusCode() == 200) {
					JsonArray all = JsonParser.parseString(r.body()).getAsJsonArray();
					double bestDiff = Double.MAX_VALUE;
					for (JsonElement e : all) {
						JsonObject o = e.getAsJsonObject();
						if (!has(o, "syncedLyrics")) {
							continue;
						}
						double d = lengthMs > 0 && o.has("duration") && !o.get("duration").isJsonNull()
								? Math.abs(o.get("duration").getAsDouble() - lengthMs / 1000.0) : 0;
						if (d < bestDiff && d < 8) {
							bestDiff = d;
							best = o;
						}
					}
				}
			}
			if (best != null && has(best, "syncedLyrics")) {
				List<Line> lines = parseLrc(best.get("syncedLyrics").getAsString());
				result = lines.isEmpty() ? Lyrics.NONE : new Lyrics(lines, true, false);
			} else if (best != null && has(best, "plainLyrics")) {
				result = new Lyrics(List.of(), false, true);
			}
			Files.createDirectories(cache.getParent());
			Files.writeString(cache, toCache(result), StandardCharsets.UTF_8);
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Lyrics lookup failed for {} - {}", title, artist, e);
			return null; // network trouble: try again later, don't cache
		}
		return result;
	}

	private static HttpResponse<String> get(String url) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("User-Agent", AGENT).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static boolean has(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isBlank();
	}

	/** "[00:12.34] text" lines (several tags per line allowed), sorted by time. */
	static List<Line> parseLrc(String lrc) {
		List<Line> lines = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher m = TAG.matcher(raw);
			List<Long> times = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				long min = Long.parseLong(m.group(1));
				long sec = Long.parseLong(m.group(2));
				String frac = m.group(3) == null ? "0" : m.group(3);
				long ms = frac.length() == 1 ? Long.parseLong(frac) * 100 : frac.length() == 2 ? Long.parseLong(frac) * 10 : Long.parseLong(frac);
				times.add(min * 60_000 + sec * 1000 + ms);
				end = m.end();
			}
			if (times.isEmpty()) {
				continue;
			}
			String text = raw.substring(end).replaceAll("<\\d+:\\d+(?:\\.\\d+)?>", "").trim();
			for (long t : times) {
				lines.add(new Line(t, text));
			}
		}
		lines.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
		return lines;
	}

	/** Drops "(Remastered 2011)", "- Radio Edit" and similar, which lyric databases leave out. */
	static String clean(String title) {
		if (title == null) {
			return "";
		}
		String t = title.replaceAll("(?i)\\s*[(\\[][^)\\]]*(remaster|version|edit|live|mono|stereo|deluxe|bonus)[^)\\]]*[)\\]]", "");
		t = t.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|live|mix).*$", "");
		return t.trim();
	}

	private static Path cacheFile(String title, String artist) {
		String key = (artist + " - " + title).toLowerCase(Locale.ROOT);
		String name = key.replaceAll("[^a-z0-9]+", "_");
		if (name.length() > 80) {
			name = name.substring(0, 80);
		}
		return VeloPaths.root().resolve("lyrics-cache").resolve(name + "_" + Integer.toHexString(key.hashCode()) + ".json");
	}

	private static String toCache(Lyrics l) {
		JsonObject o = new JsonObject();
		o.addProperty("found", l.found());
		o.addProperty("plainOnly", l.plainOnly());
		o.addProperty("at", System.currentTimeMillis());
		JsonArray lines = new JsonArray();
		for (Line line : l.lines()) {
			JsonArray a = new JsonArray();
			a.add(line.startMs());
			a.add(line.text());
			lines.add(a);
		}
		o.add("lines", lines);
		return o.toString();
	}

	private static Lyrics parseCached(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		boolean found = o.get("found").getAsBoolean();
		// "none" answers are asked again after a week (someone may have added the song since)
		if (!found && o.has("at") && System.currentTimeMillis() - o.get("at").getAsLong() > 7L * 24 * 3600 * 1000) {
			throw new IllegalStateException("stale");
		}
		List<Line> lines = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("lines")) {
			JsonArray a = e.getAsJsonArray();
			lines.add(new Line(a.get(0).getAsLong(), a.get(1).getAsString()));
		}
		return new Lyrics(lines, found, o.has("plainOnly") && o.get("plainOnly").getAsBoolean());
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
