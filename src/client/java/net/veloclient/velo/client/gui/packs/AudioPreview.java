package net.veloclient.velo.client.gui.packs;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays one sound at a time for the pack tools (straight to the sound device, not through the game's mixer, so
 * edited or not-yet-loaded sounds play too). {@link #position()} follows the playhead.
 */
public final class AudioPreview {

	private static Playback current;

	private AudioPreview() {
	}

	private static final class Playback implements Runnable {
		final AudioData audio;
		final int startFrame;
		final int endFrame;
		final boolean loop;
		final float volume;
		volatile boolean stopped;
		volatile int framesWritten;
		volatile long lineStartFrames;
		SourceDataLine line;

		Playback(AudioData audio, int startFrame, int endFrame, boolean loop, float volume) {
			this.audio = audio;
			this.startFrame = startFrame;
			this.endFrame = endFrame;
			this.loop = loop;
			this.volume = volume;
		}

		@Override
		public void run() {
			try {
				AudioFormat format = new AudioFormat(audio.rate, 16, audio.channels, true, false);
				SourceDataLine l = AudioSystem.getSourceDataLine(format);
				l.open(format, audio.rate / 10 * audio.channels * 2);
				synchronized (this) {
					if (stopped) {
						l.close();
						return;
					}
					line = l;
				}
				l.start();
				int chunk = audio.rate / 50;
				byte[] buffer = new byte[chunk * audio.channels * 2];
				do {
					int f = startFrame;
					while (!stopped && f < endFrame) {
						int n = Math.min(chunk, endFrame - f);
						for (int i = 0; i < n * audio.channels; i++) {
							int v = Math.round(audio.pcm[f * audio.channels + i] * volume);
							v = Math.max(-32768, Math.min(32767, v));
							buffer[i * 2] = (byte) v;
							buffer[i * 2 + 1] = (byte) (v >> 8);
						}
						l.write(buffer, 0, n * audio.channels * 2);
						f += n;
						framesWritten += n;
					}
				} while (loop && !stopped);
				if (!stopped) {
					l.drain();
				}
			} catch (Exception ignored) {
				// No sound device: nothing plays.
			} finally {
				synchronized (this) {
					if (line != null) {
						line.stop();
						line.flush();
						line.close();
						line = null;
					}
				}
				stopped = true;
			}
		}

		/** Seconds into the sound the listener hears now. */
		double position() {
			SourceDataLine l = line;
			long played = l == null ? 0 : l.getLongFramePosition();
			int span = Math.max(1, endFrame - startFrame);
			long within = loop ? played % span : Math.min(played, span);
			return (startFrame + within) / (double) audio.rate;
		}
	}

	/** Plays frames [startFrame, endFrame) of {@code audio}, replacing whatever was playing. */
	public static void play(AudioData audio, int startFrame, int endFrame, boolean loop, float volume) {
		stop();
		Playback playback = new Playback(audio, Math.max(0, startFrame), Math.min(audio.frames(), Math.max(startFrame + 1, endFrame)), loop, volume);
		current = playback;
		Thread thread = new Thread(playback, "velo-sound-preview");
		thread.setDaemon(true);
		thread.start();
	}

	public static void play(AudioData audio) {
		play(audio, 0, audio.frames(), false, 1f);
	}

	public static void stop() {
		Playback playback = current;
		current = null;
		if (playback != null) {
			playback.stopped = true;
			synchronized (playback) {
				if (playback.line != null) {
					playback.line.stop();
					playback.line.flush();
				}
			}
		}
	}

	/** Whether {@code audio} is the sound playing right now. */
	public static boolean playing(AudioData audio) {
		Playback playback = current;
		return playback != null && !playback.stopped && (audio == null || playback.audio == audio);
	}

	/** Seconds into the playing sound, or -1. */
	public static double position() {
		Playback playback = current;
		return playback == null || playback.stopped ? -1 : playback.position();
	}
}
