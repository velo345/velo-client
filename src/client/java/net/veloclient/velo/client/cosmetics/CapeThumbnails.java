package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.config.VeloPaths;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small still pictures of animated cloaks for grids (the Store's cloak tiles, bundle cards): the first frame only,
 * {@link #TEXELS} texture pixels per cloak pixel, made off the render thread and kept as a PNG in the cache folder so
 * the next visit shows them at once. Before, every tile decoded its whole animated GIF (up to 40 frames of 2048 px) on
 * the render thread the first time it was drawn - a page of them froze the game for seconds - and freed it again a
 * minute later. The full animation is only loaded for the one being hovered or opened.
 */
public final class CapeThumbnails {

	/** A thumbnail texture: the 64 x 32 cloak layout at {@code width} x {@code height} (only the cloak part painted). */
	public record Thumb(Identifier id, int width, int height) {
	}

	private static final int TEXELS = 8;
	private static final int REGION_W = 22;
	private static final int REGION_H = 17;
	private static final Map<String, Thumb> READY = new ConcurrentHashMap<>();
	private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
	private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Velo cloak thumbnails");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private CapeThumbnails() {
	}

	/**
	 * The thumbnail for {@code key} (unique per art version, e.g. item id + file hash), or null while it's being made -
	 * it starts loading on the first call. Render thread.
	 */
	public static Thumb get(String key, Supplier<InputStream> gif) {
		Thumb ready = READY.get(key);
		if (ready != null) {
			return ready;
		}
		Long failed = FAILED_AT.get(key);
		if (failed != null && System.currentTimeMillis() - failed < 10_000) {
			return null;
		}
		if (LOADING.add(key)) {
			LOADER.submit(() -> load(key, gif));
		}
		return null;
	}

	private static void load(String key, Supplier<InputStream> gif) {
		try {
			Path cached = cacheDir().resolve(fileName(key) + ".png");
			BufferedImage image = Files.isRegularFile(cached) ? javax.imageio.ImageIO.read(cached.toFile()) : null;
			if (image == null) {
				BufferedImage first;
				try (InputStream in = gif.get()) {
					first = GifDecoder.firstFrame(in);
				}
				image = layout(first);
				try {
					Files.createDirectories(cached.getParent());
					javax.imageio.ImageIO.write(image, "png", cached.toFile());
				} catch (Exception ignored) {
					// Cache is a bonus - it's made again next time.
				}
			}
			int w = image.getWidth();
			int h = image.getHeight();
			int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
			MinecraftClient.getInstance().execute(() -> register(key, argb, w, h));
		} catch (Exception e) {
			FAILED_AT.put(key, System.currentTimeMillis());
			LOADING.remove(key);
		}
	}

	/** The first frame's cloak corner at {@link #TEXELS} per cloak pixel, on a transparent 64 x 32 layout. */
	private static BufferedImage layout(BufferedImage frame) {
		// Full 64x32 layout (2:1) or a frame already cropped to the cloak corner (22x17 cloak px, HD art since 0.9.9):
		// width/64 on a cropped frame gave a third of the scale, so only the left part of the art ended up in the card.
		double aspect = frame.getWidth() / (double) Math.max(1, frame.getHeight());
		int scale = Math.max(1, Math.abs(aspect - 2.0) < 0.25 ? frame.getWidth() / 64 : frame.getWidth() / REGION_W);
		int rw = Math.min(frame.getWidth(), REGION_W * scale);
		int rh = Math.min(frame.getHeight(), REGION_H * scale);
		int outScale = Math.min(TEXELS, scale);
		BufferedImage out = new BufferedImage(64 * outScale, 32 * outScale, BufferedImage.TYPE_INT_ARGB);
		int ow = REGION_W * outScale;
		int oh = REGION_H * outScale;
		int step = scale / outScale;
		// Box filter: each output pixel averages its step x step source block (alpha-weighted).
		for (int y = 0; y < oh; y++) {
			for (int x = 0; x < ow; x++) {
				long a = 0;
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;
				for (int j = 0; j < step; j++) {
					int sy = y * step + j;
					if (sy >= rh) {
						break;
					}
					for (int i = 0; i < step; i++) {
						int sx = x * step + i;
						if (sx >= rw) {
							break;
						}
						int c = frame.getRGB(sx, sy);
						int ca = c >>> 24;
						a += ca;
						r += (long) ((c >> 16) & 0xFF) * ca;
						g += (long) ((c >> 8) & 0xFF) * ca;
						b += (long) (c & 0xFF) * ca;
						n++;
					}
				}
				if (n > 0 && a > 0) {
					out.setRGB(x, y, (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
				}
			}
		}
		return out;
	}

	private static void register(String key, int[] argb, int w, int h) {
		Identifier id = Identifier.of("velo-client", "cape_thumb_" + fileName(key));
		NativeImageBackedTexture texture = w >= 256 ? new AnimatedCapeAsset.SmoothTexture(() -> "cloak thumbnail " + key, w, h)
				: new NativeImageBackedTexture(() -> "cloak thumbnail " + key, w, h, false);
		var image = texture.getImage();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setColorArgb(x, y, argb[y * w + x]);
			}
		}
		texture.upload();
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
		READY.put(key, new Thumb(id, w, h));
		LOADING.remove(key);
	}

	private static String fileName(String key) {
		return key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
	}

	private static Path cacheDir() {
		// v2: the first thumbnails of cropped HD cloaks were cut (wrong scale) - a new folder makes them again
		return VeloPaths.root().resolve("cache").resolve("cloak-thumbs-v2");
	}
}
