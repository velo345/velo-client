package net.veloclient.velo.client.nowplaying;

import java.util.Locale;

/**
 * Talks to the operating system's media controls - never to Spotify (or any other service) directly, so there's no API
 * key, no account and no per-service rules: whatever the OS shows in its own media flyout is what we see and control.
 * All calls are blocking and made from {@link NowPlaying}'s worker thread, never the render thread.
 */
interface MediaBackend {

	/** The current session, or null when nothing is playing or paused. */
	MediaState poll() throws Exception;

	void playPause(MediaState on) throws Exception;

	void next(MediaState on) throws Exception;

	void previous(MediaState on) throws Exception;

	void setVolume(MediaState on, double volume) throws Exception;

	void seek(MediaState on, long positionMs) throws Exception;

	default void close() {
	}

	static MediaBackend forThisOs() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return new WindowsSmtc();
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return new MacScript();
		}
		return new LinuxMpris();
	}
}
