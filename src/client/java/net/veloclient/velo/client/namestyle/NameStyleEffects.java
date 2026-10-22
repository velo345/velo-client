package net.veloclient.velo.client.namestyle;

/**
 * The colour of each letter of a styled name at a moment in time - the whole look of every {@link NameStyle.Effect}.
 * Letter {@code index} of {@code length} (icon included), {@code seconds} since any fixed point (the same clock for
 * every letter, so a name moves as one). Plain Java and allocation-free: it runs for every glyph of every styled
 * name, every frame.
 */
public final class NameStyleEffects {

	private NameStyleEffects() {
	}

	/** The RGB colour (no alpha) of a letter. */
	public static int color(NameStyle style, int index, int length, double seconds) {
		double t = seconds * style.speed() / 10.0;
		double pos = length <= 1 ? 0 : index / (double) (length - 1);
		if (style.reverse()) {
			pos = 1 - pos;
		}
		int[] c = style.colors();
		return switch (style.effect()) {
			case SOLID -> c[0];
			case GRADIENT -> gradient(c, 0.5 + (pos - 0.5) / spreadOf(style));
			case WAVE -> cyclic(c, frac(pos * 0.7 / spreadOf(style) - t * 0.35));
			case RAINBOW -> hsv(frac(pos * 0.6 / spreadOf(style) - t * 0.25), 0.72, 1.0);
			case CHROMA -> hsv(frac(index * 0.085 / spreadOf(style) - t * 0.9), 0.9, 1.0);
			case PULSE -> gradient(c, 0.5 + 0.5 * Math.sin(t * 2.4 - pos * 1.3 / spreadOf(style)));
			case SHIMMER -> {
				double width = 0.28 * spreadOf(style);
				double center = frac(t * 0.42) * (1 + 3 * width) - 1.5 * width;
				double k = Math.max(0, 1 - Math.abs(pos - center) / width);
				yield mix(gradient(c, pos), 0xFFFFFF, k * k * 0.9);
			}
			case NEON -> {
				long slot = (long) Math.floor(t * 9);
				double brightness = hash(slot, 7) < 0.07 ? 0.3 : 1.0;
				if (hash(slot * 31 + index, 3) < 0.035) {
					brightness = Math.min(brightness, 0.45);
				}
				yield scale(gradient(c, pos), brightness);
			}
			case SPARKLE -> {
				double phase = t * 2.2 + hash(index, 11) * 7;
				long slot = (long) Math.floor(phase);
				double k = hash(slot * 17 + index, 5) < 0.22 ? Math.sin(frac(phase) * Math.PI) : 0;
				yield mix(gradient(c, pos), 0xFFFFFF, k * 0.95);
			}
			case FIRE -> {
				double heat = noise(index * 0.9, t * 3.1) * 0.75 + 0.25 * (0.5 + 0.5 * Math.sin(t * 9 + index * 1.7));
				yield scale(gradient(c, Math.max(0, Math.min(1, heat))), 0.75 + 0.25 * noise(index * 2.3 + 40, t * 6));
			}
			case AURORA -> {
				double x = 0.5 + 0.3 * Math.sin(pos * 2.3 / spreadOf(style) + t * 0.55) + 0.2 * Math.sin(pos * 4.1 / spreadOf(style) - t * 0.33);
				yield cyclic(c, frac(x));
			}
			case STRIPES -> {
				double band = frac(index * 0.5 / spreadOf(style) - t * 0.6);
				yield c[Math.min(c.length - 1, (int) (band * c.length))];
			}
			case SCANNER -> {
				double head = 0.5 - 0.5 * Math.cos(t * 1.6);
				double k = Math.max(0, 1 - Math.abs(pos - head) * (2.2 + 4.0 / spreadOf(style)));
				yield mix(c[0], c.length > 1 ? c[c.length - 1] : 0xFFFFFF, k);
			}
			case HEARTBEAT -> {
				double beat = frac(t * 0.8);
				double k = Math.exp(-Math.pow((beat - 0.1) * 18, 2)) + 0.7 * Math.exp(-Math.pow((beat - 0.28) * 18, 2));
				yield mix(c[0], c.length > 1 ? c[c.length - 1] : 0xFFFFFF, Math.min(1, k));
			}
			case STROBE -> c[(int) Math.floor(t * 3) % c.length];
			case LAVA -> {
				double heat = noise(index * 0.35 + 9, t * 0.8) * 0.7 + 0.3 * (0.5 + 0.5 * Math.sin(t * 1.3 + pos * 2));
				yield gradient(c, Math.max(0, Math.min(1, heat)));
			}
			case ELECTRIC -> {
				int base = gradient(c, pos);
				long slot = (long) Math.floor(t * 7);
				if (hash(slot, 17) < 0.14) {
					double strike = hash(slot, 23);
					yield mix(base, 0xFFFFFF, Math.max(0, 1 - Math.abs(pos - strike) * 3));
				}
				yield scale(base, 0.8 + 0.2 * noise(index * 3.0, t * 12));
			}
			case REVEAL -> {
				double cycle = frac(t * 0.35) * (length + 6);
				int dim = c[0];
				int lit = c.length > 1 ? c[c.length - 1] : 0xFFFFFF;
				double k = Math.max(0, Math.min(1, cycle - index));
				yield cycle > length + 3 ? mix(lit, dim, (cycle - length - 3) / 3) : mix(dim, lit, k);
			}
			case MATRIX -> {
				double drop = frac(t * 0.9 + hash(index, 29));
				int bright = c[0];
				int dark = c.length > 1 ? c[c.length - 1] : scale(c[0], 0.35);
				yield drop < 0.12 ? mix(0xFFFFFF, bright, drop / 0.12) : mix(bright, dark, (drop - 0.12) / 0.88);
			}
			case DUO -> {
				int a = c[0];
				int b = c.length > 1 ? c[1] : 0xFFFFFF;
				double k = 0.5 + 0.5 * Math.sin(t * 2 + index * Math.PI);
				yield mix(a, b, k);
			}
			case GLITCH -> {
				int base = gradient(c, pos);
				if (!glitching(t)) {
					yield base;
				}
				double roll = hash((long) Math.floor(t * 30) * 13 + index, 9);
				yield roll < 0.33 ? c[(int) (roll * 1000) % c.length] : roll < 0.45 ? 0xFFFFFF : base;
			}
		};
	}

