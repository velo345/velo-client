package net.veloclient.velo.client.privacy;

import net.minecraft.client.texture.NativeImage;

/**
 * Anti Coords Leak: what the minimap shows while coordinates are hidden - the terrain around you is as telling as the
 * numbers. Blurred keeps only big colour patches (water here, forest there); Fake draws made-up terrain that still
 * scrolls as you walk (seeded with a random offset per session); Hidden leaves it empty.
 */
public final class MinimapPrivacy {

	public static final String NORMAL = "Normal";
	public static final String BLURRED = "Blurred";
	public static final String FAKE = "Fake";
	public static final String HIDDEN = "Hidden";

	/** What the minimap does while hiding (Anti Coords Leak's setting). */
	public static volatile String style = BLURRED;

	private static final int OFFSET_X = new java.util.Random().nextInt(2_000_000) - 1_000_000;
	private static final int OFFSET_Z = new java.util.Random().nextInt(2_000_000) - 1_000_000;

	private MinimapPrivacy() {
	}

	/** Rewrites a freshly sampled minimap image (centred on world column centerX/centerZ) if hiding asks for it. */
	public static void apply(NativeImage image, int centerX, int centerZ) {
		if (!net.veloclient.velo.client.modules.qol.AntiCoordsLeakModule.hiding()) {
			return;
		}
		String s = style;
		int size = image.getWidth();
		int radius = size / 2;
		switch (s) {
			case HIDDEN -> {
				for (int y = 0; y < size; y++) {
					for (int x = 0; x < size; x++) {
						image.setColorArgb(x, y, 0xFF16161C);
					}
				}
			}
			case FAKE -> {
				for (int y = 0; y < size; y++) {
					for (int x = 0; x < size; x++) {
						image.setColorArgb(x, y, fakeColumn(centerX + x - radius + OFFSET_X, centerZ + y - radius + OFFSET_Z));
					}
				}
			}
			case BLURRED -> blur(image, size);
			default -> {
			}
		}
	}

	private static void blur(NativeImage image, int size) {
		// 8x8 block average, then a soft blur over the blocks: shapes the size of a house are gone.
		int block = 8;
		int cells = (size + block - 1) / block;
		float[] r = new float[cells * cells];
		float[] g = new float[cells * cells];
		float[] b = new float[cells * cells];
		int[] n = new int[cells * cells];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int argb = image.getColorArgb(x, y);
				int c = (y / block) * cells + x / block;
				r[c] += (argb >> 16) & 0xFF;
				g[c] += (argb >> 8) & 0xFF;
				b[c] += argb & 0xFF;
				n[c]++;
			}
		}
		for (int c = 0; c < n.length; c++) {
			r[c] /= Math.max(1, n[c]);
			g[c] /= Math.max(1, n[c]);
			b[c] /= Math.max(1, n[c]);
		}
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				// bilinear between block centres
				float fx = Math.max(0, Math.min(cells - 1.001f, (x + 0.5f) / block - 0.5f));
				float fy = Math.max(0, Math.min(cells - 1.001f, (y + 0.5f) / block - 0.5f));
				int x0 = (int) fx;
				int y0 = (int) fy;
				int x1 = Math.min(cells - 1, x0 + 1);
				int y1 = Math.min(cells - 1, y0 + 1);
				float tx = fx - x0;
				float ty = fy - y0;
				int c00 = y0 * cells + x0;
				int c10 = y0 * cells + x1;
				int c01 = y1 * cells + x0;
				int c11 = y1 * cells + x1;
				int rr = Math.round(lerp(lerp(r[c00], r[c10], tx), lerp(r[c01], r[c11], tx), ty));
				int gg = Math.round(lerp(lerp(g[c00], g[c10], tx), lerp(g[c01], g[c11], tx), ty));
				int bb = Math.round(lerp(lerp(b[c00], b[c10], tx), lerp(b[c01], b[c11], tx), ty));
				image.setColorArgb(x, y, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
			}
		}
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** Plausible terrain from value noise: lakes, beaches, meadows and darker forest. */
	private static int fakeColumn(int x, int z) {
		double h = noise(x / 64.0, z / 64.0) * 0.6 + noise(x / 23.0, z / 23.0) * 0.3 + noise(x / 7.0, z / 7.0) * 0.1;
		double forest = noise(x / 31.0 + 91.7, z / 31.0 - 13.3);
		int shade = (int) ((noise(x / 3.0, z / 3.0) - 0.5) * 18);
		if (h < 0.36) {
			return rgb(0x3F76E4, (int) ((h - 0.36) * 120));
		}
		if (h < 0.39) {
			return rgb(0xD8CB8C, shade);
		}
		if (forest > 0.58) {
			return rgb(0x3C6B2A, shade);
		}
		return rgb(0x6FA34A, shade);
	}

	private static int rgb(int base, int delta) {
		int r = Math.max(0, Math.min(255, ((base >> 16) & 0xFF) + delta));
		int g = Math.max(0, Math.min(255, ((base >> 8) & 0xFF) + delta));
		int b = Math.max(0, Math.min(255, (base & 0xFF) + delta));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static double noise(double x, double z) {
		int x0 = (int) Math.floor(x);
		int z0 = (int) Math.floor(z);
		double tx = x - x0;
		double tz = z - z0;
		tx = tx * tx * (3 - 2 * tx);
		tz = tz * tz * (3 - 2 * tz);
		double a = hash(x0, z0) + (hash(x0 + 1, z0) - hash(x0, z0)) * tx;
		double b = hash(x0, z0 + 1) + (hash(x0 + 1, z0 + 1) - hash(x0, z0 + 1)) * tx;
		return a + (b - a) * tz;
	}

	private static double hash(int x, int z) {
		long h = x * 374761393L + z * 668265263L;
		h = (h ^ (h >>> 13)) * 1274126177L;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
	}
}
