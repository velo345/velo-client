package net.veloclient.velo.client.nowplaying;

/**
 * One snapshot of what the system's media player is doing. Position is sampled at {@code sampledAt}
 * ({@link System#nanoTime()}) and runs on from there while playing - see {@link #positionNow()}.
 *
 * @param art    a file path or http(s) URL of the cover, or null
 * @param volume 0..1, or -1 when this player's volume can't be read or set
 */
public record MediaState(String player, String title, String artist, String album, String art, long lengthMs, long positionMs,
		long sampledAt, boolean playing, double volume, boolean canNext, boolean canPrevious, boolean canSeek) {

	/** A stable key for "the same song": a new key is a song change. */
	public String trackKey() {
		return player + "|" + title + "|" + artist;
	}

	public long positionNow() {
		long pos = positionMs;
		if (playing) {
			pos += (System.nanoTime() - sampledAt) / 1_000_000L;
		}
		return lengthMs > 0 ? Math.max(0, Math.min(lengthMs, pos)) : Math.max(0, pos);
	}

	public MediaState withPlaying(boolean nowPlaying) {
		return new MediaState(player, title, artist, album, art, lengthMs, positionNow(), System.nanoTime(), nowPlaying, volume, canNext,
				canPrevious, canSeek);
	}

	public MediaState withVolume(double v) {
		return new MediaState(player, title, artist, album, art, lengthMs, positionMs, sampledAt, playing, v, canNext, canPrevious, canSeek);
	}

	public MediaState withPosition(long ms) {
		return new MediaState(player, title, artist, album, art, lengthMs, ms, System.nanoTime(), playing, volume, canNext, canPrevious, canSeek);
	}

	/** The player's name for people: "spotify" -> "Spotify", "chrome.exe" -> "Chrome". */
	public String playerLabel() {
		String p = player == null ? "" : player;
		int bang = p.lastIndexOf('!');
		if (bang >= 0) {
			p = p.substring(bang + 1);
		}
		p = p.replaceAll("(?i)\\.exe$", "").replaceAll("\\.instance\\d+$", "").replaceAll("^kdeconnect.*", "Phone");
		int dot = p.lastIndexOf('.');
		if (dot >= 0 && dot < p.length() - 1) {
			p = p.substring(dot + 1);
		}
		return p.isEmpty() ? "Media" : Character.toUpperCase(p.charAt(0)) + p.substring(1);
	}
}
