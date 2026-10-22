package net.veloclient.velo.client.clips;

import net.veloclient.velo.VeloClient;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Clips Pro's sound: what you hear (the game <i>and</i> Discord, music... - the computer's output) plus, if you want,
 * your microphone, mixed into one 48 kHz stereo stream that {@link ProClipRecorder} encodes next to the video.
 *
 * <p>Where "what you hear" comes from: Windows - WASAPI loopback ({@link WasapiLoopback}, 26.x) or the "Stereo Mix"
 * input; Linux - the PulseAudio / PipeWire monitor of the default output ({@code parec}, else {@code pw-record});
 * macOS has no loopback without extra software (only the microphone works there). The mixer runs on the clock: every
 * 10 ms it writes exactly 10 ms of audio, padding a quiet source with silence, so sound never drifts from the picture.
 */
final class AudioCapture {

	static final int RATE = 48_000;
	private static final int CHANNELS = 2;
	/** More buffered than this from one source and the oldest is dropped (it fell behind). */
	private static final int MAX_BACKLOG_FRAMES = RATE / 5;

	/** A source pushing 48 kHz stereo 16-bit frames (interleaved shorts). */
	abstract static class Source {
		private final ArrayDeque<short[]> chunks = new ArrayDeque<>();
		private int offset;
		private int buffered;
		volatile float volume = 1f;
		volatile boolean dead;

		final void push(short[] frames) {
			synchronized (chunks) {
				chunks.addLast(frames);
				buffered += frames.length / CHANNELS;
				while (buffered > MAX_BACKLOG_FRAMES && chunks.size() > 1) {
					short[] dropped = chunks.removeFirst();
					buffered -= (dropped.length - offset) / CHANNELS;
					offset = 0;
				}
			}
		}

		/** Adds up to {@code frames} frames into {@code mix}; missing ones stay silent. */
		final void mixInto(int[] mix, int frames) {
			synchronized (chunks) {
				int want = frames * CHANNELS;
				int at = 0;
				while (at < want && !chunks.isEmpty()) {
					short[] chunk = chunks.peekFirst();
					int n = Math.min(want - at, chunk.length - offset);
					for (int i = 0; i < n; i++) {
						mix[at + i] += (int) (chunk[offset + i] * volume);
					}
					at += n;
					offset += n;
					if (offset >= chunk.length) {
						chunks.removeFirst();
						offset = 0;
					}
				}
				buffered = Math.max(0, buffered - at / CHANNELS);
			}
		}

		abstract void close();
	}

	private final List<Source> sources = new ArrayList<>();
	private volatile boolean running;
	private Thread mixer;
	/** When the first sample was written (System.nanoTime) - aligns the audio with the video when a clip is saved. */
	volatile long startNanos;

	/** Opens what's available: desktop sound if {@code desktop}, the microphone if {@code mic}. */
	static AudioCapture open(boolean desktop, float desktopVolume, boolean mic, float micVolume) {
		AudioCapture capture = new AudioCapture();
		if (desktop) {
			Source loopback = desktopSource();
			if (loopback != null) {
				loopback.volume = desktopVolume;
				capture.sources.add(loopback);
			} else {
				VeloClient.LOGGER.info("Clips Pro: no way to record desktop sound on this PC (Windows: enable Stereo Mix; macOS: needs a loopback driver)");
			}
		}
		if (mic) {
			Source microphone = JavaSoundSource.microphone();
			if (microphone != null) {
				microphone.volume = micVolume;
				capture.sources.add(microphone);
			}
		}
		return capture;
	}

	boolean hasSources() {
		return !sources.isEmpty();
	}

