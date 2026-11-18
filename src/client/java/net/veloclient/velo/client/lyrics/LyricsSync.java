package net.veloclient.velo.client.lyrics;

import net.veloclient.velo.client.nowplaying.MediaState;
import net.veloclient.velo.client.nowplaying.NowPlaying;

import java.util.List;

/**
 * Which song plays (from the system's media session - Spotify or any other player) and its lyrics. Looks the lyrics up
 * once per song in the background; {@link #now()} gives the song position the renderer draws from.
 */
final class LyricsSync {

	enum Status { NOTHING_PLAYING, LOADING, FOUND, NOT_FOUND, OFFLINE }

	private static String trackKey;
	private static volatile LyricsFetcher.Lyrics lyrics;
	private static volatile Status status = Status.NOTHING_PLAYING;
	private static long retryAt;
	private static long songStartedAt;
	private static String title = "";
	private static String artist = "";

	/** Made-up lines the Lyrics Studio shows when nothing plays, so every setting can be seen at once. */
	private static final List<LyricsFetcher.Line> DEMO = List.of(
			new LyricsFetcher.Line(0, "Velo Lyrics"),
			new LyricsFetcher.Line(2600, "Your song, right in the sky"),
			new LyricsFetcher.Line(5600, "Every word in time with the beat"),
			new LyricsFetcher.Line(8800, "Play something on Spotify"),
			new LyricsFetcher.Line(11800, "and watch it come alive"));
	private static final long DEMO_LENGTH = 15000;
	private static final long DEMO_START = System.currentTimeMillis();

	private LyricsSync() {
	}

	/** Each frame: notice song changes and fetch their lyrics. */
	static void update() {
		MediaState s = NowPlaying.state();
		if (s == null || s.title() == null || s.title().isBlank()) {
			if (trackKey != null) {
				trackKey = null;
				lyrics = null;
			}
			status = Status.NOTHING_PLAYING;
			return;
		}
		String key = s.trackKey();
		if (!key.equals(trackKey)) {
			trackKey = key;
			lyrics = null;
			title = s.title();
			artist = s.artist() == null ? "" : s.artist();
			songStartedAt = System.currentTimeMillis();
			retryAt = 0;
			load(s, key);
		} else if (status == Status.OFFLINE && System.currentTimeMillis() > retryAt) {
			load(s, key);
		}
	}

	private static void load(MediaState s, String key) {
		status = Status.LOADING;
		Thread.ofVirtual().name("velo-lyrics").start(() -> {
			LyricsFetcher.Lyrics found = LyricsFetcher.fetch(s.title(), s.artist(), s.album(), s.lengthMs());
			if (!key.equals(trackKey)) {
				return; // the song changed meanwhile
			}
			if (found == null) {
				status = Status.OFFLINE;
				retryAt = System.currentTimeMillis() + 30_000;
				return;
			}
			lyrics = found;
			status = found.found() ? Status.FOUND : Status.NOT_FOUND;
		});
	}

	static Status status() {
		return status;
	}

	static String title() {
		return title;
	}

	static String artist() {
		return artist;
	}

	/** Milliseconds since this song started showing (for the song card). */
	static long sinceSongStart() {
		return System.currentTimeMillis() - songStartedAt;
	}

	static boolean playing() {
		MediaState s = NowPlaying.state();
		return s != null && s.playing();
	}

	/** The lines to show: the song's, the demo ones (preview), or null. */
	static List<LyricsFetcher.Line> lines(boolean demo) {
		if (LyricsDev.on()) {
			return LyricsDev.lines();
		}
		LyricsFetcher.Lyrics l = lyrics;
		if (l != null && l.found()) {
			return l.lines();
		}
		return demo ? DEMO : null;
	}

	static boolean showingDemo(boolean demo) {
		if (LyricsDev.on()) {
			return true;
		}
		LyricsFetcher.Lyrics l = lyrics;
		return demo && (l == null || !l.found());
	}

	/** The song position in ms (with the sync offset), or the demo clock. */
	static long now(boolean demo, int offsetMs) {
		if (LyricsDev.on()) {
			return LyricsDev.now() + offsetMs;
		}
		if (showingDemo(demo)) {
			return (System.currentTimeMillis() - DEMO_START) % DEMO_LENGTH;
		}
		MediaState s = NowPlaying.state();
		return s == null ? 0 : s.positionNow() + offsetMs;
	}

	/** End of the demo loop / the song (so the last line knows when to leave). */
	static long length(boolean demo) {
		if (LyricsDev.on()) {
			return LyricsDev.length();
		}
		if (showingDemo(demo)) {
			return DEMO_LENGTH - 400;
		}
		MediaState s = NowPlaying.state();
		return s == null || s.lengthMs() <= 0 ? Long.MAX_VALUE : s.lengthMs();
	}
}
