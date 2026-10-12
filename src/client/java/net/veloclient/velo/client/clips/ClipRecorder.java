package net.veloclient.velo.client.clips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.pvp.FightTracker;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Keeps the last N seconds of gameplay in memory so a clip can be saved after the moment happened
 * (one key, or automatically after a won fight). Frames are grabbed from the GPU at a fixed rate
 * ({@link FrameGrabber}), scaled down and JPEG-compressed on a background thread and kept in a ring
 * buffer - about 25-60 MB for 30 seconds, nothing written to disk until you save. Saved clips land
 * in {@code .minecraft/velo-clips/} as MJPEG AVI with a thumbnail and a JSON sidecar (fight recap,
 * server, length), which the in-game Media screen and the launcher's gallery read.
 */
public final class ClipRecorder {

	private record Frame(byte[] jpeg, long nanos, int width, int height) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MAX_BUFFER_BYTES = 160L * 1024 * 1024;
	private static final ArrayDeque<Frame> RING = new ArrayDeque<>();
	private static final AtomicInteger QUEUED = new AtomicInteger();
	private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "velo-clip-encoder");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "velo-clip-writer");
		thread.setDaemon(true);
		return thread;
	});

	private static volatile boolean active;
	private static volatile int fps = 24;
	private static volatile int maxHeight = 480;
	private static volatile int seconds = 30;
	private static volatile float quality = 0.75f;
	private static long nextCaptureNanos;
	private static long bufferedBytes;
	private static byte[] scratch = new byte[0];

	private ClipRecorder() {
	}

	public static void configure(boolean enabled, int framesPerSecond, int height, int lengthSeconds, float jpegQuality) {
		fps = Math.max(5, Math.min(60, framesPerSecond));
		maxHeight = Math.max(240, height);
		seconds = Math.max(5, Math.min(120, lengthSeconds));
		quality = Math.max(0.3f, Math.min(0.95f, jpegQuality));
		if (active && !enabled) {
			synchronized (RING) {
				RING.clear();
				bufferedBytes = 0;
			}
			FrameGrabber.clear();
		}
		active = enabled;
	}

	public static boolean active() {
		return active;
	}

	/** Seconds of footage currently buffered. */
	public static double bufferedSeconds() {
		synchronized (RING) {
			return RING.size() < 2 ? 0 : (RING.getLast().nanos - RING.getFirst().nanos) / 1e9;
		}
	}

	public static Path clipsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("velo-clips");
	}

	/** Called at the end of every rendered frame (GameRenderer#render). */
	public static void onFrameRendered() {
		if (!active) {
			return;
		}
		if (net.veloclient.velo.client.pvp.PvpCompat.localPlayer() == null) {
			return;
		}
		long now = System.nanoTime();
		if (now < nextCaptureNanos || QUEUED.get() >= 3) {
			return;
		}
		long interval = 1_000_000_000L / fps;
		nextCaptureNanos = Math.max(nextCaptureNanos + interval, now - interval);
		try {
			FrameGrabber.grab((rgba, width, height) -> {
				QUEUED.incrementAndGet();
				long stamp = System.nanoTime();
				ENCODER.execute(() -> {
					try {
						encode(rgba, width, height, stamp);
					} catch (Throwable t) {
						VeloClient.LOGGER.debug("Clip frame dropped", t);
					} finally {
						FrameGrabber.release(rgba);
						QUEUED.decrementAndGet();
					}
				});
			});
		} catch (Throwable t) {
			// A GPU hiccup must never take the frame down with it; stop instead of failing every frame.
			VeloClient.LOGGER.warn("Clip recording stopped: frame capture failed", t);
			active = false;
		}
	}

	private static void encode(ByteBuffer rgba, int width, int height, long stamp) throws IOException {
		int outH = Math.min(height, maxHeight);
		int outW = Math.max(2, Math.round(width * (outH / (float) height)) & ~1);
		outH &= ~1;
		int bytes = width * height * 4;
		if (scratch.length < bytes) {
			scratch = new byte[bytes];
		}
		rgba.get(0, scratch, 0, bytes);
		BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
		byte[] out = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
		float sx = width / (float) outW;
		float sy = height / (float) outH;
		int stride = width * 4;
		for (int oy = 0; oy < outH; oy++) {
			// GPU rows come bottom-up.
			int y0 = height - 1 - Math.min(height - 1, (int) (oy * sy));
			int y1 = Math.max(0, y0 - (sy >= 2 ? (int) (sy / 2) : 0));
			int o = oy * outW * 3;
			for (int ox = 0; ox < outW; ox++) {
				int x0 = Math.min(width - 1, (int) (ox * sx));
				int x1 = Math.min(width - 1, x0 + (sx >= 2 ? (int) (sx / 2) : 0));
				int a = y0 * stride + x0 * 4;
				int b = y0 * stride + x1 * 4;
				int c = y1 * stride + x0 * 4;
				int d = y1 * stride + x1 * 4;
				out[o++] = (byte) (((scratch[a + 2] & 0xFF) + (scratch[b + 2] & 0xFF) + (scratch[c + 2] & 0xFF) + (scratch[d + 2] & 0xFF)) >> 2);
				out[o++] = (byte) (((scratch[a + 1] & 0xFF) + (scratch[b + 1] & 0xFF) + (scratch[c + 1] & 0xFF) + (scratch[d + 1] & 0xFF)) >> 2);
				out[o++] = (byte) (((scratch[a] & 0xFF) + (scratch[b] & 0xFF) + (scratch[c] & 0xFF) + (scratch[d] & 0xFF)) >> 2);
			}
		}
		byte[] jpeg = jpeg(image, quality);
		synchronized (RING) {
			RING.addLast(new Frame(jpeg, stamp, outW, outH));
			bufferedBytes += jpeg.length;
			long keep = (long) seconds * 1_000_000_000L;
			while (!RING.isEmpty() && (stamp - RING.getFirst().nanos > keep || bufferedBytes > MAX_BUFFER_BYTES)) {
				bufferedBytes -= RING.removeFirst().jpeg.length;
			}
		}
	}

	static byte[] jpeg(BufferedImage image, float quality) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
			try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
				writer.setOutput(stream);
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return bytes.toByteArray();
		} finally {
			writer.dispose();
		}
	}

	/**
	 * Saves what's buffered (optionally only the last {@code lastSeconds}) to a new clip.
	 * {@code done} gets the saved file, or null if there was nothing to save / it failed.
	 */
	public static void save(String trigger, FightTracker.Recap recap, double lastSeconds, Consumer<Path> done) {
		List<Frame> frames;
		synchronized (RING) {
			frames = new ArrayList<>(RING);
		}
		if (lastSeconds > 0 && !frames.isEmpty()) {
			long cut = frames.getLast().nanos - (long) (lastSeconds * 1e9);
			frames.removeIf(f -> f.nanos < cut);
		}
		if (frames.size() < 2) {
			done.accept(null);
			return;
		}
		// All frames of a clip must share one size (the window may have been resized meanwhile).
		Frame last = frames.getLast();
		frames.removeIf(f -> f.width != last.width || f.height != last.height);
		String server = net.veloclient.velo.client.util.ClientCompat.currentServerAddress();
		WRITER.execute(() -> {
			try {
				Files.createDirectories(clipsDir());
				String name = "clip-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now());
				Path file = clipsDir().resolve(name + ".avi");
				double length = (frames.getLast().nanos - frames.getFirst().nanos) / 1e9;
				double clipFps = length > 0 ? (frames.size() - 1) / length : fps;
				List<byte[]> jpegs = new ArrayList<>(frames.size());
				for (Frame frame : frames) {
					jpegs.add(frame.jpeg);
				}
				MjpegAvi.write(file, jpegs, clipFps, last.width, last.height);
				Files.write(clipsDir().resolve(name + ".jpg"), frames.get(frames.size() * 2 / 3).jpeg);
				JsonObject meta = new JsonObject();
				meta.addProperty("createdAt", System.currentTimeMillis());
				meta.addProperty("seconds", Math.round(length * 10) / 10.0);
				meta.addProperty("fps", Math.round(clipFps * 10) / 10.0);
				meta.addProperty("width", last.width);
				meta.addProperty("height", last.height);
				meta.addProperty("trigger", trigger);
				if (server != null) {
					meta.addProperty("server", server);
				}
				if (recap != null) {
					meta.add("recap", GSON.toJsonTree(recap));
				}
				Files.writeString(clipsDir().resolve(name + ".json"), GSON.toJson(meta), StandardCharsets.UTF_8);
				MinecraftClient.getInstance().execute(() -> done.accept(file));
			} catch (Exception e) {
				VeloClient.LOGGER.warn("Saving clip failed", e);
				MinecraftClient.getInstance().execute(() -> done.accept(null));
			}
		});
	}
}