	/** Starts writing mixed PCM (s16le, 48 kHz, stereo) to {@code out} until {@link #close}. */
	void start(OutputStream out) {
		running = true;
		mixer = Thread.ofPlatform().daemon().name("velo-clips-audio").priority(Thread.MAX_PRIORITY - 1).start(() -> {
			startNanos = System.nanoTime();
			long written = 0;
			int[] mix = new int[RATE / 10 * CHANNELS];
			byte[] bytes = new byte[mix.length * 2];
			try {
				while (running) {
					long due = (System.nanoTime() - startNanos) * RATE / 1_000_000_000L;
					int frames = (int) Math.min(RATE / 10, due - written);
					if (frames <= 0) {
						Thread.sleep(5);
						continue;
					}
					java.util.Arrays.fill(mix, 0, frames * CHANNELS, 0);
					for (Source source : sources) {
						source.mixInto(mix, frames);
					}
					for (int i = 0; i < frames * CHANNELS; i++) {
						int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i]));
						bytes[i * 2] = (byte) v;
						bytes[i * 2 + 1] = (byte) (v >> 8);
					}
					out.write(bytes, 0, frames * CHANNELS * 2);
					written += frames;
				}
			} catch (IOException | InterruptedException ignored) {
				// Encoder gone or stopping.
			}
		});
	}

	void close() {
		running = false;
		for (Source source : sources) {
			try {
				source.close();
			} catch (RuntimeException ignored) {
				// Best effort.
			}
		}
		if (mixer != null) {
			mixer.interrupt();
		}
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean linux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}

	private static Source desktopSource() {
		if (windows()) {
			Source wasapi = WasapiLoopback.open();
			if (wasapi != null) {
				return wasapi;
			}
			return JavaSoundSource.named("stereo mix", "what u hear", "wave out mix", "cable output", "loopback");
		}
		if (linux()) {
			String sink = run("pactl", "get-default-sink");
			if (sink != null && !sink.isBlank()) {
				Source parec = ProcessSource.start("parec", "-d", sink.trim() + ".monitor", "--format=s16le", "--rate=" + RATE, "--channels=2",
						"--latency-msec=20");
				if (parec != null) {
					return parec;
				}
				return ProcessSource.start("pw-record", "--target", sink.trim(), "-P", "{ stream.capture.sink=true }", "--rate", String.valueOf(RATE),
						"--channels", "2", "--format", "s16", "-");
			}
		}
		return null;
	}

	private static String run(String... command) {
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes());
			return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** A helper program writing raw s16le stereo 48 kHz to its stdout (parec, pw-record). */
	static final class ProcessSource extends Source {
		private final Process process;

		private ProcessSource(Process process) {
			this.process = process;
			Thread.ofPlatform().daemon().name("velo-clips-audio-in").start(() -> {
				byte[] buffer = new byte[RATE / 50 * 4];
				try (InputStream in = process.getInputStream()) {
					int read;
					int carry = 0;
					while ((read = in.read(buffer, carry, buffer.length - carry)) > 0) {
						int total = carry + read;
						int whole = total / 4 * 4;
						short[] frames = new short[whole / 2];
						ByteBuffer.wrap(buffer, 0, whole).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frames);
						push(frames);
						carry = total - whole;
						System.arraycopy(buffer, whole, buffer, 0, carry);
					}
				} catch (IOException ignored) {
					// Closed.
				}
				dead = true;
			});
		}

		static Source start(String... command) {
			try {
				Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				Thread.sleep(150);
				if (!process.isAlive()) {
					return null;
				}
				return new ProcessSource(process);
			} catch (Exception e) {
				return null;
			}
		}

		@Override
		void close() {
			process.destroy();
		}
	}

	/** Java Sound input: the microphone, or a mixer by name (Windows "Stereo Mix"). Converted to 48 kHz stereo. */
	static final class JavaSoundSource extends Source {
		private final TargetDataLine line;
		private volatile boolean open = true;

		private JavaSoundSource(TargetDataLine line, AudioFormat format) {
			this.line = line;
			int channels = format.getChannels();
			float rate = format.getSampleRate();
			Thread.ofPlatform().daemon().name("velo-clips-audio-line").start(() -> {
				byte[] buffer = new byte[(int) (rate / 50) * channels * 2];
				double step = rate / RATE;
				double phase = 0;
				short last0 = 0;
				short last1 = 0;
				while (open) {
					int read = line.read(buffer, 0, buffer.length);
					if (read <= 0) {
						continue;
					}
					int inFrames = read / (channels * 2);
					short[] in = new short[inFrames * channels];
					ByteBuffer.wrap(buffer, 0, inFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(in);
					// To 48 kHz stereo (linear resampling; mono doubled).
					int outFrames = (int) ((inFrames - phase) / step);
					short[] out = new short[Math.max(0, outFrames) * 2];
					for (int i = 0; i < outFrames; i++) {
						double pos = phase + i * step;
						int i0 = (int) pos;
						double k = pos - i0;
						int i1 = Math.min(inFrames - 1, i0 + 1);
						short l0 = i0 < 0 ? last0 : in[i0 * channels];
						short l1 = in[i1 * channels];
						short r0 = channels > 1 ? (i0 < 0 ? last1 : in[i0 * channels + 1]) : l0;
						short r1 = channels > 1 ? in[i1 * channels + 1] : l1;
						out[i * 2] = (short) (l0 + (l1 - l0) * k);
						out[i * 2 + 1] = (short) (r0 + (r1 - r0) * k);
					}
					phase = phase + outFrames * step - inFrames;
					last0 = in[(inFrames - 1) * channels];
					last1 = channels > 1 ? in[(inFrames - 1) * channels + 1] : last0;
					push(out);
				}
			});
		}

		static Source microphone() {
			return open(null);
		}

		/** The first input whose mixer name contains one of {@code names}. */
		static Source named(String... names) {
			for (Mixer.Info info : AudioSystem.getMixerInfo()) {
				String n = (info.getName() + " " + info.getDescription()).toLowerCase(Locale.ROOT);
				for (String wanted : names) {
					if (n.contains(wanted)) {
						Source source = open(info);
						if (source != null) {
							return source;
						}
					}
				}
			}
			return null;
		}

		private static Source open(Mixer.Info mixer) {
			for (AudioFormat format : new AudioFormat[] {new AudioFormat(RATE, 16, 2, true, false), new AudioFormat(44_100, 16, 2, true, false),
					new AudioFormat(RATE, 16, 1, true, false), new AudioFormat(44_100, 16, 1, true, false)}) {
				try {
					DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
					TargetDataLine line = mixer == null ? (TargetDataLine) AudioSystem.getLine(info)
							: (TargetDataLine) AudioSystem.getMixer(mixer).getLine(info);
					line.open(format, (int) format.getSampleRate() / 10 * format.getFrameSize());
					line.start();
					return new JavaSoundSource(line, format);
				} catch (Exception | LinkageError ignored) {
					// Try the next format / no such line.
				}
			}
			return null;
		}

		@Override
		void close() {
			open = false;
			line.stop();
			line.close();
		}
	}
}
