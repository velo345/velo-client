package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.texture.NativeImage;

/**
 * Elytra texture made from a cape's art. Vanilla maps whatever cape texture a player has onto the elytra wings
 * through the elytra's own UV layout, which turns a hand-drawn (or high-resolution animated) cape into a smeared mess.
 * This paints the cape's outside face into the wing in a texture of its own - but only inside vanilla's own elytra
 * silhouette: vanilla's elytra.png leaves the front face of the 2-deep wing box see-through and tapers the back face
 * like feathers, which is why a real elytra reads as one thin sheet. Filling the whole box (both faces and every edge)
 * made the wings look like thick planks.
 */
public final class ElytraArt {

	/** Texture pixels per elytra-layout unit: 256 x 128 - plenty for 10 x 20 faces, small to upload every animation frame. */
	public static final int SCALE = 4;
	public static final int WIDTH = 64 * SCALE;
	public static final int HEIGHT = 32 * SCALE;

	private ElytraArt() {
	}

	/**
	 * Vanilla elytra.png's opaque pixels (columns 20-47, rows 0-21 of the 64 x 32 layout; '#' = drawn). The wing box's
	 * back face is (36, 2) 10 x 20, its edge strips are at (34, 2), (22, 2) and the top rows.
	 */
	private static final String[] MASK = {
			"...........#########........",
			"............##..............",
			"..............########......",
			"..............#########.....",
			"...............#########....",
			"...............#########....",
			"...............#########....",
			"...............##########...",
			"...............##########...",
			"...............##########...",
			"...............##########...",
			"..#.............##########..",
			"..#.............##########..",
			"..#.............##########..",
			"..#.............##########..",
			"..#.............##########..",
			"..#..............#########..",
			"..#..............#########..",
			"..#..............#########..",
			"..#...............########..",
			"..#...............########..",
			"..#................#######.."};

	private static boolean drawn(int x, int y) {
		if (y < 0 || y >= MASK.length || x < 20 || x >= 48) {
			return false;
		}
		return MASK[y].charAt(x - 20) == '#';
	}

	/** Paints {@code cape} (any resolution of the 64 x 32 cape layout) into {@code out} ({@link #WIDTH} x {@link #HEIGHT}). */
	public static void compose(NativeImage cape, NativeImage out) {
		int sc = Math.max(1, cape.getWidth() / 64);
		int edge = darker(average(cape, sc));
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int lx = x / SCALE;
				int ly = y / SCALE;
				int argb = 0;
				if (drawn(lx, ly)) {
					if (lx >= 36 && lx < 46 && ly >= 2 && ly < 22) {
						// The back face carries the cape's outside face (mirrored, like it hangs on the back).
						double u = (x - 36 * SCALE + 0.5) / (10.0 * SCALE);
						double v = (y - 2 * SCALE + 0.5) / (20.0 * SCALE);
						argb = sample(cape, (1 + (1 - u) * 10) * sc, (1 + v * 16) * sc);
						if ((argb >>> 24) < 128) {
							argb = edge;
						}
					} else {
						argb = edge;
					}
				}
				out.setColorArgb(x, y, argb);
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
