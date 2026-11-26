package net.veloclient.launcher.ui.glass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher's pixel-art backdrops (2026-11-25 glass redesign), drawn procedurally into plain ARGB arrays - no JavaFX
 * here, so a scene can be rendered off the UI thread (and in a plain Java harness for previews).
 *
 * <p>Every scene is a 1280 x 800 picture on an 8 px grid (4 px for planets and stars): a static base plus a few
 * transparent layers the backdrop animates (two twinkle groups, a shooting star, a slowly falling drift layer).
 * The generators are ports of the approved design canvas (red space, crimson Nether, pixel sunset) plus colour variants
 * built from the same three generators.
 */
public final class PixelScenes {

	public static final int W = 1280;
	public static final int H = 800;

	/** A finished scene: the static picture and its animated layers (null when the scene has none). */
	public record Scene(int[] base, int[] twinkleA, int[] twinkleB, int[] shooting, int[] drift) {
	}

	/** Id, name and the colour it shows in the picker. */
	public record Info(String id, String name, int swatch) {
	}

	public static final List<Info> ALL = List.of(
			new Info("space", "Red space", 0xFFE0473C),
			new Info("nether", "Crimson Nether", 0xFFB01C2C),
			new Info("sunset", "Pixel sunset", 0xFFD95A34),
			new Info("ocean", "Blue nebula", 0xFF3C8CE0),
			new Info("aurora", "Aurora pines", 0xFF3CE08C),
			new Info("sakura", "Sakura dusk", 0xFFFF7EB6),
			new Info("amethyst", "Amethyst depths", 0xFF9A5CFF),
			new Info("snow", "Moonlit snow", 0xFFE8E8F0));

	public static final String DEFAULT = "sunset";

	private PixelScenes() {
	}

	public static Info info(String id) {
		for (Info info : ALL) {
			if (info.id().equals(id)) {
				return info;
			}
		}
		return ALL.getFirst();
	}

	public static Scene render(String id) {
		return switch (id == null ? DEFAULT : id) {
			case "nether" -> nether(Nether.CRIMSON);
			case "amethyst" -> nether(Nether.AMETHYST);
			case "sunset" -> ridge(Ridge.SUNSET);
			case "sakura" -> ridge(Ridge.SAKURA);
			case "aurora" -> ridge(Ridge.AURORA);
			case "snow" -> ridge(Ridge.SNOW);
			case "ocean" -> space(Space.OCEAN);
			default -> space(Space.RED);
		};
	}

	// ------------------------------------------------------------------ painting helpers

	private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

	private static double bayer(int x, int y, int p) {
		return BAYER[(y / p) & 3][(x / p) & 3] / 16.0 - 0.47;
	}

	/** Seeded generator matching the canvas's LCG, so the shapes are the approved ones. */
	private static final class Rng {
		private long s;

		Rng(long seed) {
			s = seed;
		}

		double next() {
			s = (s * 1103515245L + 12345L) & 0x7fffffffL;
			return s / (double) 0x7fffffffL;
		}
	}

	/** Smooth value noise on a wrapped 64 x 64 lattice. */
	private static final class Noise {
		private final double[] t = new double[4096];

		Noise(Rng r) {
			for (int i = 0; i < t.length; i++) {
				t[i] = r.next();
			}
		}

		private double at(int i, int j) {
			return t[((j & 63) << 6) | (i & 63)];
		}

		double noise(double x, double y) {
			int i = (int) Math.floor(x), j = (int) Math.floor(y);
			double fx = x - i, fy = y - j;
			double sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
			double a = at(i, j) + (at(i + 1, j) - at(i, j)) * sx;
			double b = at(i, j + 1) + (at(i + 1, j + 1) - at(i, j + 1)) * sx;
			return a + (b - a) * sy;
		}

		double fbm4(double x, double y) {
			return (noise(x, y) + 0.5 * noise(x * 2.1 + 17, y * 2.1 + 9) + 0.25 * noise(x * 4.3 + 5, y * 4.3 + 31) + 0.125 * noise(x * 8.7, y * 8.7)) / 1.875;
		}

		double fbm3(double x, double y) {
			return (noise(x, y) + 0.5 * noise(x * 2.1 + 17, y * 2.1 + 9) + 0.25 * noise(x * 4.3 + 5, y * 4.3 + 31)) / 1.75;
		}
	}

	/** Where a layer ends up. */
	private enum Kind { STATIC, TWINKLE_A, TWINKLE_B, SHOOTING, DRIFT }

	/** Cells per named layer, painted in the order the layers are declared. */
	private static final class Canvas {
		final int[] base = new int[W * H];
		final Map<String, List<int[]>> cells = new HashMap<>();
		final LinkedHashMap<String, Object[]> order = new LinkedHashMap<>();