	/** Glitch only: whether this letter shows a random glyph right now. */
	public static boolean scrambled(NameStyle style, int index, double seconds) {
		if (style.effect() != NameStyle.Effect.GLITCH) {
			return false;
		}
		double t = seconds * style.speed() / 10.0;
		return glitching(t) && hash((long) Math.floor(t * 24) * 7 + index, 13) < 0.16;
	}

	/** The colour of a glowing name's halo: the letter's own colour, a touch lighter (drawn translucent). */
	public static int glow(int rgb) {
		return mix(rgb, 0xFFFFFF, 0.15);
	}

	/** Spread 1..9 as a factor: 5 = 1.0 (normal), smaller = tighter pattern, bigger = wider. */
	private static double spreadOf(NameStyle style) {
		return Math.pow(1.35, style.spread() - 5);
	}

	private static boolean glitching(double t) {
		return hash((long) Math.floor(t * 5), 21) < 0.2;
	}

	// ---- colour helpers ----

	/** Linear blend through the colours, {@code x} 0..1 from the first to the last. */
	public static int gradient(int[] colors, double x) {
		if (colors.length == 1) {
			return colors[0];
		}
		double scaled = Math.max(0, Math.min(1, x)) * (colors.length - 1);
		int i = Math.min(colors.length - 2, (int) scaled);
		return mix(colors[i], colors[i + 1], scaled - i);
	}

	/** Like {@link #gradient} but the last colour flows back into the first (for moving effects, so there's no seam). */
	public static int cyclic(int[] colors, double x) {
		if (colors.length == 1) {
			return colors[0];
		}
		double scaled = frac(x) * colors.length;
		int i = (int) scaled % colors.length;
		return mix(colors[i], colors[(i + 1) % colors.length], scaled - Math.floor(scaled));
	}

	public static int mix(int a, int b, double k) {
		k = Math.max(0, Math.min(1, k));
		int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
		int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
		int bl = (int) Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
		return r << 16 | g << 8 | bl;
	}

	public static int scale(int rgb, double k) {
		int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * k);
		int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * k);
		int b = (int) Math.min(255, (rgb & 0xFF) * k);
		return r << 16 | g << 8 | b;
	}

	public static int hsv(double h, double s, double v) {
		double hh = frac(h) * 6;
		int sector = (int) hh;
		double f = hh - sector;
		double p = v * (1 - s);
		double q = v * (1 - s * f);
		double u = v * (1 - s * (1 - f));
		double r;
		double g;
		double b;
		switch (sector) {
			case 0 -> { r = v; g = u; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = u; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = u; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return (int) Math.round(r * 255) << 16 | (int) Math.round(g * 255) << 8 | (int) Math.round(b * 255);
	}

	private static double frac(double x) {
		return x - Math.floor(x);
	}

	/** A stable pseudo-random number in [0, 1) for an integer and a salt. */
	private static double hash(long n, int salt) {
		long x = n * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
		x ^= x >>> 31;
		x *= 0x94D049BB133111EBL;
		x ^= x >>> 29;
		return (x >>> 11) * 0x1.0p-53;
	}

	/** Smooth 2D value noise in [0, 1]. */
	private static double noise(double x, double y) {
		long xi = (long) Math.floor(x);
		long yi = (long) Math.floor(y);
		double xf = x - xi;
		double yf = y - yi;
		double u = xf * xf * (3 - 2 * xf);
		double v = yf * yf * (3 - 2 * yf);
		double a = hash(xi * 73856093L ^ yi * 19349663L, 1);
		double b = hash((xi + 1) * 73856093L ^ yi * 19349663L, 1);
		double c = hash(xi * 73856093L ^ (yi + 1) * 19349663L, 1);
		double d = hash((xi + 1) * 73856093L ^ (yi + 1) * 19349663L, 1);
		return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
	}
}
