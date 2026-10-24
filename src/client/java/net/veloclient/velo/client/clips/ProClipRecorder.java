package net.veloclient.velo.client.clips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.pvp.FightTracker;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Clips Pro (Velo+): a real video replay buffer - the last 2 to 5 minutes at up to 1080p60, H.264 on the graphics
 * card's own encoder where there is one. Frames go from the GPU ({@link FrameGrabber}) straight into an FFmpeg process,
 * which writes one-second pieces into a ring on disk ({@code velo-clips/.buffer/}, oldest overwritten), so memory use
 * stays flat however long the buffer is. Saving joins the last pieces without re-encoding (instant), then squeezes the
 * clip down in the background ({@link #compress}) so a minute of footage takes tens of MB, not hundreds.
 *
 * <p>Timing: FFmpeg gets exactly {@code fps} frames per second of real time - when the game renders slower, the
 * latest frame is repeated; faster, frames are skipped - so clips always play at real speed.
 */
public final class ProClipRecorder {

	private record Frame(ByteBuffer rgba, int width, int height, long nanos) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	/** Re-encoding runs while you play: half the cores at most, so the game keeps its frame rate. */
	private static final int BACKGROUND_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

	private static volatile boolean active;
	private static volatile int fps = 60;
	private static volatile int maxHeight = 1080;
	private static volatile int seconds = 180;
	private static volatile int bitrateKbps = 12000;
	private static volatile boolean compressSaved = true;
	private static volatile boolean desktopAudio = true;
	private static volatile boolean micAudio;
	private static volatile float desktopVolume = 1f;
	private static volatile float micVolume = 1f;
	private static Process audioProcess;
	private static AudioCapture audio;
	private static volatile String status = "";

	private static Process process;
	private static WritableByteChannel pipe;
	private static Thread writer;
	private static final BlockingQueue<Frame> QUEUE = new ArrayBlockingQueue<>(3);
	private static int encoderWidth;
	private static int encoderHeight;
	private static long startNanos;
	private static long framesWritten;
	private static long nextGrabNanos;
	private static volatile boolean starting;

	private ProClipRecorder() {
	}

	/** Sound: the computer's output (game, Discord...) and/or your microphone, each with its own volume. */
	public static void configureAudio(boolean desktop, float desktopVol, boolean mic, float micVol) {
		boolean changed = desktop != desktopAudio || mic != micAudio || desktopVol != desktopVolume || micVol != micVolume;
		desktopAudio = desktop;
		micAudio = mic;
		desktopVolume = desktopVol;
		micVolume = micVol;
		if (changed && active) {
			stopEncoder(); // the writer restarts both with the next frame
		}
	}

	public static void configure(boolean enabled, int framesPerSecond, int height, int bufferSeconds, int kbps, boolean compress) {
		boolean restart = enabled && active && (framesPerSecond != fps || height != maxHeight || bufferSeconds != seconds || kbps != bitrateKbps);
		fps = framesPerSecond;
		maxHeight = height;
		seconds = Math.max(30, Math.min(300, bufferSeconds));
		bitrateKbps = kbps;
		compressSaved = compress;
		if (!enabled) {
			active = false;
			stopEncoder();
			return;
		}
		if (restart) {
			stopEncoder();
		}
		if (!active) {
			active = true;
			FfmpegTool.ensure(problem -> status = problem == null ? "" : problem);
		}
	}

	public static boolean active() {
		return active;
	}

	/** What's going on, for the settings and the Clips screen ("" when all is well). */
	public static String status() {
		if (FfmpegTool.downloading()) {
			return "Downloading FFmpeg for Clips Pro...";
		}
		return status;
	}

	public static Path bufferDir() {
		return ClipRecorder.clipsDir().resolve(".buffer");
	}

	/** Seconds of footage in the ring right now. */
	public static double bufferedSeconds() {
		if (process == null || !process.isAlive()) {
			return 0;
		}
		return Math.min(seconds, (System.nanoTime() - startNanos) / 1e9);
	}

	// ---- Recording ----

	/** Every rendered frame (render thread): grabs one when the next one is due. */
	static void onFrameRendered() {
		if (!active || FfmpegTool.binary() == null || net.veloclient.velo.client.pvp.PvpCompat.localPlayer() == null) {
			return;
		}
		long now = System.nanoTime();
		long interval = 1_000_000_000L / fps;
		if (now < nextGrabNanos) {
			return;
		}
		nextGrabNanos = Math.max(nextGrabNanos + interval, now - interval);
		try {
			FrameGrabber.grab((rgba, width, height) -> {
				if (!QUEUE.offer(new Frame(rgba, width, height, System.nanoTime()))) {
					FrameGrabber.release(rgba); // encoder busy: the next frame is repeated to keep time
				}
			}, maxHeight);
		} catch (Throwable t) {
			VeloClient.LOGGER.warn("Clips Pro stopped: frame capture failed", t);
			active = false;
		}
		if (writer == null && !starting) {
			starting = true;
			writer = Thread.ofPlatform().daemon().name("velo-clips-pro").priority(Thread.NORM_PRIORITY - 1).start(ProClipRecorder::writeLoop);
		}
	}

	private static void writeLoop() {
		starting = false;
		try {
			while (active) {
				Frame frame = QUEUE.poll(500, TimeUnit.MILLISECONDS);
				if (frame == null) {
					continue;
				}
				try {
					if (process == null || !process.isAlive() || frame.width() != encoderWidth || frame.height() != encoderHeight) {
						startEncoder(frame.width(), frame.height(), frame.nanos());
					}
					long due = (frame.nanos() - startNanos) * fps / 1_000_000_000L + 1;
					int copies = (int) Math.min(8, Math.max(0, due - framesWritten));
					for (int i = 0; i < copies; i++) {
						ByteBuffer view = frame.rgba().duplicate();
						view.position(0).limit(frame.width() * frame.height() * 4);
						while (view.hasRemaining()) {
							pipe.write(view);
						}
						framesWritten++;
					}
				} catch (IOException e) {
					status = "The encoder stopped - restarting it";
					VeloClient.LOGGER.debug("Clips Pro encoder pipe failed", e);
					stopEncoder();
				} finally {
					FrameGrabber.release(frame.rgba());
				}
			}
		} catch (InterruptedException ignored) {
			// Shutting down.
		} finally {
			stopEncoder();
			QUEUE.forEach(f -> FrameGrabber.release(f.rgba()));
			QUEUE.clear();
			writer = null;
		}
	}

	private static synchronized void startEncoder(int width, int height, long nanos) throws IOException {
		stopEncoder();
		Path ffmpeg = FfmpegTool.binary();
		FfmpegTool.Encoder encoder = FfmpegTool.encoder(bitrateKbps);
		if (ffmpeg == null || encoder == null) {
			throw new IOException("FFmpeg isn't ready");
		}
		Path dir = bufferDir();
		Files.createDirectories(dir);
		clearBuffer();
		int outH = Math.min(height, maxHeight) & ~1;
		// NVENC takes the RGBA frames as they are and converts them to YUV on the GPU (same yuv420p
		// stream out) - the CPU conversion it replaces was most of ffmpeg's ~1 core at 1080p60. Only
		// when the frame already has the recorded, even size (26.2 scales it on the GPU first).
		boolean gpuConvert = "h264_nvenc".equals(encoder.name()) && outH == height && (width & 1) == 0;
		List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
				"-f", "rawvideo", "-pix_fmt", "rgba", "-video_size", width + "x" + height, "-framerate", String.valueOf(fps), "-i", "pipe:0"));
		if (gpuConvert) {
			// GPU rows come bottom-up.
			command.addAll(List.of("-vf", "vflip"));
		} else {
			// GPU rows come bottom-up; scale only when the window is bigger than the target.
			command.addAll(List.of("-vf", "vflip" + (outH < height ? ",scale=-2:" + outH + ":flags=bilinear" : ",scale=trunc(iw/2)*2:trunc(ih/2)*2"),
					"-pix_fmt", "yuv420p"));
		}
		command.addAll(encoder.args());
		int ring = seconds + 4;
		command.addAll(List.of("-g", String.valueOf(fps), "-force_key_frames", "expr:gte(t,n_forced*1)",
				"-f", "segment", "-segment_time", "1", "-segment_wrap", String.valueOf(ring), "-segment_format", "mpegts", "-reset_timestamps", "1",
				"-segment_list", dir.resolve("video.csv").toString(), "-segment_list_type", "csv", "-segment_list_size", String.valueOf(ring),
				dir.resolve("v%03d.ts").toString()));
		process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("encoder.log").toFile()).start();
		OutputStream stdin = process.getOutputStream();
		pipe = Channels.newChannel(stdin);
		encoderWidth = width;
		encoderHeight = height;
		startNanos = nanos;
		framesWritten = 0;
		status = "";
		startAudio(ffmpeg, dir, ring);
		VeloClient.LOGGER.info("Clips Pro recording {}x{} -> {}p{} with {}{}", width, height, outH, fps, encoder.label(),
				audio != null ? " + sound" : "");
	}

	/** Its own ring of one-second AAC pieces, lined up with the picture by time when a clip is saved. */
	private static void startAudio(Path ffmpeg, Path dir, int ring) {
		if (!desktopAudio && !micAudio) {
			return;
		}
		AudioCapture capture = AudioCapture.open(desktopAudio, desktopVolume, micAudio, micVolume);
		if (!capture.hasSources()) {
			capture.close();
			status = desktopAudio ? "No sound source found - clips are silent (Windows: enable Stereo Mix, or turn on the microphone)" : "";
			return;
		}
		try {
			audioProcess = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
					"-f", "s16le", "-ar", String.valueOf(AudioCapture.RATE), "-ac", "2", "-i", "pipe:0", "-c:a", "aac", "-b:a", "128k",
					"-f", "segment", "-segment_time", "1", "-segment_wrap", String.valueOf(ring), "-segment_format", "mpegts", "-reset_timestamps", "1",
					"-segment_list", dir.resolve("audio.csv").toString(), "-segment_list_type", "csv", "-segment_list_size", String.valueOf(ring),
					dir.resolve("a%03d.ts").toString())
					.redirectErrorStream(true).redirectOutput(dir.resolve("audio.log").toFile()).start();
			capture.start(audioProcess.getOutputStream());
			audio = capture;
		} catch (IOException e) {
			capture.close();
			VeloClient.LOGGER.warn("Clips Pro: sound recording failed to start", e);
		}
	}

	private static synchronized void stopEncoder() {
		if (audio != null) {
			audio.close();
			audio = null;
		}
		if (audioProcess != null) {
			try {
				audioProcess.getOutputStream().close();
				if (!audioProcess.waitFor(3, TimeUnit.SECONDS)) {
					audioProcess.destroyForcibly();
				}
			} catch (IOException | InterruptedException e) {
				audioProcess.destroyForcibly();
			}
			audioProcess = null;
		}
		Process p = process;
		process = null;
		if (pipe != null) {
			try {
				pipe.close();
			} catch (IOException ignored) {
				// Already gone.
			}
			pipe = null;
		}
		if (p != null) {
			try {
				if (!p.waitFor(3, TimeUnit.SECONDS)) {
					p.destroyForcibly();
				}
			} catch (InterruptedException e) {
				p.destroyForcibly();
			}
		}
	}

	private static void clearBuffer() {
		try (Stream<Path> files = Files.list(bufferDir())) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".ts") || p.getFileName().toString().endsWith(".csv")).toList()) {
				Files.deleteIfExists(f);
			}
		} catch (IOException ignored) {
			// Nothing there yet.
		}
	}

	// ---- Saving ----

	/** One finished piece of a ring: its file and where it starts / ends in its stream (seconds). */
	private record Piece(Path file, double start, double end) {
	}

	/** A ring's finished pieces, oldest first (from FFmpeg's CSV list; read twice if FFmpeg was rewriting it). */
	private static List<Piece> pieces(String list) {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				List<Piece> out = new ArrayList<>();
				for (String line : Files.readAllLines(bufferDir().resolve(list), StandardCharsets.UTF_8)) {
					String[] parts = line.split(",");
					if (parts.length >= 3) {
						Path file = bufferDir().resolve(parts[0].replace("\"", ""));
						if (Files.exists(file)) {
							out.add(new Piece(file, Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
						}
					}
				}
				return out;
			} catch (Exception e) {
				try {
					Thread.sleep(30);
				} catch (InterruptedException ignored) {
					return List.of();
				}
			}
		}
		return List.of();
	}

	private static Path concatList(String name, List<Piece> pieces) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (Piece piece : pieces) {
			sb.append("file '").append(piece.file().toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
		}
		Path list = bufferDir().resolve(name);
		Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
		return list;
	}

	/**
	 * Saves the last {@code lastSeconds} (0 = the whole buffer) as {@code velo-clips/clip-<time>.mp4} with a thumbnail and
	 * the same JSON sidecar as normal clips. {@code done} gets the file on the render thread (null if nothing to save).
	 */
	public static void save(String trigger, FightTracker.Recap recap, double lastSeconds, Consumer<Path> done) {
		String server = net.veloclient.velo.client.util.ClientCompat.currentServerAddress();
		Thread.ofVirtual().name("velo-clips-pro-save").start(() -> {
			Path out = null;
			try {
				List<Piece> all = pieces("video.csv");
				int take = lastSeconds > 0 ? (int) Math.ceil(lastSeconds) + 1 : all.size();
				List<Piece> pick = all.subList(Math.max(0, all.size() - take), all.size());
				long videoStart = startNanos;
				AudioCapture capture = audio;
				long audioStart = capture == null ? 0 : capture.startNanos;
				if (pick.size() >= 2) {
					String name = "clip-" + NAME.format(LocalDateTime.now());
					out = ClipRecorder.clipsDir().resolve(name + ".mp4");
					List<String> command = new ArrayList<>(List.of(FfmpegTool.binary().toString(), "-hide_banner", "-loglevel", "error", "-y",
							"-f", "concat", "-safe", "0", "-i", concatList("video.txt", pick).toString()));
					// The sound pieces that overlap the picture, shifted so both start at the same moment.
					double from = videoStart / 1e9 + pick.getFirst().start();
					double to = videoStart / 1e9 + pick.getLast().end();
					List<Piece> sound = capture == null ? List.of() : pieces("audio.csv").stream()
							.filter(a -> audioStart / 1e9 + a.end() > from && audioStart / 1e9 + a.start() < to).toList();
					if (!sound.isEmpty()) {
						double offset = audioStart / 1e9 + sound.getFirst().start() - from;
						command.addAll(List.of("-itsoffset", String.format(java.util.Locale.ROOT, "%.3f", offset),
								"-f", "concat", "-safe", "0", "-i", concatList("audio.txt", sound).toString(), "-map", "0:v", "-map", "1:a", "-shortest",
								"-bsf:a", "aac_adtstoasc"));
					}
					command.addAll(List.of("-c", "copy", "-movflags", "+faststart", out.toString()));
					try {
						run(command, 120);
					} catch (IOException soundTrouble) {
						if (sound.isEmpty() || !Files.exists(out) || Files.size(out) == 0) {
							throw soundTrouble;
						}
						// FFmpeg complained about a cut-off sound piece but wrote the clip - keep it.
						VeloClient.LOGGER.debug("Clips Pro: sound join warning", soundTrouble);
					}
					double length = probeSeconds(out, pick.size());
					thumbnail(out, length, ClipRecorder.clipsDir().resolve(name + ".jpg"));
					JsonObject meta = new JsonObject();
					meta.addProperty("createdAt", System.currentTimeMillis());
					meta.addProperty("seconds", Math.round(length * 10) / 10.0);
					meta.addProperty("fps", fps);
					meta.addProperty("height", Math.min(encoderHeight, maxHeight));
					meta.addProperty("width", Math.round(encoderWidth * (Math.min(encoderHeight, maxHeight) / (float) Math.max(1, encoderHeight))) & ~1);
					meta.addProperty("trigger", trigger);
					meta.addProperty("pro", true);
					meta.addProperty("audio", !sound.isEmpty());
					if (server != null) {
						meta.addProperty("server", server);
					}
					if (recap != null) {
						meta.add("recap", GSON.toJsonTree(recap));
					}
					Files.writeString(ClipRecorder.clipsDir().resolve(name + ".json"), GSON.toJson(meta), StandardCharsets.UTF_8);
					if (compressSaved) {
						Path saved = out;
						Thread.ofVirtual().name("velo-clips-compress").start(() -> compressInPlace(saved));
					}
				}
			} catch (Exception e) {
				VeloClient.LOGGER.warn("Saving a Clips Pro clip failed", e);
				out = null;
			}
			Path result = out;
			MinecraftClient.getInstance().execute(() -> done.accept(result));
		});
	}

	/** Saved clips shrink in the background (a smaller copy replaces the original only when it's done and smaller). */
	private static void compressInPlace(Path clip) {
		Path smaller = clip.resolveSibling(clip.getFileName().toString().replace(".mp4", ".small.tmp.mp4"));
		try {
			compress(clip, smaller, 28, 4_000, 0);
			if (Files.size(smaller) < Files.size(clip)) {
				Files.move(smaller, clip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Couldn't compress {}", clip, e);
		} finally {
			try {
				Files.deleteIfExists(smaller);
			} catch (IOException ignored) {
				// Left for the next start.
			}
		}
	}

	/**
	 * For keeping on your PC: H.264 (plays in every video player) at a constant quality that still looks clean, x264
	 * with Minecraft-friendly tuning and a light denoise, a bitrate cap, AAC sound - a fraction of the recording's size.
	 */
	public static void compress(Path in, Path out, int crf, int maxKbps, double lastSeconds) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of(FfmpegTool.binary().toString(), "-hide_banner", "-loglevel", "error", "-y"));
		if (lastSeconds > 0) {
			command.addAll(List.of("-sseof", "-" + lastSeconds));
		}
		command.addAll(List.of("-i", in.toString(), "-vf", "hqdn3d=2:1.5:3:3,scale=-2:'min(1080,ih)':flags=lanczos", "-c:v", "libx264", "-preset", "medium",
				"-tune", "animation", "-crf", String.valueOf(crf), "-maxrate", maxKbps + "k", "-bufsize", maxKbps * 2 + "k", "-pix_fmt", "yuv420p",
				"-profile:v", "high", "-threads", String.valueOf(BACKGROUND_THREADS), "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart",
				out.toString()));
		run(command, 1800);
	}

	/**
	 * For share links, where every byte is stored on the Velo server: as small as it gets while it stays watchable.
	 * AV1 (SVT-AV1, quality-based but capped at about 1.5 Mbit/s, long keyframe distance, film grain off, a light denoise so
	 * noise doesn't eat the bits) at most 720p60, with Opus sound at 48 kbit/s: at most ~11 MB a minute even for fast
	 * PvP over detailed ground (measured on real footage), calmer clips much less. AV1 plays in Chrome, Edge, Firefox,
	 * Android and Discord. Without an AV1 encoder in this FFmpeg: H.264 with the same cap instead.
	 */
	public static void compressForSharing(Path in, Path out, double lastSeconds) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of(FfmpegTool.binary().toString(), "-hide_banner", "-loglevel", "error", "-y"));
		if (lastSeconds > 0) {
			command.addAll(List.of("-sseof", "-" + lastSeconds));
		}
		command.addAll(List.of("-i", in.toString(), "-vf", "hqdn3d=3:2:4:4,scale=-2:'min(720,ih)':flags=lanczos,fps='min(60,source_fps)'",
				"-pix_fmt", "yuv420p"));
		if (FfmpegTool.hasEncoder("libsvtav1")) {
			command.addAll(List.of("-c:v", "libsvtav1", "-preset", "8", "-crf", "50", "-g", "300", "-svtav1-params",
					"tune=0:enable-overlays=1:scd=1:film-grain=0:mbr=1000:lp=" + BACKGROUND_THREADS));
		} else {
			command.addAll(List.of("-c:v", "libx264", "-preset", "slow", "-tune", "animation", "-crf", "32", "-maxrate", "1500k", "-bufsize", "3000k",
					"-profile:v", "high", "-threads", String.valueOf(BACKGROUND_THREADS)));
		}
		if (FfmpegTool.hasEncoder("libopus")) {
			command.addAll(List.of("-c:a", "libopus", "-b:a", "48k", "-ac", "2"));
		} else {
			command.addAll(List.of("-c:a", "aac", "-b:a", "64k"));
		}
		command.addAll(List.of("-movflags", "+faststart", out.toString()));
		run(command, 3600);
	}

	private static void thumbnail(Path clip, double length, Path jpg) {
		try {
			run(List.of(FfmpegTool.binary().toString(), "-hide_banner", "-loglevel", "error", "-y", "-ss", String.valueOf(Math.max(0, length * 2 / 3)),
					"-i", clip.toString(), "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", jpg.toString()), 30);
		} catch (Exception e) {
			VeloClient.LOGGER.debug("No thumbnail for {}", clip, e);
		}
	}

	/** The clip's length (from FFmpeg's own read of it), or the number of one-second pieces. */
	private static double probeSeconds(Path clip, int pieces) {
		try {
			Process p = new ProcessBuilder(FfmpegTool.binary().toString(), "-hide_banner", "-i", clip.toString()).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(10, TimeUnit.SECONDS);
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)").matcher(out);
			if (m.find()) {
				return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
			}
		} catch (Exception ignored) {
			// Fall back.
		}
		return pieces;
	}

	private static void run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("FFmpeg took too long");
		}
		if (p.exitValue() != 0) {
			throw new IOException("FFmpeg failed: " + output.lines().reduce((a, b) -> b).orElse("exit " + p.exitValue()));
		}
	}
}