		void put(String key, int x, int y, int p) {
			cells.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] {x, y, p, p});
		}

		void rect(String key, int x, int y, int w, int h) {
			cells.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] {x, y, w, h});
		}

		void layer(String key, int rgb, double alpha) {
			layer(key, rgb, alpha, Kind.STATIC);
		}

		void layer(String key, int rgb, double alpha, Kind kind) {
			order.put(key, new Object[] {rgb, alpha, kind});
		}

		Scene finish() {
			int[] twA = null, twB = null, shoot = null, drift = null;
			for (Map.Entry<String, Object[]> e : order.entrySet()) {
				List<int[]> list = cells.get(e.getKey());
				if (list == null) {
					continue;
				}
				int rgb = (int) e.getValue()[0];
				double alpha = (double) e.getValue()[1];
				Kind kind = (Kind) e.getValue()[2];
				int[] target;
				switch (kind) {
					case TWINKLE_A -> target = twA == null ? (twA = new int[W * H]) : twA;
					case TWINKLE_B -> target = twB == null ? (twB = new int[W * H]) : twB;
					case SHOOTING -> target = shoot == null ? (shoot = new int[W * H]) : shoot;
					case DRIFT -> target = drift == null ? (drift = new int[W * H]) : drift;
					default -> target = base;
				}
				for (int[] c : list) {
					fill(target, c[0], c[1], c[2], c[3], rgb, alpha, kind != Kind.STATIC);
				}
			}
			return new Scene(base, twA, twB, shoot, drift);
		}

		private static void fill(int[] dst, int x0, int y0, int w, int h, int rgb, double alpha, boolean overTransparent) {
			int x1 = Math.min(W, x0 + w), y1 = Math.min(H, y0 + h);
			int sr = (rgb >> 16) & 255, sg = (rgb >> 8) & 255, sb = rgb & 255;
			int a8 = (int) Math.round(alpha * 255);
			for (int y = Math.max(0, y0); y < y1; y++) {
				int row = y * W;
				for (int x = Math.max(0, x0); x < x1; x++) {
					int d = dst[row + x];
					if (overTransparent) {
						// premultiplied "over" onto a possibly transparent layer
						int da = (d >>> 24) & 255;
						int oa = a8 + da * (255 - a8) / 255;
						if (oa == 0) {
							continue;
						}
						int r = (sr * a8 + ((d >> 16) & 255) * da * (255 - a8) / 255) / oa;
						int g = (sg * a8 + ((d >> 8) & 255) * da * (255 - a8) / 255) / oa;
						int b = (sb * a8 + (d & 255) * da * (255 - a8) / 255) / oa;
						dst[row + x] = (oa << 24) | (r << 16) | (g << 8) | b;
					} else {
						int r = (sr * a8 + ((d >> 16) & 255) * (255 - a8)) / 255;
						int g = (sg * a8 + ((d >> 8) & 255) * (255 - a8)) / 255;
						int b = (sb * a8 + (d & 255) * (255 - a8)) / 255;
						dst[row + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
					}
				}
			}
		}

		/** Radial gradient over the whole picture: stops as {offset, rgb}. */
		void radial(double cx, double cy, double r, double[][] stops) {
			double rr = r * Math.hypot(W, H) / Math.sqrt(2);
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					double t = Math.min(1, Math.hypot(x - cx * W, y - cy * H) / rr);
					base[y * W + x] = gradient(stops, t);
				}
			}
		}

		/** Vertical gradient (hard bands when two stops share an offset). */
		void vertical(double[][] stops) {
			for (int y = 0; y < H; y++) {
				int c = gradient(stops, y / (double) (H - 1));
				for (int x = 0; x < W; x++) {
					base[y * W + x] = c;
				}
			}
		}

		private static int gradient(double[][] stops, double t) {
			for (int i = 1; i < stops.length; i++) {
				if (t <= stops[i][0]) {
					double span = stops[i][0] - stops[i - 1][0];
					double f = span <= 0 ? 1 : (t - stops[i - 1][0]) / span;
					return mix((int) stops[i - 1][1], (int) stops[i][1], f);
				}
			}
			return 0xFF000000 | (int) stops[stops.length - 1][1];
		}
	}

	public static int mix(int a, int b, double f) {
		int r = (int) Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
		int g = (int) Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
		int bl = (int) Math.round((a & 255) * (1 - f) + (b & 255) * f);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	// ------------------------------------------------------------------ space (red / blue)

	/** Colours of a space scene, darkest first where it matters. */
	private record Space(long seed, double[][] sky, int mag1, int mag2, int[] neb, int dust, int star, int tw1, int tw2, int shoot,
			int halo, int[] ringB, int[] planet, int[] ringF, int[] moon, int glow1, int glow2, int[] ground, int cr0, int cr1,
			int planetX, int planetY) {

		static final Space RED = new Space(1337,
				new double[][] {{0, 0x2a0712}, {0.45, 0x12030a}, {1, 0x050103}},
				0x3c0a2e, 0x6a1446, new int[] {0x2a0812, 0x4a0f1c, 0x741a26, 0xa8292e, 0xe0473c},
				0xffcab8, 0xffe2d6, 0xffffff, 0xffd2c0, 0xffb8a0, 0xff6a4a,
				new int[] {0x5a1420, 0x8c2630, 0xc8483c},
				new int[] {0x12030a, 0x2c0a16, 0x561424, 0x8e2530, 0xcc4538, 0xff8e5c},
				new int[] {0x6e1a26, 0xa8323a, 0xe8664a},
				new int[] {0x1a080e, 0x3c1820, 0x6a3034, 0xa05650, 0xd8988a},
				0xc8322e, 0xff5a44, new int[] {0x14040a, 0x1e0710, 0x3a0c18, 0xff6a4a}, 0x080205, 0x5a1622, 936, 280);

		static final Space OCEAN = new Space(2027,
				new double[][] {{0, 0x0a1a33}, {0.45, 0x050b1a}, {1, 0x02040a}},
				0x0e3a4a, 0x146a6a, new int[] {0x08142a, 0x0f2a4a, 0x1a4a7a, 0x2a74b8, 0x4cb0f0},
				0xb8dcff, 0xd6ecff, 0xffffff, 0xc0e4ff, 0xa0d0ff, 0x4aa8ff,
				new int[] {0x14325a, 0x26508c, 0x3c7ec8},
				new int[] {0x030a14, 0x0a1a30, 0x14345a, 0x255a8e, 0x3c8ccc, 0x8ed4ff},
				new int[] {0x1a3a6e, 0x3266a8, 0x5aa8e8},
				new int[] {0x0a1018, 0x182432, 0x2e4256, 0x56748e, 0x9ab8d0},
				0x2e64c8, 0x44b4ff, new int[] {0x040a14, 0x07101e, 0x0c1e38, 0x5ab8ff}, 0x020408, 0x16365a, 330, 260);
	}

	private static Scene space(Space s) {
		Canvas c = new Canvas();
		Rng r = new Rng(s.seed());
		Noise n = new Noise(r);
		c.radial(s.planetX() > 640 ? 0.68 : 0.3, 0.36, 0.85, s.sky());
		int P = 8;
		for (int y = 0; y < H; y += P) {
			for (int x = 0; x < W; x += P) {
				double diag = s.planetX() > 640 ? (60 + x * 0.42) : (520 - x * 0.36);
				double off = (y - diag) / 230;
				double band = Math.exp(-off * off);
				double v = n.fbm4(x / 230.0, y / 230.0) * (0.35 + band * 0.95) + bayer(x, y, P) * 0.07;
				double m = n.fbm4(x / 170.0 + 40, y / 170.0 + 12) * (0.3 + band * 0.9) + bayer(x, y, P) * 0.07;
				if (m > 0.66) {
					c.put(m > 0.76 ? "mag2" : "mag1", x, y, P);
				}
				if (v > 0.5) {
					c.put(v > 0.83 ? "n5" : v > 0.75 ? "n4" : v > 0.67 ? "n3" : v > 0.59 ? "n2" : "n1", x, y, P);
				}
			}
		}
		for (int i = 0; i < 240; i++) {
			c.put("dust", (int) (r.next() * 320) * 4, (int) (r.next() * 170) * 4, 4);
		}
		for (int i = 0; i < 60; i++) {
			c.put("star", (int) (r.next() * 320) * 4, (int) (r.next() * 160) * 4, 4);
		}
		for (int i = 0; i < 14; i++) {
			int x = (int) (r.next() * 316) * 4 + 4, y = (int) (r.next() * 150) * 4 + 4;
			String k = i % 2 == 1 ? "tw1" : "tw2";
			c.put(k, x, y, 4);
			c.put(k, x - 4, y, 4);
			c.put(k, x + 4, y, 4);
			c.put(k, x, y - 4, 4);
			c.put(k, x, y + 4, 4);
			if (i % 3 == 0) {
				c.put(k, x - 8, y, 4);
				c.put(k, x + 8, y, 4);
				c.put(k, x, y - 8, 4);
				c.put(k, x, y + 8, 4);
			}
		}
		for (int i = 0; i < 16; i++) {
			c.put(i < 3 ? "shootHead" : "shoot", 300 - i * 8, 70 - i * 4, 4);
		}

		int cx = s.planetX(), cy = s.planetY(), R = 148, p = 4;
		double lx = s.planetX() > 640 ? -0.58 : 0.58, ly = -0.42, lz = 0.70;
		double ang = s.planetX() > 640 ? -0.34 : 0.3, ca = Math.cos(ang), sa = Math.sin(ang), K = 0.27;
		for (int y = cy - 340; y < cy + 340; y += p) {
			for (int x = cx - 340; x < cx + 340; x += p) {
				double ccx = x + p / 2.0, ccy = y + p / 2.0;
				double dx = (ccx - cx) / R, dy = (ccy - cy) / R, d2 = dx * dx + dy * dy;
				double u = (ccx - cx) * ca + (ccy - cy) * sa, vv = -(ccx - cx) * sa + (ccy - cy) * ca;
				double rr = Math.sqrt(u * u + (vv / K) * (vv / K)) / R;
				boolean inRing = !(rr < 1.32 || rr > 2.15 || (rr > 1.66 && rr < 1.73));
				boolean front = vv > 0;
				int tone = rr < 1.66 ? (rr < 1.48 ? 2 : 1) : (rr < 1.95 ? 1 : 0);
				if (d2 <= 1) {
					if (inRing && front) {
						c.put("ringF" + tone, x, y, p);
						continue;
					}
					double nz = Math.sqrt(1 - d2);
					double lam = Math.max(0, dx * lx + dy * ly + nz * lz);
					double lat = dy * 0.94 + dx * 0.34 + 0.09 * n.fbm4(ccx / 40, ccy / 18);
					double stripe = Math.sin(lat * 19) * 0.5 + 0.5;
					double su = (ccx - cx + 26) * ca + (ccy - cy - 18) * sa, sv = -(ccx - cx + 26) * sa + (ccy - cy - 18) * ca;
					double sr = Math.sqrt(su * su + (sv / K) * (sv / K)) / R;
					double shadow = sv > 0 && sr > 1.32 && sr < 1.66 && dy > -0.2 ? 0.22 : 0;
					double t = lam * 0.92 + stripe * 0.16 - shadow + bayer(x, y, p) * 0.12;
					if (d2 > 0.86 && lam > 0.25) {
						t += 0.12;
					}
					c.put("pl" + Math.max(0, Math.min(5, (int) Math.floor(t * 6))), x, y, p);
				} else if (inRing) {
					c.put((front ? "ringF" : "ringB") + tone, x, y, p);
				} else if (d2 < 1.12 && (dx * lx + dy * ly) > 0.1) {
					c.put("halo", x, y, p);
				}
			}
		}
		int mx = s.planetX() > 640 ? 238 : 1060, my = 196, MR = 42;
		for (int y = my - MR; y < my + MR; y += p) {
			for (int x = mx - MR; x < mx + MR; x += p) {
				double dx = (x + 2 - mx) / (double) MR, dy = (y + 2 - my) / (double) MR, d2 = dx * dx + dy * dy;
				if (d2 > 1) {
					continue;
				}
				double nz = Math.sqrt(1 - d2);
				double t = Math.max(0, dx * 0.62 + dy * -0.38 + nz * 0.68) + bayer(x, y, p) * 0.14;
				if (n.fbm4(x / 14.0 + 90, y / 14.0) > 0.6) {
					t -= 0.18;
				}
				c.put("mo" + Math.max(0, Math.min(4, (int) Math.floor(t * 5))), x, y, p);
			}
		}
		int hx = 560, hy = 3020, HR = 2380;
		int[][] craters = {{180, 742, 70, 16}, {470, 700, 46, 10}, {820, 760, 96, 20}, {1110, 716, 54, 12}, {1240, 780, 70, 16}, {330, 790, 40, 9}};
		for (int y = 560; y < H; y += P) {
			for (int x = 0; x < W; x += P) {
				double dist = Math.hypot(x + 4 - hx, y + 4 - hy);
				if (dist > HR) {
					double g = dist - HR;
					if (g < 18 + bayer(x, y, P) * 10) {
						c.put("glow2", x, y, P);
					} else if (g < 54 + bayer(x, y, P) * 24) {
						c.put("glow1", x, y, P);
					}
					continue;
				}
				double depth = HR - dist;
				String key = depth < 9 ? "gr3" : depth < 30 + bayer(x, y, P) * 16 ? "gr2" : n.fbm4(x / 60.0, y / 20.0) + bayer(x, y, P) * 0.1 > 0.58 ? "gr1" : "gr0";
				for (int[] cr : craters) {
					double q = Math.pow((x - cr[0]) / (double) cr[2], 2) + Math.pow((y - cr[1]) / (double) cr[3], 2);
					if (q < 1) {
						key = y < cr[1] ? "cr0" : "cr1";
					} else if (q < 1.6 && y < cr[1]) {
						key = "gr2";
					}
				}
				c.put(key, x, y, P);
			}
		}
		c.layer("mag1", s.mag1(), 0.75);
		c.layer("mag2", s.mag2(), 0.7);
		for (int i = 0; i < 5; i++) {
			c.layer("n" + (i + 1), s.neb()[i], i == 0 ? 0.9 : 1);
		}
		c.layer("dust", s.dust(), 0.35);
		c.layer("star", s.star(), 0.9);
		c.layer("tw1", s.tw1(), 1, Kind.TWINKLE_A);
		c.layer("tw2", s.tw2(), 1, Kind.TWINKLE_B);
		c.layer("shoot", s.shoot(), 0.55, Kind.SHOOTING);
		c.layer("shootHead", 0xffffff, 1, Kind.SHOOTING);
		c.layer("halo", s.halo(), 0.32);
		for (int i = 0; i < 3; i++) {
			c.layer("ringB" + i, s.ringB()[i], 0.85);
		}
		for (int i = 0; i < 6; i++) {
			c.layer("pl" + i, s.planet()[i], 1);
		}
		for (int i = 0; i < 3; i++) {
			c.layer("ringF" + i, s.ringF()[i], 1);
		}
		for (int i = 0; i < 5; i++) {
			c.layer("mo" + i, s.moon()[i], 1);
		}
		c.layer("glow1", s.glow1(), 0.22);
		c.layer("glow2", s.glow2(), 0.45);
		for (int i = 0; i < 4; i++) {
			c.layer("gr" + i, s.ground()[i], 1);
		}
		c.layer("cr0", s.cr0(), 1);
		c.layer("cr1", s.cr1(), 1);
		return c.finish();
	}

	// ------------------------------------------------------------------ nether / cave (crimson / amethyst)

	private record Nether(long seed, double[][] sky, int far1, int far2, int far2hi, int haze1, int haze2, int fort, int fortLit, int win,
			int rack1, int rack2, int nyl1, int nyl2, int stem, int stemHi, int cap, int capHi, int shroom, int vine, int ceil, int ceilHi,
			int gsHalo, int gsHalo2, int gs0, int gs1, int gs2, int edge, int crust, int lava1, int lava2, int lava3, int spark,
			int ember, int ember2, int ash) {

		static final Nether CRIMSON = new Nether(4242,
				new double[][] {{0, 0x7a1e0e}, {0.4, 0x3a0a0c}, {1, 0x110305}},
				0x2a0a10, 0x3d0e14, 0x6a1c18, 0xff5a24, 0xff6a2a, 0x1a0507, 0x5a1a10, 0xffb347,
				0x3a0b10, 0x4e1014, 0x7a1020, 0xb01c2c, 0x4a1226, 0x7a2440, 0x9a1424, 0xc8283a, 0xffb347, 0xc8283a, 0x1e0508, 0x4e1014,
				0xff9a3c, 0xffb347, 0xb8742a, 0xe8a33c, 0xffe08a, 0xffd36b, 0x9a2a16, 0xe8541a, 0xff7a24, 0xffb040, 0xfff2c0,
				0xffb347, 0xff6a2a, 0xb89a9a);

		static final Nether AMETHYST = new Nether(7777,
				new double[][] {{0, 0x3e1a7a}, {0.4, 0x1c0a3a}, {1, 0x090314}},
				0x1a0c34, 0x281450, 0x5a34a0, 0xa064ff, 0xb47cff, 0x0c0518, 0x3c2470, 0xe6d2ff,
				0x22103e, 0x301852, 0x5c2e9a, 0x9a5cff, 0x3a2068, 0x6a44b0, 0x7a3cd8, 0xb07cff, 0xf0e0ff, 0xc89cff, 0x10071e, 0x301852,
				0xc8a0ff, 0xe0c8ff, 0x7a54c0, 0xb894ff, 0xf4ecff, 0xe8d8ff, 0x3a1a70, 0x6a34c8, 0x8a54ec, 0xb88cff, 0xffffff,
				0xd8b8ff, 0x9a6cff, 0x9a8ab8);
	}

	private static int[] cols(java.util.function.IntUnaryOperator fn) {
		int[] out = new int[W / 8];
		for (int i = 0; i < out.length; i++) {
			out[i] = (int) Math.round(fn.applyAsInt(i * 8) / 8.0) * 8;
		}
		return out;
	}

	private static Scene nether(Nether s) {
		Canvas c = new Canvas();
		Rng r = new Rng(s.seed());
		Noise n = new Noise(r);
		c.radial(0.55, 1.05, 1.05, s.sky());
		int P = 8;
		int[] far1 = cols(x -> (int) (400 - n.fbm3(x / 160.0, 3) * 210 - n.noise(x / 26.0, 7) * 40));
		int[] far2 = cols(x -> (int) (500 - n.fbm3(x / 120.0 + 9, 5) * 170 - n.noise(x / 20.0, 2) * 30));
		for (int i = 0; i < far1.length; i++) {
			int x = i * P;
			for (int y = far1[i]; y < 664; y += P) {
				c.put("far1", x, y, P);
			}
			for (int y = far2[i]; y < 664; y += P) {
				c.put(y < far2[i] + P ? "far2hi" : "far2", x, y, P);
			}
		}
		for (int y = 480; y < 664; y += P) {
			for (int x = 0; x < W; x += P) {
				double t = (y - 480) / 184.0 + bayer(x, y, P) * 0.12;
				if (t > 0.3) {
					c.put(t > 0.68 ? "haze2" : "haze1", x, y, P);
				}
			}
		}
		int deckY = 448, deckX = 560;
		box(c, "fort", deckX, deckY, W - deckX, 32);
		for (int x = deckX; x < W; x += 24) {
			c.put("fort", x, deckY - P, P);
		}
		for (int x = deckX; x < W; x += P) {
			c.put("fortLit", x, deckY + 24, P);
		}
		for (int px = deckX + 56; px < W; px += 136) {
			box(c, "fort", px, deckY + 32, 40, 664 - deckY - 32);
			for (int y = deckY + 32; y < 664; y += P) {
				c.put("fortLit", px, y, P);
			}
			box(c, "fort", px - 16, deckY + 32, 16, P);
			box(c, "fort", px + 40, deckY + 32, 16, P);
		}
		box(c, "fort", 1040, 296, 96, deckY - 296);
		for (int x = 1040; x < 1136; x += 24) {
			box(c, "fort", x, 280, 16, 16);
		}
		box(c, "win", 1064, 336, 8, 16);
		box(c, "win", 1104, 336, 8, 16);
		box(c, "win", 1084, 392, 8, 16);
		for (int y = 296; y < deckY; y += P) {
			c.put("fortLit", 1040, y, P);
		}
		int[] cliff = cols(x -> x < 420 ? (int) (360 + Math.pow(x / 420.0, 2.4) * 280 + n.noise(x / 18.0, 11) * 24) : 900);
		for (int i = 0; i < cliff.length; i++) {
			int x = i * P;
			if (cliff[i] >= 664) {
				continue;
			}
			for (int y = cliff[i]; y < 680; y += P) {
				int d = (y - cliff[i]) / P;
				c.put(d < 1 ? "nyl2" : d < 2 ? "nyl1" : n.fbm3(x / 28.0, y / 28.0) + bayer(x, y, P) * 0.15 > 0.56 ? "rack2" : "rack1", x, y, P);
			}
		}
		int[][] fungi = {{96, 96, 56}, {240, 64, 40}, {16, 48, 32}};
		for (int[] f : fungi) {
			int fx = f[0], h = f[1], capW = f[2];
			int base = cliff[fx / P];
			box(c, "stem", fx, base - h, 16, h);
			for (int y = base - h; y < base; y += P) {
				c.put("stemHi", fx, y, P);
			}
			int cy = base - h - 8;
			for (int dy = -48; dy <= 8; dy += P) {
				for (int dx = -capW; dx <= capW + 8; dx += P) {
					double q = Math.pow(dx / (double) capW, 2) + Math.pow((dy + 16) / 40.0, 2);
					if (q > 1 + n.noise((fx + dx) / 12.0, (cy + dy) / 12.0) * 0.35) {
						continue;
					}
					String k = n.noise((fx + dx) / 9.0 + 3, (cy + dy) / 9.0) > 0.78 ? "shroom" : (dy < -24 ? "capHi" : "cap");
					c.put(k, fx + dx, cy + dy, P);
				}
			}
			for (int v = 0; v < 4; v++) {
				int vx = fx - capW + 8 + (int) (r.next() * (capW * 2 / P)) * P, len = 3 + (int) (r.next() * 6);
				for (int k = 0; k < len; k++) {
					c.put("vine", vx, cy + 16 + k * P, P);
				}
			}
		}
		int[] ceil = cols(x -> (int) (36 + n.fbm3(x / 140.0, 1) * 110 + n.noise(x / 14.0, 4) * 26));
		for (int i = 0; i < ceil.length; i++) {
			int x = i * P;
			for (int y = 0; y < ceil[i]; y += P) {
				c.put(y > ceil[i] - 2 * P ? "ceilHi" : "ceil", x, y, P);
			}
			if (r.next() < 0.12) {
				int len = 2 + (int) (r.next() * 7);
				for (int k = 0; k < len; k++) {
					c.put(k > len - 2 ? "ceilHi" : "ceil", x, ceil[i] + k * P, P);
				}
			}
		}
		int[][] glows = {{520, ceil[65] - 4, 34}, {880, ceil[110] - 6, 26}, {1180, ceil[147] - 6, 20}, {700, ceil[87] - 6, 14}};
		for (int[] g : glows) {
			int gx = g[0], gy = g[1], rad = g[2];
			for (int dy = -rad * 2; dy <= rad * 2; dy += P) {
				for (int dx = -rad * 2; dx <= rad * 2; dx += P) {
					double q = Math.hypot(dx, dy) / rad;
					double q2 = Math.hypot(dx * 1.3, dy) / rad;
					if (q2 < 1 - (dy < 0 ? 0 : n.noise((gx + dx) / 6.0, (gy + dy) / 6.0) * 0.5)) {
						c.put(n.noise((gx + dx) / 5.0 + 4, (gy + dy) / 5.0) > 0.55 ? "gs2" : (q2 > 0.7 ? "gs0" : "gs1"), gx + dx, gy + dy, P);
					} else if (q < 2.2 + bayer(gx + dx, gy + dy, P) * 0.6) {
						c.put(q < 1.6 ? "gsHalo2" : "gsHalo", gx + dx, gy + dy, P);
					}
				}
			}
		}
		for (int y = 656; y < H; y += P) {
			for (int x = 0; x < W; x += P) {
				double wave = Math.sin(x / 46.0 + y / 9.0) * 0.5 + 0.5;
				double v = n.fbm3(x / 70.0, y / 24.0) * 0.75 + wave * 0.25 + bayer(x, y, P) * 0.12;
				c.put(y < 664 ? "lavaEdge" : v > 0.74 ? "lava3" : v > 0.58 ? "lava2" : v < 0.36 ? "crust" : "lava1", x, y, P);
				if (v > 0.8 && r.next() < 0.25) {
					c.put("spark", x + 2, y + 2, 4);
				}
			}
		}
		for (int i = 0; i < 70; i++) {
			c.put(i % 3 != 0 ? "ember" : "ember2", (int) (r.next() * 320) * 4, 200 + (int) (r.next() * 105) * 4, 4);
		}
		for (int i = 0; i < 50; i++) {
			c.put("ash", (int) (r.next() * 320) * 4, (int) (r.next() * 150) * 4 + 60, 4);
		}
		c.layer("far1", s.far1(), 1);
		c.layer("far2", s.far2(), 1);
		c.layer("far2hi", s.far2hi(), 1);
		c.layer("haze1", s.haze1(), 0.12);
		c.layer("haze2", s.haze2(), 0.22);
		c.layer("fort", s.fort(), 1);
		c.layer("fortLit", s.fortLit(), 1);
		c.layer("win", s.win(), 1, Kind.TWINKLE_B);
		c.layer("rack1", s.rack1(), 1);
		c.layer("rack2", s.rack2(), 1);
		c.layer("nyl1", s.nyl1(), 1);
		c.layer("nyl2", s.nyl2(), 1);
		c.layer("stem", s.stem(), 1);
		c.layer("stemHi", s.stemHi(), 1);
		c.layer("cap", s.cap(), 1);
		c.layer("capHi", s.capHi(), 1);
		c.layer("shroom", s.shroom(), 1);
		c.layer("vine", s.vine(), 0.9);
		c.layer("ceil", s.ceil(), 1);
		c.layer("ceilHi", s.ceilHi(), 1);
		c.layer("gsHalo", s.gsHalo(), 0.1);
		c.layer("gsHalo2", s.gsHalo2(), 0.18);
		c.layer("gs0", s.gs0(), 1);
		c.layer("gs1", s.gs1(), 1);
		c.layer("gs2", s.gs2(), 1);
		c.layer("lavaEdge", s.edge(), 1);
		c.layer("crust", s.crust(), 1);
		c.layer("lava1", s.lava1(), 1);
		c.layer("lava2", s.lava2(), 1);
		c.layer("lava3", s.lava3(), 1);
		c.layer("spark", s.spark(), 1, Kind.TWINKLE_A);
		c.layer("ember", s.ember(), 0.9, Kind.TWINKLE_A);
		c.layer("ember2", s.ember2(), 0.9, Kind.TWINKLE_B);
		c.layer("ash", s.ash(), 0.35);
		return c.finish();
	}

	private static void box(Canvas c, String key, int x, int y, int w, int h) {
		for (int yy = y; yy < y + h; yy += 8) {
			for (int xx = x; xx < x + w; xx += 8) {
				c.put(key, xx, yy, 8);
			}
		}
	}

	// ------------------------------------------------------------------ ridgelines (sunset / sakura / aurora / snow)

	private enum Extra { NONE, BLOSSOM, AURORA, SNOW }

	private record Ridge(double[][] bands, int dither, int star, int sunGlow, int sun, int sunCore, int cloud, int cloudLight,
			int r1, int r1hi, int r2, int r2hi, int mist, int r3, int r4, int sunX, int sunY, boolean moon, Extra extra, int extraA,
			int extraB, int extraC) {

		static final Ridge SUNSET = new Ridge(bandStops(0x10030a, 0x18050f, 0x220713, 0x2e0a18, 0x3d0e1d, 0x521320, 0x6b1a23, 0x882426, 0xa5322a,
				0xc2442e, 0xd95a34), 0xd95a34, 0xffd9c9, 0xff9a5c, 0xffb36b, 0xffe0a3, 0xff8f6b, 0xffc39c, 0x6e1f2c, 0x8a2a33, 0x4a1424,
				0x5f1b2b, 0xe07a5a, 0x2c0b19, 0x170510, 640, 420, false, Extra.NONE, 0, 0, 0);

		static final Ridge SAKURA = new Ridge(bandStops(0x1a0716, 0x26091f, 0x340c2a, 0x461236, 0x5c1a44, 0x772452, 0x93305e, 0xb0406a, 0xca5676,
				0xe0708a, 0xf08ca0), 0xf08ca0, 0xffe0ee, 0xffb0c8, 0xffc8d8, 0xfff0f4, 0xff9ab8, 0xffd0e0, 0x7a2a52, 0x9a3a66, 0x52183c,
				0x6a2450, 0xf0a0c0, 0x320c26, 0x1c0616, 720, 430, false, Extra.BLOSSOM, 0xff8ab8, 0xffc4dc, 0x4a1832);

		static final Ridge AURORA = new Ridge(bandStops(0x020a0c, 0x031012, 0x04161a, 0x061e22, 0x08282a, 0x0a3230, 0x0c3a34, 0x0e4238, 0x0f4a3c,
				0x10523e, 0x115a40), 0x115a40, 0xd8fff0, 0x6affc0, 0xc8ffe8, 0xf4fffa, 0x2a8a6a, 0x5ad0a0, 0x0c3a30, 0x16584a, 0x082a24,
				0x0e3e34, 0x4ad0a0, 0x051a18, 0x020c0c, 1100, 96, true, Extra.AURORA, 0x3cffa0, 0x8affd4, 0xa06cff);

		static final Ridge SNOW = new Ridge(bandStops(0x050608, 0x08090c, 0x0b0d11, 0x0f1116, 0x13161c, 0x181b22, 0x1d2129, 0x232731, 0x2a2e39,
				0x313641, 0x383d49), 0x383d49, 0xffffff, 0xd8dce8, 0xeef0f6, 0xffffff, 0x5a6070, 0x8a90a0, 0x4a505e, 0xe8ecf4, 0x2e333e,
				0xd0d6e2, 0xc8ccd8, 0x1a1e26, 0x0c0e12, 300, 190, true, Extra.SNOW, 0xffffff, 0xdfe4ee, 0x0);
	}

	private static double[][] bandStops(int... colors) {
		double[] at = {0, 0.08, 0.16, 0.23, 0.3, 0.36, 0.41, 0.46, 0.5, 0.54, 0.58, 1};
		List<double[]> stops = new ArrayList<>();
		for (int i = 0; i < colors.length; i++) {
			double end = i + 1 < colors.length ? at[i + 1] : 1;
			stops.add(new double[] {at[i], colors[i]});
			stops.add(new double[] {end, colors[i]});
		}
		return stops.toArray(new double[0][]);
	}

	private static int snap(double v) {
		return (int) Math.round(v / 8) * 8;
	}

	private static int[] heights(double base, double amp, double freq, long seed) {
		Rng r = new Rng(seed);
		double[] ctrl = new double[80];
		for (int i = 0; i < ctrl.length; i++) {
			ctrl[i] = r.next();
		}
		double[] fine = new double[260];
		for (int i = 0; i < fine.length; i++) {
			fine[i] = r.next();
		}
		int[] out = new int[W / 8 + 1];
		for (int i = 0; i < out.length; i++) {
			double t = i / freq;
			int i0 = (int) Math.floor(t);
			double f = t - i0, s = f * f * (3 - 2 * f);
			double v = ctrl[i0] + (ctrl[i0 + 1] - ctrl[i0]) * s;
			double t2 = i / (freq / 3.2);
			int j0 = (int) Math.floor(t2);
			double g = t2 - j0, s2 = g * g * (3 - 2 * g);
			double v2 = fine[j0] + (fine[j0 + 1] - fine[j0]) * s2;
			out[i] = snap(base - v * amp - v2 * amp * 0.28);
		}
		return out;
	}

	private static Scene ridge(Ridge s) {
		Canvas c = new Canvas();
		int P = 8;
		c.vertical(s.bands());
		Rng r = new Rng(7);
		for (int i = 0; i < (s.moon() ? 150 : 70); i++) {
			int x = snap(r.next() * W), y = snap(r.next() * (s.moon() ? 380 : 300));
			c.put(r.next() < 0.35 ? (r.next() < 0.5 ? "starA" : "starB") : "starDim", x, y, 4);
		}
		if (!s.moon()) {
			for (int y = 456; y < 470; y += 4) {
				for (int x = (y / 4) % 2 * 4; x < W; x += 8) {
					c.put("dither", x, y, 4);
				}
			}
		}
		int sunX = s.sunX(), sunY = s.sunY();
		disc(c, s.moon() ? "sunGlow" : "sunGlow", sunX, sunY, s.moon() ? 84 : 136);
		disc(c, "sun", sunX, sunY, s.moon() ? 56 : 96);
		disc(c, "sunCore", sunX, sunY, s.moon() ? 40 : 64);
		if (s.moon()) {
			Noise mn = new Noise(new Rng(99));
			for (int y = sunY - 40; y < sunY + 40; y += 4) {
				for (int x = sunX - 40; x < sunX + 40; x += 4) {
					double d = Math.hypot(x + 2 - sunX, y + 2 - sunY);
					if (d < 30 && mn.noise(x / 7.0, y / 7.0) > 0.8) {
						c.put("crater", x, y, 4);
					} else if (d < 40 && d >= 34 && (x - sunX) + (y - sunY) > 10) {
						c.put("crater", x, y, 4); // the shaded lower-right limb
					}
				}
			}
		}
		cloud(c, 120, 250, 240);
		cloud(c, 820, 200, 300);
		cloud(c, 1040, 300, 200);
		cloud(c, 360, 330, 180);
		c.rect("cloudLight", 140, 242, 120, P);
		c.rect("cloudLight", 840, 192, 160, P);
		c.rect("cloudLight", 380, 322, 90, P);
		if (s.extra() == Extra.AURORA) {
			Noise an = new Noise(new Rng(31));
			for (int x = 0; x < W; x += P) {
				double centre = 150 + an.fbm3(x / 260.0, 2) * 120 + Math.sin(x / 140.0) * 40;
				double height = 70 + an.noise(x / 90.0, 6) * 140;
				for (int y = (int) (centre - height); y < centre + 40; y += P) {
					double t = (y - (centre - height)) / (height + 40);
					double strength = Math.sin(t * Math.PI) * (0.4 + an.noise(x / 40.0, y / 60.0)) + bayer(x, y, P) * 0.3;
					if (strength > 0.95) {
						c.put("auroraC", x, y, P);
					} else if (strength > 0.7) {
						c.put("auroraB", x, y, P);
					} else if (strength > 0.45) {
						c.put("auroraA", x, y, P);
					} else if (strength > 0.3 && t < 0.4) {
						c.put("auroraTop", x, y, P);
					}
				}
			}
		}
		int[] h1 = heights(470, 150, 9, 11), h2 = heights(545, 120, 7, 23), h3 = heights(625, 90, 6, 37), h4 = heights(708, 56, 4, 51);
		ridgeFill(c, "r1", h1);
		rim(c, "r1hi", h1, sunX, s.extra() == Extra.SNOW);
		ridgeFill(c, "r2", h2);
		rim(c, "r2hi", h2, sunX, s.extra() == Extra.SNOW);
		for (int y = 520; y < 600; y += P * 2) {
			c.rect("mist", 0, y, W, P);
		}
		ridgeFill(c, "r3", h3);
		trees(c, "r3", h3, 61, s.extra() == Extra.BLOSSOM ? 16 : 26, 24, 56, s.extra() == Extra.BLOSSOM ? "bloomA" : null, s.extra() == Extra.SNOW ? "snowTree" : null);
		ridgeFill(c, "r4", h4);
		trees(c, "r4", h4, 83, s.extra() == Extra.BLOSSOM ? 9 : 18, 40, 88, s.extra() == Extra.BLOSSOM ? "bloomB" : null, s.extra() == Extra.SNOW ? "snowTree" : null);
		if (s.extra() == Extra.BLOSSOM || s.extra() == Extra.SNOW) {
			Rng d = new Rng(s.extra() == Extra.BLOSSOM ? 515 : 616);
			for (int i = 0; i < 140; i++) {
				int x = (int) (d.next() * 320) * 4, y = (int) (d.next() * 200) * 4;
				c.put(i % 4 == 0 ? "driftB" : "driftA", x, y, 4);
				if (s.extra() == Extra.BLOSSOM && i % 3 == 0) {
					c.put("driftA", x + 4, y, 4);
				}
			}
		}
		c.layer("dither", s.dither(), 0.5);
		c.layer("starDim", s.star(), 0.45);
		c.layer("starA", s.star(), 1, Kind.TWINKLE_A);
		c.layer("starB", s.star(), 1, Kind.TWINKLE_B);
		c.layer("auroraTop", s.extraC(), 0.18);
		c.layer("auroraA", s.extraA(), 0.16);
		c.layer("auroraB", s.extraA(), 0.32);
		c.layer("auroraC", s.extraB(), 0.5);
		c.layer("sunGlow", s.sunGlow(), 0.28);
		c.layer("sun", s.sun(), 1);
		c.layer("sunCore", s.sunCore(), 1);
		c.layer("crater", mix(0xFF000000 | s.sun(), 0xFF000000, 0.18) & 0xFFFFFF, 1);
		c.layer("cloud", s.cloud(), 0.32);
		c.layer("cloudLight", s.cloudLight(), 0.28);
		c.layer("r1", s.r1(), 1);
		c.layer("r1hi", s.r1hi(), 1);
		c.layer("r2", s.r2(), 1);
		c.layer("r2hi", s.r2hi(), 1);
		c.layer("mist", s.mist(), 0.16);
		c.layer("r3", s.r3(), 1);
		c.layer("bloomALow", s.extraC() == 0 ? s.extraA() : mix(0xFF000000 | s.extraA(), 0xFF000000 | s.extraC(), 0.45) & 0xFFFFFF, 1);
		c.layer("bloomA", s.extraA(), 1);
		c.layer("r4", s.r4(), 1);
		c.layer("bloomBLow", mix(0xFF000000 | s.extraA(), 0xFF000000 | s.extraC(), 0.25) & 0xFFFFFF, 1);
		c.layer("bloomB", s.extraB(), 1);
		c.layer("snowTree", s.extraA(), 0.9);
		c.layer("driftA", s.extraA(), 0.85, Kind.DRIFT);
		c.layer("driftB", s.extraB(), 0.6, Kind.DRIFT);
		return c.finish();
	}

	private static void disc(Canvas c, String key, int cx, int cy, int rad) {
		for (int y = -rad; y < rad; y += 8) {
			int half = snap(Math.sqrt(Math.max(0, rad * rad - (y + 4) * (y + 4))));
			if (half > 0) {
				c.rect(key, cx - half, cy + y, half * 2, 8);
			}
		}
	}

	private static void cloud(Canvas c, int x, int y, int w) {
		c.rect("cloud", x, y, w, 8);
		c.rect("cloud", x + 16, y - 8, w - 40, 8);
		c.rect("cloud", x + 32, y - 16, w - 80, 8);
	}

	private static void ridgeFill(Canvas c, String key, int[] hs) {
		for (int i = 0; i < hs.length; i++) {
			c.rect(key, i * 8, hs[i], 8, H - hs[i]);
		}
	}

	/** The lit edge facing the sun; on snow every ridge top is white (a snow cap two cells deep). */
	private static void rim(Canvas c, String key, int[] hs, int sunX, boolean snowCaps) {
		for (int i = 0; i < hs.length; i++) {
			int x = i * 8, y = hs[i];
			int prev = hs[Math.max(0, i - 1)], next = hs[Math.min(hs.length - 1, i + 1)];
			if (snowCaps) {
				// a cap on the higher peaks that reaches a little down the slope, with a ragged lower edge
				int line = key.startsWith("r1") ? 420 : 468;
				if (y < line) {
					int depth = Math.min(line - y, 24 + ((i * 7 + y / 8) % 3) * 8);
					c.rect(key, x, y, 8, depth);
				}
			} else if ((x < sunX && next <= y) || (x >= sunX && prev <= y)) {
				c.rect(key, x, y, 8, 8);
			}
		}
	}

	private static void trees(Canvas c, String key, int[] hs, long seed, int count, int minH, int maxH, String bloom, String snow) {
		Rng r = new Rng(seed);
		for (int k = 0; k < count; k++) {
			int col = (int) (r.next() * (hs.length - 2));
			int baseY = hs[col];
			int h = snap(minH + r.next() * (maxH - minH));
			int x = col * 8;
			if (bloom != null) {
				// a round blossom crown on a short trunk
				int trunk = snap(Math.max(16, h * 0.45));
				c.rect(key, x, baseY - trunk, 8, trunk);
				int rad = snap(h * 0.4 + 10);
				int cy = baseY - trunk - rad / 2;
				// a cluster of round puffs: centre, two sides, a top - lit from above, deeper pink below
				double[][] puffs = {{0, 0, 1}, {-0.7, 0.25, 0.68}, {0.7, 0.25, 0.68}, {0.15, -0.65, 0.62}, {-0.35, -0.45, 0.5}};
				for (int dy = -2 * rad; dy <= rad; dy += 8) {
					for (int dx = -2 * rad; dx <= 2 * rad; dx += 8) {
						String shade = null;
						for (double[] puff : puffs) {
							double pr = rad * puff[2];
							double ox = dx - puff[0] * rad, oy = dy - puff[1] * rad;
							if (ox * ox + oy * oy < pr * pr) {
								boolean lit = oy < -pr * 0.15 && ox < pr * 0.5;
								shade = lit ? bloom : (shade == null || !shade.equals(bloom) ? bloom + "Low" : shade);
							}
						}
						if (shade != null) {
							c.rect(shade, x + dx, cy + dy, 8, 8);
						}
					}
				}
				continue;
			}
			for (int row = 0; row < h / 8; row++) {
				int half = Math.max(0, (int) Math.floor((h / 8.0 - row) * 0.55));
				int y = baseY - (row + 1) * 8;
				c.rect(key, x - half * 8, y, (half * 2 + 1) * 8, 8);
				if (snow != null && row >= h / 8 - 2) {
					c.rect(snow, x - half * 8, y, (half * 2 + 1) * 8, 8);
				}
			}
		}
	}
}
