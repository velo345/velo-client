package net.veloclient.velo.client.nowplaying;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The current song's cover as a GUI texture, plus the one before it so a song change can crossfade. Also picks the
 * cover's most vivid color, which tints the popup and the controls. Decoding goes through the JDK (covers are mostly
 * JPEG; Minecraft's loader only reads PNG) on a virtual thread; only registering runs on the render thread.
 */
public final class CoverArt {

	private static final int SIZE = 96;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private static String wanted;
	private static Identifier current;
	private static Identifier previous;
	private static int currentColor = 0;
	private static int previousColor = 0;
	private static long swappedAt;
	private static int serial;

	private CoverArt() {
	}

	/** Render thread: makes sure {@code art} (a path or URL, may be null) is what's loading or shown. */
	static void want(String art) {
		String key = art == null ? "" : art;
		if (key.equals(wanted)) {
			return;
		}
		wanted = key;
		if (art == null) {
			swap(null, 0);
			return;
		}
		int mine = ++serial;
		Thread.ofVirtual().name("velo-cover").start(() -> {
			try {
				byte[] bytes;
				if (art.startsWith("http://") || art.startsWith("https://")) {
					HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(art)).timeout(Duration.ofSeconds(10)).GET().build(),
							HttpResponse.BodyHandlers.ofByteArray());
					if (response.statusCode() >= 400) {
						return;
					}
					bytes = response.body();
				} else {
					bytes = Files.readAllBytes(Path.of(art));
				}
				BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
				if (image == null) {
					return;
				}
				BufferedImage square = square(image);
				int color = vivid(square);
				NativeImage nativeImage = toNative(square);
				MinecraftClient.getInstance().execute(() -> {
					if (mine != serial) {
						nativeImage.close();
						return;
					}
					Identifier id = Identifier.of("velo-client", "nowplaying/cover_" + mine);
					MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "velo cover", nativeImage));
					swap(id, color);
				});
			} catch (Exception ignored) {
				// No cover then: the popup shows its placeholder note.
			}
		});
	}

	private static void swap(Identifier id, int color) {
		if (previous != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(previous);
		}
		previous = current;
		previousColor = currentColor;
		current = id;
		currentColor = color;
		swappedAt = System.nanoTime();
	}

	static void release() {
		for (Identifier id : new Identifier[] {current, previous}) {
			if (id != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			}
		}
		current = previous = null;
		currentColor = previousColor = 0;
		wanted = null;
		serial++;
	}

	/** 0..1 through the crossfade from the previous cover to this one. */
	private static float fade() {
		return Math.min(1f, (System.nanoTime() - swappedAt) / 350_000_000f);
	}

	public static boolean has() {
		return current != null || previous != null && fade() < 1f;
	}

	/** The cover's accent, crossfading with the cover; {@code fallback} when there's none. */
	public static int color(int fallback) {
		int to = current != null && currentColor != 0 ? currentColor : fallback;
		int from = previous != null && previousColor != 0 ? previousColor : fallback;
		float t = fade();
		return t >= 1f ? to : net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(from, to, t);
	}

	/** Draws the cover (crossfading on a song change) into a square, {@code alpha} 0..1. Draws nothing without a cover. */
	public static void draw(DrawContext context, int x, int y, int size, float alpha) {
		float t = fade();
		if (previous != null && t < 1f) {
			blit(context, previous, x, y, size, alpha);
		}
		if (current != null) {
			blit(context, current, x, y, size, alpha * t);
		}
	}

	private static void blit(DrawContext context, Identifier id, int x, int y, int size, float alpha) {
		int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
		if (a <= 0) {
			return;
		}
		context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, SIZE, SIZE, SIZE, SIZE, (a << 24) | 0xFFFFFF);
	}

	private static BufferedImage square(BufferedImage image) {
		int side = Math.min(image.getWidth(), image.getHeight());
		int sx = (image.getWidth() - side) / 2;
		int sy = (image.getHeight() - side) / 2;
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(image, 0, 0, SIZE, SIZE, sx, sy, sx + side, sy + side, null);
		g.dispose();
		return out;
	}

	/** The cover's most vivid color, by saturation-weighted buckets, brightened enough to glow on a dark panel. */
	private static int vivid(BufferedImage image) {
		int[] px = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
		float[] weight = new float[36];
		float[][] sum = new float[36][3];
		float[] hsb = new float[3];
		for (int i = 0; i < px.length; i += 3) {
			int r = px[i] >> 16 & 0xFF;
			int gr = px[i] >> 8 & 0xFF;
			int b = px[i] & 0xFF;
			java.awt.Color.RGBtoHSB(r, gr, b, hsb);
			if (hsb[2] < 0.18f) {
				continue;
			}
			float w = hsb[1] * hsb[1] * hsb[2];
			int bucket = Math.min(35, (int) (hsb[0] * 36));
			weight[bucket] += w;
			sum[bucket][0] += r * w;
			sum[bucket][1] += gr * w;
			sum[bucket][2] += b * w;
		}
		int best = 0;
		for (int i = 1; i < 36; i++) {
			if (weight[i] > weight[best]) {
				best = i;
			}
		}
		if (weight[best] < 0.5f) {
			return 0;
		}
		int r = Math.round(sum[best][0] / weight[best]);
		int gr = Math.round(sum[best][1] / weight[best]);
		int b = Math.round(sum[best][2] / weight[best]);
		java.awt.Color.RGBtoHSB(r, gr, b, hsb);
		int rgb = java.awt.Color.HSBtoRGB(hsb[0], Math.min(0.85f, Math.max(0.45f, hsb[1])), Math.max(0.82f, hsb[2]));
		return 0xFF000000 | rgb & 0xFFFFFF;
	}

	private static NativeImage toNative(BufferedImage image) {
		int[] argb = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
		NativeImage nativeImage = new NativeImage(SIZE, SIZE, false);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				nativeImage.setColorArgb(x, y, argb[y * SIZE + x] | 0xFF000000);
			}
		}
		return nativeImage;
	}
}
