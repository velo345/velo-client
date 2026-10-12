package net.veloclient.velo.client.gui.media;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screenshots, clip thumbnails and clip frames as GUI textures. Decoding (PNG or JPEG, through the
 * JDK, since Minecraft's own loader only reads PNG) and scaling happen on a worker thread; only the
 * texture registration runs on the render thread. Everything is released when the Media screen closes.
 */
public final class MediaTextures {

	/** A loaded texture and its pixel size. */
	public record Tex(Identifier id, int width, int height) {
	}

	private static final ExecutorService DECODE = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-media-decode");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, Tex> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();
	private static int counter;

	private MediaTextures() {
	}

	/** The image at {@code file} scaled to fit {@code maxSize}; null while it's still loading. */
	public static Tex get(Path file, int maxSize) {
		String key = file + "|" + maxSize;
		Tex tex = CACHE.get(key);
		if (tex != null || PENDING.putIfAbsent(key, true) != null) {
			return tex;
		}
		DECODE.execute(() -> {
			try {
				BufferedImage image = ImageIO.read(file.toFile());
				if (image != null) {
					NativeImage nativeImage = toNative(fit(image, maxSize));
					MinecraftClient.getInstance().execute(() -> CACHE.put(key, register(nativeImage, "velo media")));
				}
			} catch (Exception ignored) {
				// Broken file: stays a placeholder.
			}
		});
		return null;
	}

	static Tex register(NativeImage image, String name) {
		Identifier id = Identifier.of("velo-client", "media/" + (counter++));
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> name, image));
		return new Tex(id, image.getWidth(), image.getHeight());
	}

	static BufferedImage fit(BufferedImage image, int maxSize) {
		double scale = Math.min(1.0, maxSize / (double) Math.max(image.getWidth(), image.getHeight()));
		if (scale >= 1.0) {
			return image;
		}
		int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(image, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	static NativeImage toNative(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
		NativeImage nativeImage = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				nativeImage.setColorArgb(x, y, argb[y * w + x] | 0xFF000000);
			}
		}
		return nativeImage;
	}

	static NativeImage decodeJpeg(byte[] jpeg, int maxSize) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
		if (image == null) {
			throw new IOException("Not an image");
		}
		return toNative(fit(image, maxSize));
	}

	static ExecutorService decoder() {
		return DECODE;
	}

	/** Draws {@code tex} letterboxed into the box, keeping its aspect ratio. */
	public static void drawFit(DrawContext context, Tex tex, int x, int y, int width, int height) {
		double scale = Math.min(width / (double) tex.width(), height / (double) tex.height());
		int w = Math.max(1, (int) (tex.width() * scale));
		int h = Math.max(1, (int) (tex.height() * scale));
		int dx = x + (width - w) / 2;
		int dy = y + (height - h) / 2;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id(), dx, dy, 0f, 0f, w, h, tex.width(), tex.height(), tex.width(), tex.height());
	}

	/** Draws {@code tex} cropped to fill the box (thumbnails). */
	public static void drawCover(DrawContext context, Tex tex, int x, int y, int width, int height) {
		double scale = Math.max(width / (double) tex.width(), height / (double) tex.height());
		int regionW = (int) Math.min(tex.width(), Math.round(width / scale));
		int regionH = (int) Math.min(tex.height(), Math.round(height / scale));
		float u = (tex.width() - regionW) / 2f;
		float v = (tex.height() - regionH) / 2f;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id(), x, y, u, v, width, height, regionW, regionH, tex.width(), tex.height());
	}

	/** Forgets one file's textures (after deleting it). */
	public static void forget(Path file) {
		String prefix = file + "|";
		CACHE.entrySet().removeIf(e -> {
			if (e.getKey().startsWith(prefix)) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(e.getValue().id());
				return true;
			}
			return false;
		});
		PENDING.keySet().removeIf(k -> k.startsWith(prefix));
	}

	public static void releaseAll() {
		for (Tex tex : CACHE.values()) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(tex.id());
		}
		CACHE.clear();
		PENDING.clear();
	}

	static boolean exists(Path file) {
		return Files.exists(file);
	}
}
