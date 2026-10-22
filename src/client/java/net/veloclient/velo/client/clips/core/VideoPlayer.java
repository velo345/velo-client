package net.veloclient.velo.client.clips.core;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * A video player for clips, shared by the game and the launcher (plain Java): FFmpeg decodes the picture to raw pixels
 * (scaled down to what's shown) and the sound to PCM in two processes, the sound plays through Java Sound and is the
 * clock the picture follows. Works on local files and https links (staff watching a reported clip).
 *
 * <p>The UI calls {@link #frame()} every frame: it returns the picture to show now (or null when it didn't change) as
 * raw RGBA or BGRA bytes, which the UI copies into its own texture/image and hands back with {@link #recycle}. Seeking
 * restarts both processes at the new spot (FFmpeg seeks exactly and fast with {@code -ss} before the input).
 *
 * <p>{@link #setRange} limits playback to the trimmed part (it plays from the start mark to the end mark and stops).
 */
public final class VideoPlayer {

	public enum Pixels { RGBA, BGRA }

	/** One decoded picture; {@code data} is width * height * 4 bytes. */
	public static final class Frame {
		public final byte[] data;
		public final int width;
		public final int height;
		double pts;

		Frame(int width, int height) {
			this.width = width;
			this.height = height;
			this.data = new byte[width * height * 4];
		}

		public double pts() {
			return pts;
		}
	}

	private static final int RATE = 48_000;
	private static final int QUEUE = 6;

	private final Path ffmpeg;
	private final String source;
	private final ClipTools.Info info;
	private final Pixels pixels;
	private final int outWidth;
	private final int outHeight;
	private final double fps;

	private final ConcurrentLinkedQueue<Frame> pool = new ConcurrentLinkedQueue<>();
	private volatile Session session;
	private volatile boolean playing;
	private volatile boolean ended;
	private volatile float volume = 0.8f;
	private volatile boolean muted;
	private volatile String error;
	private double rangeFrom;
	private double rangeTo;
	/** Where playback stands while paused or before the clock has started. */
	private volatile double held;
	private boolean released;

	/** Opens {@code source} (a path or URL); {@code maxWidth} caps the decoded size. Probes the clip (blocks briefly). */
	public static VideoPlayer open(Path ffmpeg, String source, int maxWidth, Pixels pixels) throws IOException {
		return new VideoPlayer(ffmpeg, source, ClipTools.probe(ffmpeg, source), maxWidth, pixels);
	}

	public VideoPlayer(Path ffmpeg, String source, ClipTools.Info info, int maxWidth, Pixels pixels) {
		this.ffmpeg = ffmpeg;
		this.source = source;
		this.info = info;
		this.pixels = pixels;
		int w = Math.max(2, Math.min(maxWidth, info.width())) & ~1;
		this.outWidth = w;
		this.outHeight = Math.max(2, (int) Math.round(info.height() * (double) w / Math.max(1, info.width()))) & ~1;
		this.fps = info.fps() > 1 ? Math.min(60, info.fps()) : 30;
		this.rangeTo = length();
		start(0, false);
	}

	public ClipTools.Info info() {
		return info;
	}

	public int width() {
		return outWidth;
	}

	public int height() {
		return outHeight;
	}

	public double duration() {
		return info.seconds();
	}

	/** The length to play to (an unknown length plays until the stream ends). */
	private double length() {
		return info.seconds() > 0 ? info.seconds() : Double.MAX_VALUE;
	}

	public String error() {
		return error;
	}

	public boolean playing() {
		return playing;
	}

	public boolean ended() {
		return ended;
	}

	// ---- Controls ----

	public synchronized void play() {
		if (released) {
			return;
		}
		if (ended || position() >= rangeTo - 0.05) {
			start(rangeFrom, true);
			return;
		}
		Session s = session;
		if (s == null) {
			start(held, true);
			return;
		}
		playing = true;
		s.resume();
	}

	public synchronized void pause() {
		Session s = session;
		if (s != null) {
			double clock = s.clock();
			if (clock >= 0) {
				held = clock;
			}
			s.pause();
		}
		playing = false;
	}

	public void toggle() {
		if (playing) {
			pause();
		} else {
			play();
		}
	}

	/** Jumps to {@code seconds} (kept inside the trimmed range); keeps playing if it was. */
	public synchronized void seek(double seconds) {
		start(Math.max(rangeFrom, Math.min(rangeTo - 0.02, seconds)), playing);
	}

	/** Plays only {@code from..to} (the trim marks); null-safe full length with (0, duration). */
	public synchronized void setRange(double from, double to) {
		rangeFrom = Math.max(0, Math.min(from, length()));
		rangeTo = Math.max(rangeFrom + 0.1, Math.min(to, length()));
	}

	public double rangeFrom() {
		return rangeFrom;
	}

	public double rangeTo() {
		return rangeTo;
	}

	public void setVolume(float value) {
		volume = Math.max(0, Math.min(1, value));
		Session s = session;
		if (s != null) {
			s.applyVolume();
		}
	}

	public float volume() {
		return volume;
	}

	public void setMuted(boolean value) {
		muted = value;
		setVolume(volume);
	}

	public boolean muted() {
		return muted;
	}

	/** Where playback is now (seconds). */
	public double position() {
		Session s = session;
		if (s == null || !playing) {
			return held;
		}
		double clock = s.clock();
		return clock < 0 ? held : clock;
	}

	/**
	 * The picture to show now, or null when the one already shown is still right. Pass it back to {@link #recycle}
	 * once copied. Also notices the end of the clip / trimmed range.
	 */
	public Frame frame() {
		Session s = session;
		if (s == null) {
			return null;
		}
		if (!playing) {
			// Paused (or just seeked while paused): show the first picture at the spot once.
			if (!s.shownFirst) {
				Frame first = s.frames.poll();
				if (first != null) {
					s.shownFirst = true;
				}
				return first;
			}
			return null;
		}
		double now = s.clock();
		if (now < 0) {
			return null;
		}
		if (now >= rangeTo || s.videoDone && s.frames.isEmpty() && (now > s.lastPts + 0.2 || s.audioDone)) {
			synchronized (this) {
				playing = false;
				ended = true;
				held = Math.min(now, rangeTo);
				s.pause();
			}
			return null;
		}
		Frame best = null;
		while (true) {
			Frame next = s.frames.peek();
			if (next == null || next.pts > now) {
				break;
			}
			s.frames.poll();
			if (best != null) {
				recycle(best);
			}
			best = next;
		}
		if (best != null) {
			s.shownFirst = true;
		}
		return best;
	}

	public void recycle(Frame frame) {
		if (frame != null && frame.width == outWidth && frame.height == outHeight && pool.size() < QUEUE + 4) {
			pool.offer(frame);
		}
	}

	/** Stops everything (processes, sound); the player can't be used after. */
	public synchronized void release() {
		released = true;
		playing = false;
		Session s = session;
		session = null;
		if (s != null) {
			s.stop();
		}
	}

	// ---- Sessions: one pair of FFmpeg processes from one spot ----

	private void start(double at, boolean play) {
		if (released) {
			return;
		}
		Session old = session;
		if (old != null) {
			old.stop();
		}
		ended = false;
		held = at;
		playing = play;
		Session s = new Session(at);
		session = s;
		s.begin(play);
	}

	private final class Session {
		final double start;
		final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(QUEUE);
		final List<Process> processes = new ArrayList<>();
		volatile boolean stopped;
		volatile boolean videoDone;
		volatile boolean audioDone;
		volatile boolean shownFirst;
		volatile double lastPts;
		volatile boolean started;
		SourceDataLine line;
		/** Without sound: the wall clock, paused time left out. */
		long wallStart;
		double wallBase;
		boolean wallRunning;
		boolean useAudio;

		Session(double start) {
			this.start = start;
		}

		void begin(boolean play) {
			useAudio = info.audio();
			Thread.ofPlatform().daemon().name("velo-video-decode").start(this::decodeVideo);
			if (useAudio) {
				Thread.ofPlatform().daemon().name("velo-video-audio").start(() -> playAudio(play));
			}
			wallBase = start;
			if (play) {
				wallStart = System.nanoTime();
				wallRunning = true;
			}
			started = true;
		}

		/** Seconds into the clip by the sound card (or the wall clock); -1 before the sound has started. */
		synchronized double clock() {
			if (useAudio) {
				SourceDataLine l = line;
				if (l == null) {
					return playing ? -1 : held;
				}
				return start + l.getLongFramePosition() / (double) RATE;
			}
			return wallRunning ? wallBase + (System.nanoTime() - wallStart) / 1e9 : wallBase;
		}

		synchronized void pause() {
			if (line != null) {
				line.stop();
			}
			if (wallRunning) {
				wallBase += (System.nanoTime() - wallStart) / 1e9;
				wallRunning = false;
			}
		}

		synchronized void resume() {
			if (line != null) {
				line.start();
			}
			if (!wallRunning) {
				wallStart = System.nanoTime();
				wallRunning = true;
			}
		}

		void applyVolume() {
			SourceDataLine l = line;
			if (l != null && l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl gain = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
				float v = muted ? 0 : volume;
				float db = v <= 0.001f ? gain.getMinimum() : (float) (20 * Math.log10(v));
				gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
			}
		}

		void decodeVideo() {
			List<String> command = List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", ClipTools.fmt(start),
					"-i", source, "-an", "-sn", "-vf", "scale=" + outWidth + ":" + outHeight + ":flags=bilinear,fps=" + ClipTools.fmt(fps),
					"-pix_fmt", pixels == Pixels.RGBA ? "rgba" : "bgra", "-f", "rawvideo", "-");
			try {
				Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				synchronized (processes) {
					if (stopped) {
						p.destroyForcibly();
						return;
					}
					processes.add(p);
				}
				try (InputStream in = p.getInputStream()) {
					long n = 0;
					while (!stopped) {
						Frame frame = pool.poll();
						if (frame == null) {
							frame = new Frame(outWidth, outHeight);
						}
						if (in.readNBytes(frame.data, 0, frame.data.length) < frame.data.length) {
							break;
						}
						frame.pts = start + n++ / fps;
						lastPts = frame.pts;
						while (!stopped && !frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
							// Waits while the queue is full (paused, or decoding ahead).
						}
					}
				}
			} catch (Exception e) {
				if (!stopped) {
					error = "Couldn't play the clip: " + e.getMessage();
				}
			} finally {
				videoDone = true;
			}
		}

		void playAudio(boolean play) {
			List<String> command = List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", ClipTools.fmt(start),
					"-i", source, "-vn", "-sn", "-ac", "2", "-ar", String.valueOf(RATE), "-f", "s16le", "-");
			try {
				Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				synchronized (processes) {
					if (stopped) {
						p.destroyForcibly();
						return;
					}
					processes.add(p);
				}
				AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
				SourceDataLine l = AudioSystem.getSourceDataLine(format);
				l.open(format, RATE / 5 * 4);
				synchronized (this) {
					if (stopped) {
						l.close();
						return;
					}
					line = l;
					applyVolume();
					if (playing) {
						l.start();
					}
				}
				byte[] buffer = new byte[RATE / 50 * 4];
				try (InputStream in = p.getInputStream()) {
					int read;
					while (!stopped && (read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
						// Never fill the line while it's stopped (some sound servers then don't move on after start()).
						while (!playing && !stopped) {
							Thread.sleep(10);
						}
						l.write(buffer, 0, read - read % 4);
					}
				}
				if (!stopped) {
					l.drain();
				}
				audioDone = true;
			} catch (Exception e) {
				// No sound device / no audio after all: the picture runs on the wall clock.
				synchronized (this) {
					useAudio = false;
					wallBase = start;
					wallStart = System.nanoTime();
					wallRunning = playing;
				}
			}
		}

		void stop() {
			stopped = true;
			synchronized (processes) {
				for (Process p : processes) {
					p.destroyForcibly();
				}
			}
			synchronized (this) {
				if (line != null) {
					line.stop();
					line.flush();
					line.close();
					line = null;
				}
			}
			Frame f;
			while ((f = frames.poll()) != null) {
				recycle(f);
			}
		}
	}
}
