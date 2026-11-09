package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind's video export: plays the selected clip from its start with the HUD hidden, takes every frame the game draws
 * (at the chosen frame rate) and pipes it into FFmpeg, cut to the chosen format (YouTube 16:9, TikTok 9:16, square) and
 * scaled to its resolution. MP4 files land in {@code velo-rewind/exports}.
 */
//? if <26.2 {
public final class RewindExport {

	private RewindExport() {
	}

	public static void beforeGui() {
	}
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.clips.FfmpegTool;
import net.veloclient.velo.client.clips.FrameCapture;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class RewindExport {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Rewind");

	// Output formats: name, aspect (w/h), resolutions (height of the short side ... as width x height).
	record Format(String name, double aspect, int[][] sizes) {
	}

	static final List<Format> FORMATS = List.of(
			new Format("YouTube 16:9", 16 / 9.0, new int[][] {{1280, 720}, {1920, 1080}, {2560, 1440}}),
			new Format("TikTok 9:16", 9 / 16.0, new int[][] {{720, 1280}, {1080, 1920}}),
			new Format("Square 1:1", 1.0, new int[][] {{720, 720}, {1080, 1080}}),
			new Format("Window", 0, new int[][] {{0, 0}}));

	enum State { IDLE, PREPARING, RENDERING, FINISHING, DONE, FAILED }

	// Sound for the video: what the speakers play while it renders (game sounds, voices), only the recorded voice tracks, none.
	static final List<String> AUDIO_MODES = List.of("Everything you hear", "Voice tracks", "No sound");
	static volatile String audioMode = AUDIO_MODES.get(0);
	private static Process audioProcess;
	private static Path audioFile;
	private static java.io.OutputStream audioOut;
	private static boolean audioStarted;
	private static volatile String audioUsed;

	static volatile State state = State.IDLE;
	static volatile double progress;
	static volatile String message = "";
	static volatile Path output;
	static volatile Format format;
	static volatile int sizeIndex;
	static volatile int fpsShown;

	private static Process process;
	private static WritableByteChannel pipe;
	private static Thread writer;
	private static final ArrayBlockingQueue<Object[]> QUEUE = new ArrayBlockingQueue<>(6);
	private static int fps;
	private static int outW;
	private static int outH;
	private static double aspect;
	private static long nextFrameNanos;
	private static int frames;
	private static boolean hudWasHidden;

	private RewindExport() {
	}

	// RewindPreGuiMixin: called every frame right before the GUI is drawn. While it works, frames are taken there (world
	// only) and the editor stays open with the progress; otherwise (mixin missing) after the whole frame with the screen closed.
	private static volatile long beforeGuiSeen;

	public static void beforeGui() {
		beforeGuiSeen = System.nanoTime();
		if (state == State.RENDERING && !(live && liveHud)) {
			frame();
		}
	}

	private static boolean preGui() {
		return System.nanoTime() - beforeGuiSeen < 500_000_000L;
	}

	// ClipRecorder.frameHook: only the fallback
	private static void frameAfterGui() {
		if (!preGui() || live && liveHud) {
			frame(); // (a live clip with the HUD wants the whole frame)
		}
	}

	// A live clip (AI API: POST /clip): the normal game for a few seconds instead of a replay clip.
	private static volatile boolean live;
	private static long liveStart;
	private static long liveNanos;
	private static boolean liveHud;

	static void startLive(Format format, int sizeIndex, int frameRate, double seconds, boolean hud) {
		Minecraft mc = Minecraft.getInstance();
		unstick();
		if (busy() || mc.player == null) {
			return;
		}
		lastActivity = System.nanoTime();
		fps = frameRate;
		RewindExport.format = format;
		RewindExport.sizeIndex = sizeIndex;
		fpsShown = frameRate;
		aspect = format.aspect();
		int[] size = format.sizes()[Math.min(sizeIndex, format.sizes().length - 1)];
		outW = size[0];
		outH = size[1];
		state = State.PREPARING;
		progress = 0;
		message = "Getting FFmpeg ready...";
		FfmpegTool.ensure(problem -> mc.execute(() -> {
			if (problem != null && FfmpegTool.binary() == null) {
				state = State.FAILED;
				message = "FFmpeg isn't available: " + problem;
				return;
			}
			try {
				Path dir = RewindLibraryScreen.folder().resolve("clips");
				Files.createDirectories(dir);
				String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
				output = dir.resolve("clip " + stamp + ".mp4");
				live = true;
				liveHud = hud;
				liveNanos = (long) (Math.max(1, Math.min(300, seconds)) * 1e9);
				liveStart = System.nanoTime();
				hudWasHidden = mc.gui.hud.isHidden();
				setHud(mc, !hud);
				frames = 0;
				nextFrameNanos = 0;
				audioStarted = false;
				audioUsed = null;
				state = State.RENDERING;
				message = "Recording";
				ClipRecorder.frameHook = RewindExport::frameAfterGui;
			} catch (Exception e) {
				fail("Couldn't start: " + e.getMessage(), e);
			}
		}));
	}

	static boolean busy() {
		return state == State.PREPARING || state == State.RENDERING || state == State.FINISHING;
	}

	// When the export last made progress: an export that stopped moving (the replay closed under it, FFmpeg never
	// answered) must not block every later one.
	private static volatile long lastActivity;

	private static void unstick() {
		if ((state == State.PREPARING || state == State.RENDERING) && System.nanoTime() - lastActivity > 15_000_000_000L) {
			LOG.warn("Velo Rewind export: the last export stopped moving ({}) - starting over", state);
			ClipRecorder.frameHook = null;
			stopAudio();
			live = false;
			state = State.IDLE;
		}
	}

	static void start(Format format, int sizeIndex, int frameRate) {
		RewindPlayer p = RewindPlayer.current();
		unstick();
		if (p == null || busy()) {
			LOG.warn("Velo Rewind export: not starting ({})", p == null ? "no replay open" : "another export is running: " + state);
			return;
		}
		LOG.info("Velo Rewind export: {} {} at {} fps, sound: {}", format.name(), sizeIndex, frameRate, audioMode);
		lastActivity = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		fps = frameRate;
		RewindExport.format = format;
		RewindExport.sizeIndex = sizeIndex;
		fpsShown = frameRate;
		aspect = format.aspect();
		int[] size = format.sizes()[Math.min(sizeIndex, format.sizes().length - 1)];
		outW = size[0];
		outH = size[1];
		state = State.PREPARING;
		progress = 0;
		message = "Getting FFmpeg ready...";
		FfmpegTool.ensure(problem -> mc.execute(() -> {
			if (problem != null && FfmpegTool.binary() == null) {
				state = State.FAILED;
				message = "FFmpeg isn't available: " + problem;
				return;
			}
			try {
				Path dir = RewindLibraryScreen.folder().resolve("exports");
				Files.createDirectories(dir);
				String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
				output = dir.resolve(p.name() + " - " + p.edits.clip().name + " " + stamp + ".mp4");
				// from the clip's start, hidden HUD, editor closed, normal speed
				p.seekClip(0);
				if (p.paused()) {
					p.togglePause();
				}
				hudWasHidden = mc.gui.hud.isHidden();
				setHud(mc, true);
				if (!preGui()) {
					mc.setScreen(null); // frames include the GUI: nothing may be open
				}
				frames = 0;
				nextFrameNanos = 0;
				audioStarted = false;
				audioUsed = null;
				state = State.RENDERING;
				message = "Rendering";
				ClipRecorder.frameHook = RewindExport::frameAfterGui;
			} catch (Exception e) {
				fail("Couldn't start: " + e.getMessage(), e);
			}
		}));
	}

	static void setHud(Minecraft mc, boolean hidden) {
		try {
			var f = mc.gui.hud.getClass().getDeclaredField("isHidden");
			f.setAccessible(true);
			f.setBoolean(mc.gui.hud, hidden);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	// every rendered frame while exporting
	private static void frame() {
		RewindPlayer p = RewindPlayer.current();
		Minecraft mc = Minecraft.getInstance();
		if (state != State.RENDERING) {
			return;
		}
		if (live) {
			long elapsed = System.nanoTime() - liveStart;
			progress = Math.min(1, elapsed / (double) liveNanos);
			if (elapsed >= liveNanos || mc.player == null) {
				finish();
				return;
			}
		} else {
			if (p == null) {
				fail("The replay closed while exporting", null);
				return;
			}
			RewindEdits.Clip clip = p.edits.clip();
			progress = clip.length == 0 ? 1 : p.clipPosition() / (double) clip.length;
			if (p.clipPosition() >= clip.length || p.paused() && frames > 0) {
				finish();
				return;
			}
		}
		long now = System.nanoTime();
		long interval = 1_000_000_000L / fps;
		if (now < nextFrameNanos) {
			return;
		}
		nextFrameNanos = nextFrameNanos == 0 ? now + interval : Math.max(nextFrameNanos + interval, now - interval);
		lastActivity = now;
		if (!audioStarted) {
			audioStarted = true;
			startAudio();
		}
		FrameCapture.grab((rgba, width, height) -> {
			if (!QUEUE.offer(new Object[] {rgba, width, height})) {
				FrameCapture.release(rgba);
			}
		}, 2160);
		if (writer == null) {
			writer = Thread.ofPlatform().daemon().name("velo-rewind-export").start(RewindExport::writeLoop);
		}
	}

	private static void writeLoop() {
		try {
			while (state == State.RENDERING || !QUEUE.isEmpty()) {
				Object[] f = QUEUE.poll(300, TimeUnit.MILLISECONDS);
				if (f == null) {
					continue;
				}
				ByteBuffer rgba = (ByteBuffer) f[0];
				int w = (int) f[1];
				int h = (int) f[2];
				try {
					if (process == null) {
						startEncoder(w, h);
					}
					rgba.position(0).limit(w * h * 4);
					while (rgba.hasRemaining()) {
						pipe.write(rgba);
					}
					frames++;
				} finally {
					FrameCapture.release(rgba);
				}
			}
		} catch (Exception e) {
			fail("Rendering failed: " + e.getMessage(), e);
		}
	}

	private static void startEncoder(int w, int h) throws Exception {
		// cut the window to the format, centred, then scale to the resolution
		int cw = w;
		int ch = h;
		if (aspect > 0) {
			if (w / (double) h > aspect) {
				cw = (int) Math.round(h * aspect) & ~1;
			} else {
				ch = (int) Math.round(w / aspect) & ~1;
			}
		}
		String scale = outW > 0 ? ",scale=" + outW + ":" + outH + ":flags=lanczos" : ",scale=trunc(iw/2)*2:trunc(ih/2)*2";
		String filter = "vflip,crop=" + cw + ":" + ch + ":" + (w - cw) / 2 + ":" + (h - ch) / 2 + scale;
		List<String> cmd = new ArrayList<>(List.of(FfmpegTool.binary().toString(), "-hide_banner", "-loglevel", "error", "-y",
				"-f", "rawvideo", "-pix_fmt", "rgba", "-video_size", w + "x" + h, "-framerate", String.valueOf(fps), "-i", "pipe:0",
				"-vf", filter, "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-movflags", "+faststart",
				output.toString()));
		process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(output.resolveSibling("export.log").toFile()).start();
		OutputStream stdin = process.getOutputStream();
		pipe = Channels.newChannel(stdin);
	}

	// From the first frame on: the speakers (loopback) or the voice-track mix, raw 48 kHz stereo into a temp file.
	private static void startAudio() {
		String mode = audioMode;
		if (mode.equals("No sound")) {
			return;
		}
		try {
			audioFile = output.resolveSibling("." + output.getFileName() + ".audio.raw");
			audioOut = new java.io.BufferedOutputStream(Files.newOutputStream(audioFile), 1 << 16);
			if (mode.equals(AUDIO_MODES.get(0))) {
				Process proc = RewindAudio.loopback(List.of("-f", "s16le", "-ac", "2", "-ar", String.valueOf(RewindAudio.RATE), "pipe:1"), true);
				if (proc != null) {
					audioProcess = proc;
					java.io.OutputStream out = audioOut;
					Thread.ofPlatform().daemon().name("velo-rewind-export-audio").start(() -> {
						try (var in = proc.getInputStream()) {
							in.transferTo(out);
						} catch (java.io.IOException ignored) {
							// stopped
						}
					});
					audioUsed = "everything you heard";
					return;
				}
				LOG.info("Velo Rewind export: no way to record the speakers here - using the voice tracks");
			}
			RewindSound.capture = audioOut;
			audioUsed = "voice tracks";
		} catch (Exception e) {
			LOG.warn("Velo Rewind export: no sound", e);
			audioFile = null;
		}
	}

	private static void stopAudio() {
		RewindSound.capture = null;
		if (audioProcess != null) {
			audioProcess.destroy();
			try {
				audioProcess.waitFor(3, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			}
			audioProcess = null;
		}
		if (audioOut != null) {
			try {
				audioOut.close();
			} catch (java.io.IOException ignored) {
			}
			audioOut = null;
		}
	}

	// video + raw audio -> the same MP4 with AAC sound
	private static boolean mux() {
		Path audio = audioFile;
		audioFile = null;
		try {
			if (audio == null || !Files.exists(audio) || Files.size(audio) < RewindAudio.RATE) {
				return false;
			}
			Path tmp = output.resolveSibling("." + output.getFileName() + ".mux.mp4");
			Process p = new ProcessBuilder(FfmpegTool.binary().toString(), "-hide_banner", "-loglevel", "error", "-y", "-i", output.toString(),
					"-f", "s16le", "-ar", String.valueOf(RewindAudio.RATE), "-ac", "2", "-i", audio.toString(), "-map", "0:v", "-map", "1:a",
					"-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", tmp.toString())
					.redirectErrorStream(true).redirectOutput(output.resolveSibling("export-audio.log").toFile()).start();
			boolean ok = p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.size(tmp) > 0;
			if (ok) {
				Files.move(tmp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.deleteIfExists(tmp);
			}
			return ok;
		} catch (Exception e) {
			LOG.warn("Velo Rewind export: couldn't add the sound", e);
			return false;
		} finally {
			try {
				Files.deleteIfExists(audio);
			} catch (java.io.IOException ignored) {
			}
		}
	}

	private static void finish() {
		Minecraft mc = Minecraft.getInstance();
		ClipRecorder.frameHook = null;
		stopAudio();
		state = State.FINISHING;
		message = "Saving the video...";
		progress = 1;
		setHud(mc, hudWasHidden);
		RewindPlayer p = RewindPlayer.current();
		if (live) {
			live = false;
		} else {
			if (p != null && !p.paused()) {
				p.togglePause();
			}
			if (!(mc.gui.screen() instanceof RewindEditorScreen)) {
				mc.setScreen(new RewindEditorScreen(true));
			}
		}
		Thread.ofVirtual().start(() -> {
			try {
				if (writer != null) {
					writer.join(10_000);
				}
				if (pipe != null) {
					pipe.close();
				}
				if (process != null) {
					process.waitFor(120, TimeUnit.SECONDS);
				}
				boolean ok = process != null && process.exitValue() == 0 && Files.exists(output);
				boolean sound = false;
				if (ok && audioUsed != null) {
					message = "Adding the sound...";
					sound = mux();
				}
				state = ok ? State.DONE : State.FAILED;
				message = ok ? "Saved " + frames + " frames" + (sound ? " with sound (" + audioUsed + ")" : "")
						: "FFmpeg couldn't finish the video (see export.log)";
				if (ok) {
					mc.execute(() -> mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
							net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.6f)));
				}
			} catch (Exception e) {
				fail("Saving failed: " + e.getMessage(), e);
			} finally {
				process = null;
				pipe = null;
				writer = null;
				QUEUE.clear();
			}
		});
	}

	static void cancel() {
		if (state == State.RENDERING) {
			finish();
		}
	}

	static void reset() {
		if (!busy()) {
			state = State.IDLE;
		}
	}

	private static void fail(String why, Exception e) {
		LOG.warn("Velo Rewind export: {}", why, e);
		stopAudio();
		ClipRecorder.frameHook = null;
		state = State.FAILED;
		message = why;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> setHud(mc, hudWasHidden));
	}
}
*///?}
