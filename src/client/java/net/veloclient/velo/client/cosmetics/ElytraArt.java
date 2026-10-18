package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.texture.NativeImage;

/**
 * Elytra texture made from a cape's art. Vanilla maps whatever cape texture a player has onto the elytra wings
 * through the elytra's own UV layout, which turns a hand-drawn (or high-resolution animated) cape into a smeared mess.
 * This paints the cape's outside face onto both big faces of each wing - the 10 x 20 faces of the wing box at
 * (24, 2) and (36, 2) in the 64 x 32 elytra layout - in a texture of its own, with the thin edges in the art's
 * darker average colour, so the wings carry the cape's design sharply.
 */
public final class ElytraArt {

	/** Texture pixels per elytra-layout unit: 256 x 128 - plenty for 10 x 20 faces, small to upload every animation frame. */
	public static final int SCALE = 4;
	public static final int WIDTH = 64 * SCALE;
	public static final int HEIGHT = 32 * SCALE;

	private ElytraArt() {
	}

	/** Paints {@code cape} (any resolution of the 64 x 32 cape layout) into {@code out} ({@link #WIDTH} x {@link #HEIGHT}). */
	public static void compose(NativeImage cape, NativeImage out) {
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				out.setColorArgb(x, y, 0);
			}
		}
		int sc = Math.max(1, cape.getWidth() / 64);
		int edge = darker(average(cape, sc));
		// Edges first (top, bottom, left and right sides of the wing box), then the two faces over them.
		fill(out, 24, 0, 10, 2, edge);
		fill(out, 34, 0, 10, 2, edge);
		fill(out, 22, 2, 2, 20, edge);
		fill(out, 34, 2, 2, 20, edge);
		face(cape, out, sc, 24, 2, false);
		face(cape, out, sc, 36, 2, true);
	}

	/** One 10 x 20 face at ({@code fx}, {@code fy}) filled from the cape's outside face (10 x 16 at (1, 1)), stretched to fit. */
	private static void face(NativeImage cape, NativeImage out, int sc, int fx, int fy, boolean mirror) {
		for (int dy = 0; dy < 20 * SCALE; dy++) {
			for (int dx = 0; dx < 10 * SCALE; dx++) {
				double u = (dx + 0.5) / (10.0 * SCALE);
				double v = (dy + 0.5) / (20.0 * SCALE);
				double sx = (1 + (mirror ? 1 - u : u) * 10) * sc;
				double sy = (1 + v * 16) * sc;
				out.setColorArgb(fx * SCALE + dx, fy * SCALE + dy, sample(cape, sx, sy));
			}
		}
	}

	private static void fill(NativeImage out, int x, int y, int w, int h, int argb) {
		for (int dy = y * SCALE; dy < (y + h) * SCALE; dy++) {
			for (int dx = x * SCALE; dx < (x + w) * SCALE; dx++) {
				out.setColorArgb(dx, dy, argb);
			}
		}
	}

	/** Bilinear sample at (x, y) in source pixels. */
	private static int sample(NativeImage image, double x, double y) {
		double fx = x - 0.5;
		double fy = y - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		double tx = fx - x0;
		double ty = fy - y0;
		double a = 0;
		double r = 0;
		double g = 0;
		double b = 0;
		for (int j = 0; j < 2; j++) {
			for (int i = 0; i < 2; i++) {
				double w = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
				int px = clamp(x0 + i, image.getWidth() - 1);
				int py = clamp(y0 + j, image.getHeight() - 1);
				int argb = image.getColorArgb(px, py);
				double pa = (argb >>> 24) / 255.0 * w;
				a += pa;
				r += ((argb >> 16) & 0xFF) * pa;
				g += ((argb >> 8) & 0xFF) * pa;
				b += (argb & 0xFF) * pa;
			}
		}
		if (a <= 0.001) {
			return 0;
		}
		return ((int) Math.round(Math.min(1, a) * 255) << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
	}

	private static int clamp(int v, int max) {
		return Math.max(0, Math.min(max, v));
	}

	/** Average colour of the cape's outside face. */
	private static int average(NativeImage cape, int sc) {
		long r = 0;
		long g = 0;
		long b = 0;
		long n = 0;
		for (int y = sc; y < 17 * sc; y += Math.max(1, sc / 2)) {
			for (int x = sc; x < 11 * sc; x += Math.max(1, sc / 2)) {
				int argb = cape.getColorArgb(Math.min(x, cape.getWidth() - 1), Math.min(y, cape.getHeight() - 1));
				if ((argb >>> 24) > 32) {
					r += (argb >> 16) & 0xFF;
					g += (argb >> 8) & 0xFF;
					b += argb & 0xFF;
					n++;
				}
			}
		}
		if (n == 0) {
			return 0xFF303038;
		}
		return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
	}

	private static int darker(int argb) {
		int r = (int) (((argb >> 16) & 0xFF) * 0.55);
		int g = (int) (((argb >> 8) & 0xFF) * 0.55);
		int b = (int) ((argb & 0xFF) * 0.55);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
