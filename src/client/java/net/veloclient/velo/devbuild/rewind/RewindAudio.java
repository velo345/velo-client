package net.veloclient.velo.devbuild.rewind;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Velo Rewind's audio while recording (plain Java): voice chat from Simple Voice Chat ({@link RewindVoicePlugin} hands it
 * over - each player's voice as its own track), your microphone (opt-in: what Simple Voice Chat sends, or the system's
 * default microphone without it) and system audio such as Discord (opt-in: what your speakers play). Everything goes to
 * {@link #sink}, which the recorder sets while it records. Audio is 48 kHz 16-bit PCM.
 *
 * <p>System audio ("everything you hear") is also how a video export gets its sound: see {@link #loopback}.
 */
public final class RewindAudio {

	public static final int RATE = 48_000;

	/** Takes audio blocks: track ("voice-&lt;uuid&gt;", "voice-at", "mic", "system"), PCM, channels, rate, position (NaN = none). */
	public interface Sink {
		void audio(String track, short[] pcm, int channels, int rate, float x, float y, float z);
	}

	public static volatile Sink sink;
	public static volatile boolean recordVoice = true;
	public static volatile boolean recordMic;
	public static volatile boolean recordSystem;
	/** The system audio ("everything you hear") source to record from; "" = find it automatically. */
	public static volatile String systemDevice = "";
	/** The microphone to record from (without Simple Voice Chat); "" = the system's default. */
	public static volatile String micDevice = "";
	/** Simple Voice Chat's plugin is running (so the mic comes from it). */
	static volatile boolean voicechat;

	private static Process systemProcess;
	private static Thread micThread;
	private static volatile boolean micRunning;

	private RewindAudio() {
	}

	// ---- from Simple Voice Chat (any thread) ----

	static void voice(UUID entity, double[] pos, short[] pcm) {
		Sink s = sink;
		if (s == null || !recordVoice || pcm == null || pcm.length == 0) {
			return;
		}
		if (entity != null) {
			s.audio("voice-" + entity, pcm, 1, RATE, Float.NaN, Float.NaN, Float.NaN);
		} else if (pos != null) {
			s.audio("voice-at", pcm, 1, RATE, (float) pos[0], (float) pos[1], (float) pos[2]);
		} else {
			s.audio("voice-static", pcm, 1, RATE, Float.NaN, Float.NaN, Float.NaN);
		}
	}

	static void ownVoice(short[] pcm) {
		Sink s = sink;
		if (s != null && recordMic && pcm != null && pcm.length > 0) {
			s.audio("mic", pcm, 1, RATE, Float.NaN, Float.NaN, Float.NaN);
		}
	}

	// ---- recording starts / stops (game thread) ----

	public static synchronized void started() {
		if (recordSystem && systemProcess == null) {
			systemProcess = loopback(List.of("-f", "s16le", "-ac", "2", "-ar", String.valueOf(RATE), "pipe:1"), true);
			if (systemProcess != null) {
				Process proc = systemProcess;
				Thread.ofPlatform().daemon().name("velo-rewind-system-audio").start(() -> pump(proc.getInputStream(), 2,
						pcm -> {
							Sink s = sink;
							if (s != null) {
								s.audio("system", pcm, 2, RATE, Float.NaN, Float.NaN, Float.NaN);
							}
						}));
			}
		}
		if (recordMic && !voicechat && micThread == null) {
			micRunning = true;
			micThread = Thread.ofPlatform().daemon().name("velo-rewind-mic").start(RewindAudio::micLoop);
		}
	}

	public static synchronized void stopped() {
		if (systemProcess != null) {
			systemProcess.destroy();
			systemProcess = null;
		}
		micRunning = false;
		micThread = null;
	}

	// The default microphone through Java Sound (only without Simple Voice Chat).
	private static void micLoop() {
		javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(RATE, 16, 1, true, false);
		try (javax.sound.sampled.TargetDataLine line = micLine(format)) {
			line.open(format, RATE / 5 * 2);
			line.start();
			byte[] buf = new byte[960 * 2];
			while (micRunning) {
				int n = line.read(buf, 0, buf.length);
				if (n > 0) {
					short[] pcm = new short[n / 2];
					ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
					ownVoice(pcm);
				}
			}
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("Velo Rewind").warn("Velo Rewind: no microphone to record", e);
		}
	}

	private static javax.sound.sampled.TargetDataLine micLine(javax.sound.sampled.AudioFormat format) throws Exception {
		String want = micDevice;
		if (want != null && !want.isBlank()) {
			for (var info : javax.sound.sampled.AudioSystem.getMixerInfo()) {
				if (info.getName().equals(want)) {
					return javax.sound.sampled.AudioSystem.getTargetDataLine(format, info);
				}
			}
		}
		return javax.sound.sampled.AudioSystem.getTargetDataLine(format);
	}

	// ---- devices (the settings and the AI API list them) ----

	private static List<String> systemCache;
	private static long systemCacheAt;

	/** Sources "everything you hear" can be recorded from: PulseAudio / PipeWire sources (monitors first), DirectShow audio devices. */
	public static synchronized List<String> systemDevices() {
		if (systemCache != null && System.currentTimeMillis() - systemCacheAt < 60_000) {
			return systemCache;
		}
		List<String> out = new ArrayList<>();
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		try {
			if (os.contains("linux")) {
				Process p = new ProcessBuilder("pactl", "list", "short", "sources").redirectErrorStream(true).start();
				for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) {
					String[] cols = line.split("\t");
					if (cols.length > 1) {
						out.add(cols[1]);
					}
				}
				out.sort((a, b) -> Boolean.compare(!a.endsWith(".monitor"), !b.endsWith(".monitor")));
			} else if (os.contains("win")) {
				java.nio.file.Path ffmpeg = net.veloclient.velo.client.clips.FfmpegTool.binary();
				if (ffmpeg != null) {
					Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
							.redirectErrorStream(true).start();
					for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) {
						int a = line.indexOf('"');
						int b = line.lastIndexOf('"');
						if (a >= 0 && b > a && line.contains("(audio)")) {
							out.add(line.substring(a + 1, b));
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
		systemCache = out;
		systemCacheAt = System.currentTimeMillis();
		return out;
	}

	/** Microphones Java can record from. */
	public static List<String> micDevices() {
		List<String> out = new ArrayList<>();
		javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(RATE, 16, 1, true, false);
		for (var info : javax.sound.sampled.AudioSystem.getMixerInfo()) {
			try {
				if (javax.sound.sampled.AudioSystem.getMixer(info).isLineSupported(new javax.sound.sampled.DataLine.Info(
						javax.sound.sampled.TargetDataLine.class, format))) {
					out.add(info.getName());
				}
			} catch (Exception ignored) {
			}
		}
		return out;
	}

	// Reads raw little-endian PCM in 20 ms blocks.
	static void pump(InputStream in, int channels, Consumer<short[]> out) {
		byte[] buf = new byte[960 * channels * 2];
		try (in) {
			while (true) {
				int got = 0;
				while (got < buf.length) {
					int n = in.read(buf, got, buf.length - got);
					if (n < 0) {
						return;
					}
					got += n;
				}
				short[] pcm = new short[buf.length / 2];
				ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
				out.accept(pcm);
			}
		} catch (Exception ignored) {
			// process ended
		}
	}

	// ---- "everything you hear" ----

	/**
	 * Starts capturing what the speakers play: on Linux from the default output's monitor (PulseAudio / PipeWire, through
	 * {@code parec} or FFmpeg), on Windows from "Stereo Mix" (or a similar loopback device) through FFmpeg. {@code output}:
	 * FFmpeg output arguments (format + target). {@code rawToStdout}: the output is raw 48 kHz stereo s16le on stdout
	 * (parec can do that directly). Null when this system has no way to do it.
	 */
	public static Process loopback(List<String> output, boolean rawToStdout) {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		try {
			if (os.contains("linux")) {
				String monitor = defaultMonitor();
				if (rawToStdout && onPath("parec")) {
					return new ProcessBuilder("parec", "-d", monitor, "--format=s16le", "--rate=" + RATE, "--channels=2", "--latency-msec=40")
							.redirectError(ProcessBuilder.Redirect.DISCARD).start();
				}
				java.nio.file.Path ffmpeg = net.veloclient.velo.client.clips.FfmpegTool.binary();
				if (ffmpeg != null) {
					List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-f", "pulse",
							"-sample_rate", String.valueOf(RATE), "-channels", "2", "-i", monitor));
					cmd.addAll(output);
					return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				}
				if (onPath("parec")) {
					return null; // parec only gives raw audio; the caller asked for a file
				}
			} else if (os.contains("win")) {
				java.nio.file.Path ffmpeg = net.veloclient.velo.client.clips.FfmpegTool.binary();
				String device = ffmpeg == null ? null : windowsLoopback(ffmpeg);
				if (device != null) {
					List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-f", "dshow",
							"-i", "audio=" + device));
					cmd.addAll(output);
					return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				}
			}
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("Velo Rewind").warn("Velo Rewind: can't capture system audio", e);
		}
		return null;
	}

	/** Whether {@link #loopback} can work here (cheap guess, for the UI). */
	public static boolean loopbackAvailable() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (os.contains("linux")) {
			return onPath("parec") || net.veloclient.velo.client.clips.FfmpegTool.binary() != null;
		}
		if (!os.contains("win") || net.veloclient.velo.client.clips.FfmpegTool.binary() == null) {
			return false;
		}
		try {
			return windowsLoopback(net.veloclient.velo.client.clips.FfmpegTool.binary()) != null; // a Stereo Mix / cable / chosen device
		} catch (Exception e) {
			return false;
		}
	}

	private static String defaultMonitor() {
		if (systemDevice != null && !systemDevice.isBlank()) {
			return systemDevice;
		}
		try {
			Process p = new ProcessBuilder("pactl", "get-default-sink").redirectErrorStream(true).start();
			String sinkName = new String(p.getInputStream().readAllBytes()).trim();
			if (p.waitFor() == 0 && !sinkName.isEmpty() && !sinkName.contains(" ")) {
				return sinkName + ".monitor";
			}
		} catch (Exception ignored) {
		}
		return "@DEFAULT_MONITOR@";
	}

	private static String windowsDevice;

	private static String windowsLoopback(java.nio.file.Path ffmpeg) throws Exception {
		if (systemDevice != null && !systemDevice.isBlank()) {
			return systemDevice;
		}
		if (windowsDevice != null) {
			return windowsDevice.isEmpty() ? null : windowsDevice;
		}
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		p.waitFor();
		windowsDevice = "";
		for (String line : out.split("\n")) {
			int a = line.indexOf('"');
			int b = line.lastIndexOf('"');
			if (a < 0 || b <= a || !line.contains("(audio)")) {
				continue;
			}
			String name = line.substring(a + 1, b);
			String l = name.toLowerCase(java.util.Locale.ROOT);
			if (l.contains("stereo mix") || l.contains("stereomix") || l.contains("what u hear") || l.contains("wave out") || l.contains("loopback")
					|| l.contains("cable output") || l.contains("stereomischung")) {
				windowsDevice = name;
				break;
			}
		}
		return windowsDevice.isEmpty() ? null : windowsDevice;
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, tool))) {
				return true;
			}
		}
		return false;
	}
}
