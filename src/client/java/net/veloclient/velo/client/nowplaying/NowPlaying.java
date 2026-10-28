package net.veloclient.velo.client.nowplaying;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * What's playing on this computer, kept in sync with the OS media session from one worker thread: polled every
 * ~0.6 s (and right after every command), so pausing in Spotify, a media key or the phone shows up here too.
 *
 * <p>Our own clicks apply at once (optimistic) and hold that value for a moment, so a poll that still sees the old
 * state doesn't make the button or the volume slider jump back. Volume drags are coalesced: only the newest value
 * is sent, at most every 70 ms.
 */
public final class NowPlaying {

	private static final Logger LOGGER = LoggerFactory.getLogger("velo-client");
	private static final long HOLD = 1_500_000_000L;

	private static final LinkedBlockingQueue<Runnable> COMMANDS = new LinkedBlockingQueue<>();
	private static volatile MediaState state;
	private static volatile boolean running;
	private static volatile Thread worker;
	private static volatile MediaBackend backend;

	private static volatile long playingHeldUntil;
	private static volatile long volumeHeldUntil;
	private static volatile long positionHeldUntil;
	private static volatile double pendingVolume = -1;

	/** Bumped on every song change (and on pause/resume, separately) - the popup watches these. */
	private static volatile int trackSerial;
	private static volatile int playStateSerial;
	private static String lastKey;
	private static volatile Boolean lastPlaying;

	private NowPlaying() {
	}

	public static synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		worker = new Thread(NowPlaying::loop, "Velo Now Playing");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	public static synchronized void stop() {
		running = false;
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
		state = null;
	}

	public static boolean running() {
		return running;
	}

	/** The current session, position running on; null when nothing plays. */
	public static MediaState state() {
		return state;
	}

	public static int trackSerial() {
		return trackSerial;
	}

	public static int playStateSerial() {
		return playStateSerial;
	}

	/** Render thread, every frame something shows the cover: keeps the cover texture in step with the song. */
	public static void syncCover() {
		MediaState s = state;
		CoverArt.want(s == null ? null : s.art());
	}

	private static void loop() {
		boolean failedOnce = false;
		Thread self = Thread.currentThread();
		MediaBackend mine = MediaBackend.forThisOs();
		backend = mine;
		try {
			while (running && worker == self) {
				try {
					MediaState polled = mine.poll();
					merge(polled);
					failedOnce = false;
				} catch (InterruptedException e) {
					break;
				} catch (Exception e) {
					if (!failedOnce) {
						LOGGER.warn("Now Playing: couldn't read the system media session ({})", e.toString());
						failedOnce = true;
					}
					merge(null);
				}
				// Wait for the next poll or a command, whichever comes first.
				// Nothing playing: look less often (each poll on Linux/macOS starts a small process).
				long wait = state == null ? 1500 : mine instanceof WindowsSmtc ? 250 : 600;
				Runnable command = COMMANDS.poll(wait, TimeUnit.MILLISECONDS);
				boolean ran = false;
				while (command != null) {
					ran = true;
					try {
						command.run();
					} catch (RuntimeException e) {
						LOGGER.debug("Now Playing command failed", e);
					}
					command = COMMANDS.poll();
				}
				if (ran && !(mine instanceof WindowsSmtc)) {
					// Players take a moment to report the new state.
					Thread.sleep(140);
				}
			}
		} catch (InterruptedException ignored) {
			// Stopped.
		} finally {
			mine.close();
			if (backend == mine) {
				backend = null;
				state = null;
			}
		}
	}

	private static void merge(MediaState polled) {
		MediaState mine = state;
		if (polled == null) {
			state = null;
			lastKey = null;
			lastPlaying = null;
			return;
		}
		long now = System.nanoTime();
		boolean sameTrack = mine != null && mine.trackKey().equals(polled.trackKey());
		MediaState merged = polled;
		if (sameTrack) {
			if (now < playingHeldUntil && mine.playing() != polled.playing()) {
				merged = merged.withPlaying(mine.playing());
			}
			if (now < volumeHeldUntil && mine.volume() >= 0) {
				merged = merged.withVolume(mine.volume());
			}
			if (now < positionHeldUntil) {
				merged = merged.withPosition(mine.positionNow());
			}
			// Players report position coarsely: keep our smooth clock unless it's really off (a seek elsewhere).
			if (merged == polled && mine.playing() == polled.playing() && Math.abs(mine.positionNow() - polled.positionNow()) < 1200) {
				merged = merged.withPosition(mine.positionNow());
			}
		} else {
			playingHeldUntil = volumeHeldUntil = positionHeldUntil = 0;
		}
		state = merged;
		String key = merged.trackKey();
		if (!key.equals(lastKey)) {
			lastKey = key;
			trackSerial++;
		} else if (lastPlaying != null && lastPlaying != merged.playing()) {
			playStateSerial++;
		}
		lastPlaying = merged.playing();
	}

	// ---- Controls (any thread; they return at once) ----

	public static void playPause() {
		MediaState s = state;
		if (s == null) {
			return;
		}
		state = s.withPlaying(!s.playing());
		lastPlaying = !s.playing();
		playingHeldUntil = System.nanoTime() + HOLD;
		command(() -> backend.playPause(s));
	}

	public static void next() {
		MediaState s = state;
		if (s != null) {
			command(() -> backend.next(s));
		}
	}

	public static void previous() {
		MediaState s = state;
		if (s == null) {
			return;
		}
		// Like every player: past the first few seconds "previous" restarts the song - show that right away.
		if (s.positionNow() > 4000) {
			state = s.withPosition(0);
			positionHeldUntil = System.nanoTime() + 600_000_000L;
		}
		command(() -> backend.previous(s));
	}

	public static void seek(long positionMs) {
		MediaState s = state;
		if (s == null || !s.canSeek()) {
			return;
		}
		state = s.withPosition(positionMs);
		positionHeldUntil = System.nanoTime() + HOLD;
		command(() -> backend.seek(s, positionMs));
	}

	/** While dragging: applies locally now, sends the newest value at most every 70 ms. */
	public static void setVolume(double volume) {
		MediaState s = state;
		if (s == null || s.volume() < 0) {
			return;
		}
		double v = Math.max(0, Math.min(1, volume));
		state = s.withVolume(v);
		volumeHeldUntil = System.nanoTime() + HOLD;
		boolean queued = pendingVolume >= 0;
		pendingVolume = v;
		if (!queued) {
			command(() -> {
				try {
					Thread.sleep(70);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				double send = pendingVolume;
				pendingVolume = -1;
				MediaState on = state;
				if (on != null && send >= 0) {
					backend.setVolume(on, send);
				}
			});
		}
	}

	@FunctionalInterface
	private interface Command {
		void run() throws Exception;
	}

	private static void command(Command command) {
		if (!running) {
			return;
		}
		COMMANDS.add(() -> {
			try {
				MediaBackend b = backend;
				if (b != null) {
					command.run();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOGGER.debug("Now Playing command failed: {}", e.toString());
			}
		});
	}

	/** "3:07" */
	public static String time(long ms) {
		long s = Math.max(0, ms / 1000);
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	/** Render thread: drops the cover textures (module off). */
	public static void releaseCover() {
		CoverArt.release();
	}
}
