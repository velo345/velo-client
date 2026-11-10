package net.veloclient.launcher.sandbox;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

import java.util.HashMap;
import java.util.Map;

/**
 * Shaded pixel art for the monsters: bodies are lit volumes (light from the top left, four-tone ramps with an ordered
 * dither), outlined, with glinting eyes - painted once per animation frame and cached, plus a white silhouette of each
 * frame for the hit flash.
 */
final class MobArt {

	private static final Map<String, Image> CACHE = new HashMap<>();
	private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

	private MobArt() {
	}

	static void clear() {
		CACHE.clear();
	}

	/** A frame of {@code kind}; white = the hit-flash silhouette. Feet sit on the bottom row, centred. */
	static Image frame(int kind, int frame, boolean white) {
		String key = kind + ":" + frame + ":" + white;
		Image cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		Painter p = paint(kind, frame);
		Image image = white ? p.white() : p.image();
		CACHE.put(key, image);
		return image;
	}

	static int frames(int kind) {
		return switch (kind) {
			case Actors.SLIME, Actors.CRYPT_SLIME, Actors.MAGMA_SLIME -> 3;
			case Actors.REVENANT, Actors.SKELETON, Actors.STALKER, Actors.VILLAGER, Actors.BANDIT, Actors.BANDIT_ARCHER -> 3;
			default -> 2;
		};
	}

	// ------------------------------------------------------------------ ramps (dark -> light)

	private static final int[] SLIME = {0xFF4A0E14, 0xFF8A1A22, 0xFFC42A33, 0xFFFF6B70};
	private static final int[] BONE_SLIME = {0xFF5A5246, 0xFF9A8E78, 0xFFD8CCB4, 0xFFF4EBDA};
	private static final int[] MAGMA = {0xFF5A1A0A, 0xFFA8320E, 0xFFFF6A2A, 0xFFFFC27A};
	private static final int[] FUR = {0xFF140E18, 0xFF2A1E30, 0xFF3E2E48, 0xFF5E4870};
	private static final int[] MEMBRANE = {0xFF1E1424, 0xFF3A2844, 0xFF56405E, 0xFF74587E};
	private static final int[] FROST = {0xFF2E5266, 0xFF4E7A92, 0xFF86AFC4, 0xFFE4F0F6};
	private static final int[] VOID = {0xFF1E1036, 0xFF3A1E6A, 0xFF6E3FD0, 0xFFB48CFF};
	private static final int[] IMP = {0xFF3A0E06, 0xFF7A2410, 0xFFC8401A, 0xFFFF7A40};
	private static final int[] SAND = {0xFF4A3A22, 0xFF7E6238, 0xFFB0905A, 0xFFE2C08A};
	private static final int[] ROT = {0xFF2E3A26, 0xFF4E5E3A, 0xFF6E8A5A, 0xFF9AB07E};
	private static final int[] RAGS = {0xFF16121A, 0xFF26222C, 0xFF3A3540, 0xFF52495A};
	private static final int[] BONE = {0xFF6E6656, 0xFF9A8E78, 0xFFCFC4AE, 0xFFF4EBDA};
	private static final int[] SHADE = {0xFF0A0614, 0xFF1E1036, 0xFF2E1A52, 0xFF4A2E80};
	private static final int[] CLOAK = {0xFF1E140E, 0xFF3A2A20, 0xFF5A3E2A, 0xFF7A5A3E};

	private static Painter paint(int kind, int f) {
		return switch (kind) {
			case Actors.SLIME, Actors.CRYPT_SLIME, Actors.MAGMA_SLIME -> slime(kind, f);
			case Actors.BAT -> bat(f);
			case Actors.FROST_WISP, Actors.VOID_WISP -> wisp(kind == Actors.VOID_WISP, f);
			case Actors.IMP -> imp(f);
			case Actors.SCUTTLER -> scuttler(f);
			case Actors.REVENANT, Actors.SKELETON, Actors.STALKER -> humanoid(kind, f);
			case Actors.VILLAGER -> villager(f % 10, f / 10);
			case Actors.BANDIT, Actors.BANDIT_ARCHER -> bandit(kind == Actors.BANDIT_ARCHER, f);
			default -> trader(f);
		};
	}

	private static Painter slime(int kind, int f) {
		int[] ramp = kind == Actors.MAGMA_SLIME ? MAGMA : kind == Actors.CRYPT_SLIME ? BONE_SLIME : SLIME;
		Painter p = new Painter(22, 18);
		double rx = f == 0 ? 10 : f == 1 ? 8.5 : 7;
		double ry = f == 0 ? 5.5 : f == 1 ? 7 : 8.5;
		double cy = 17.5 - ry;
		p.blob(11, cy, rx, ry, ramp, 0.75);
		p.flatBottom(17, ramp[0]);
		// a darker core inside the jelly
		p.blob(11.5, cy + ry * 0.25, rx * 0.45, ry * 0.4, new int[] {ramp[0], ramp[0], ramp[1], ramp[1]}, 1);
		if (kind == Actors.CRYPT_SLIME) {
			// a skull floating inside
			p.rect(9, (int) (cy + 1), 5, 4, 0xFF6E6656);
			p.px(10, (int) (cy + 2), 0xFF2A2420);
			p.px(12, (int) (cy + 2), 0xFF2A2420);
			p.rect(10, (int) (cy + 5), 3, 1, 0xFF6E6656);
		}
		if (kind == Actors.MAGMA_SLIME) {
			p.line(6, (int) cy + 2, 9, (int) cy + 4, 0xFFFFE08A);
			p.line(13, (int) cy + 1, 15, (int) cy + 4, 0xFFFFE08A);
		}
		int ey = (int) Math.round(cy - ry * 0.15);
		eye(p, 8, ey, 0xFF1A0608);
		eye(p, 13, ey, 0xFF1A0608);
		p.px(7, (int) (cy - ry * 0.55), 0xFFFFFFFF);
		p.px(8, (int) (cy - ry * 0.6), ramp[3]);
		p.outline(0xFF120708);
		return p;
	}

	private static void eye(Painter p, int x, int y, int pupil) {
		p.rect(x, y, 2, 2, 0xFFF4EFEE);
		p.px(x + 1, y + 1, pupil);
		p.px(x, y + 1, pupil);
	}

	private static Painter bat(int f) {
		Painter p = new Painter(26, 14);
		boolean up = f == 0;
		// wings: membrane with finger bones
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < 10; i++) {
				int x = 13 + side * (3 + i);
				int top = up ? 1 + i / 3 : 6 + i / 4;
				int bottom = up ? 6 + (i % 3 == 0 ? 2 : 0) : 10 + (i % 3 == 0 ? 1 : 0);
				for (int y = top; y <= bottom; y++) {
					p.px(x, y, MEMBRANE[Math.min(3, 1 + (y - top) * 3 / Math.max(1, bottom - top))]);
				}
				if (i % 3 == 0) {
					p.line(13 + side * 3, up ? 3 : 7, x, top, FUR[0]);
				}
			}
		}
		p.blob(13, 7, 4, 4.2, FUR, 1);
		p.px(10, 2, FUR[2]);
		p.px(11, 3, FUR[2]);
		p.px(16, 2, FUR[2]);
		p.px(15, 3, FUR[2]);
		p.px(11, 6, 0xFFFF4A52);
		p.px(15, 6, 0xFFFF4A52);
		p.px(12, 9, 0xFFF4EFEE);
		p.px(14, 9, 0xFFF4EFEE);
		p.outline(0xFF08050A);
		return p;
	}

	private static Painter wisp(boolean dark, int f) {
		int[] ramp = dark ? VOID : FROST;
		Painter p = new Painter(16, 20);
		p.blob(8, 7, 5.5, 5.5, ramp, 0.9);
		// a flame-like tail that flickers between frames
		for (int i = 0; i < 8; i++) {
			int w = Math.max(0, 4 - i / 2);
			int x = 8 + (f == 0 ? (i % 3 == 1 ? 1 : 0) : (i % 3 == 2 ? -1 : 0));
			for (int dx = -w; dx <= w; dx++) {
				p.px(x + dx, 12 + i, ramp[Math.max(0, 2 - i / 3)]);
			}
		}
		p.blob(8, 7, 2.5, 2.5, new int[] {ramp[2], ramp[3], ramp[3], 0xFFFFFFFF}, 1);
		if (dark) {
			p.rect(6, 6, 4, 2, 0xFFEADCFF);
			p.px(8, 6, 0xFF1A0A30);
			p.px(8, 7, 0xFF1A0A30);
		} else {
			p.px(6, 6, 0xFF1E3442);
			p.px(10, 6, 0xFF1E3442);
		}
		p.outline(dark ? 0xFF0A0418 : 0xFF14222C);
		return p;
	}

	private static Painter imp(int f) {
		Painter p = new Painter(26, 24);
		boolean up = f == 0;
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < 8; i++) {
				int x = 13 + side * (4 + i);
				int top = up ? 3 + i / 2 : 9 + i / 3;
				for (int y = top; y < top + 5 - i / 3; y++) {
					p.px(x, y, IMP[y == top ? 1 : 0]);
				}
			}
		}
		p.blob(13, 13, 4.5, 6, IMP, 1);
		p.blob(13, 6, 4, 3.6, IMP, 1);
		p.px(10, 2, 0xFF2A0A04);
		p.px(10, 3, 0xFF3A140A);
		p.px(16, 2, 0xFF2A0A04);
		p.px(16, 3, 0xFF3A140A);
		p.px(11, 6, 0xFFFFE08A);
		p.px(15, 6, 0xFFFFE08A);
		p.rect(12, 8, 3, 1, 0xFF2A0A04);
		p.line(13, 19, 15, 23, IMP[1]);
		p.px(16, 23, 0xFFFFC27A);
		p.outline(0xFF1A0402);
		return p;
	}

	private static Painter scuttler(int f) {
		Painter p = new Painter(30, 16);
		for (int i = 0; i < 3; i++) {
			int x = 9 + i * 5;
			int lift = (i + f) % 2 == 0 ? 0 : 1;
			p.line(x, 11, x - 2, 15 - lift, SAND[0]);
			p.line(x + 1, 11, x + 3, 15 - (1 - lift), SAND[0]);
		}
		p.blob(15, 10, 8, 3.2, SAND, 1);
		// segmented tail curling over the back, stinger glinting
		int[][] tail = {{7, 9}, {5, 7}, {4, 5}, {5, 3}, {7, 2}, {9, 2}};
		for (int[] t : tail) {
			p.blob(t[0], t[1], 1.6, 1.4, SAND, 1);
		}
		p.px(10, 3, 0xFFF4EBDA);
		p.px(11, 3, 0xFF2A1A10);
		// claws
		p.blob(25, 8, 2.4, 1.8, SAND, 1);
		p.px(27, 7, SAND[3]);
		p.px(27, 9, SAND[0]);
		p.px(22, 9, 0xFF1A1010);
		p.px(22, 8, 0xFFF4EFEE);
		p.outline(0xFF1A120A);
		return p;
	}

	private static Painter humanoid(int kind, int f) {
		Painter p = new Painter(18, 34);
		int[] skin = kind == Actors.REVENANT ? ROT : kind == Actors.SKELETON ? BONE : SHADE;
		int[] cloth = kind == Actors.REVENANT ? RAGS : kind == Actors.SKELETON ? BONE : SHADE;
		int step = f == 0 ? 0 : f == 1 ? 2 : -2;
		// legs (cylinder shading)
		p.limb(6 + step, 22, 3, 12, cloth);
		p.limb(9 - step, 22, 3, 12, cloth);
		// torso
		if (kind == Actors.SKELETON) {
			p.limb(8, 12, 2, 11, BONE);
			for (int r = 0; r < 4; r++) {
				p.rect(5, 13 + r * 2, 8, 1, BONE[2 + (r == 0 ? 1 : 0)]);
			}
			p.rect(5, 21, 8, 2, BONE[1]);
		} else {
			p.limb(5, 12, 8, 11, cloth);
			if (kind == Actors.REVENANT) {
				p.px(7, 15, ROT[2]);
				p.px(10, 18, ROT[2]);
				p.rect(5, 21, 8, 1, 0xFF3A2A20);
			}
		}
		// arms: revenants reach forward
		if (kind == Actors.REVENANT) {
			p.rect(11, 13, 7, 2, ROT[2]);
			p.rect(11, 15, 7, 1, ROT[0]);
		} else {
			p.limb(3, 12 + (f == 1 ? 1 : 0), 2, 10, skin);
			p.limb(13, 12 + (f == 2 ? 1 : 0), 2, 10, skin);
		}
		// head
		p.blob(9, 6.5, 4.2, 4.6, skin, 1);
		if (kind == Actors.SKELETON) {
			p.rect(7, 6, 2, 2, 0xFF1A1410);
			p.rect(10, 6, 2, 2, 0xFF1A1410);
			p.rect(7, 9, 5, 1, 0xFF6E6656);
			p.px(8, 9, 0xFF1A1410);
			p.px(10, 9, 0xFF1A1410);
		} else if (kind == Actors.STALKER) {
			// hood, glowing eyes, smoke trailing off the shoulders
			p.blob(9, 6, 5.2, 5.6, SHADE, 0.9);
			p.px(7, 7, 0xFFC9A8FF);
			p.px(11, 7, 0xFFC9A8FF);
			p.px(15, 13, SHADE[2]);
			p.px(16, 11, SHADE[1]);
			p.px(2, 12, SHADE[2]);
		} else {
			p.px(7, 6, 0xFFE0333A);
			p.px(11, 6, 0xFFE0333A);
			p.rect(7, 9, 4, 1, ROT[0]);
		}
		p.outline(0xFF0A080C);
		return p;
	}

	private static final int[] SKIN = {0xFF6A4030, 0xFF9A6448, 0xFFC98E6A, 0xFFE8B896};
	private static final int[] PANTS = {0xFF1E1610, 0xFF33261C, 0xFF4A3828, 0xFF62503A};
	private static final int[][] TUNICS = {
			{0xFF1E3418, 0xFF2E5226, 0xFF467A3A, 0xFF6EA05A},
			{0xFF14243A, 0xFF22406A, 0xFF3A6496, 0xFF6A92C0},
			{0xFF3A2410, 0xFF6A4218, 0xFF9A6A28, 0xFFC99A4A}};
	private static final int[] LEATHER = {0xFF120E0C, 0xFF221A16, 0xFF362A22, 0xFF4E3E32};

	/** Villagers: three outfits (straw hat, hood, bearded elder), walking frames. */
	private static Painter villager(int f, int variant) {
		Painter p = new Painter(18, 34);
		int[] tunic = TUNICS[variant % 3];
		int step = f == 0 ? 0 : f == 1 ? 2 : -2;
		p.limb(6 + step, 23, 3, 11, PANTS);
		p.limb(9 - step, 23, 3, 11, PANTS);
		p.rect(5 + step, 33, 4, 1, 0xFF120A06);
		p.rect(9 - step, 33, 4, 1, 0xFF120A06);
		p.limb(5, 12, 8, 12, tunic);
		p.rect(5, 21, 8, 1, 0xFF3A2410); // belt
		p.px(9, 21, 0xFFE0B040);
		p.limb(3, 13 + (f == 1 ? 1 : 0), 2, 9, tunic);
		p.limb(13, 13 + (f == 2 ? 1 : 0), 2, 9, tunic);
		p.px(3, 22, SKIN[2]);
		p.px(14, 22, SKIN[2]);
		p.blob(9, 7, 4, 4.4, SKIN, 1);
		p.px(7, 7, 0xFF1A1010);
		p.px(11, 7, 0xFF1A1010);
		p.px(9, 9, SKIN[0]);
		switch (variant % 3) {
			case 0 -> { // straw hat
				p.rect(3, 3, 13, 1, 0xFFC9A04A);
				p.rect(5, 1, 9, 2, 0xFFE0B860);
				p.rect(5, 2, 9, 1, 0xFF8E6A2A);
			}
			case 1 -> { // hood
				p.blob(9, 6, 5, 5, tunic, 0.9);
				p.blob(9, 7.5, 3.2, 3.2, SKIN, 1);
				p.px(8, 7, 0xFF1A1010);
				p.px(10, 7, 0xFF1A1010);
			}
			default -> { // bald elder with a beard
				p.rect(6, 2, 7, 1, SKIN[1]);
				p.rect(6, 9, 7, 4, 0xFFD8D0C8);
				p.rect(7, 13, 5, 1, 0xFFB8B0A8);
				p.px(8, 9, SKIN[0]);
			}
		}
		p.outline(0xFF0A0806);
		return p;
	}

	/** Bandits: dark leather, a red mask over the face; archers carry a bow and a quiver. */
	private static Painter bandit(boolean archer, int f) {
		Painter p = new Painter(20, 34);
		int step = f == 0 ? 0 : f == 1 ? 2 : -2;
		if (archer) {
			p.rect(2, 12, 3, 9, 0xFF5A3A28); // quiver
			p.px(2, 11, 0xFFE8E0D0);
			p.px(4, 10, 0xFFE8E0D0);
		}
		p.limb(7 + step, 23, 3, 11, LEATHER);
		p.limb(10 - step, 23, 3, 11, LEATHER);
		p.limb(6, 12, 8, 12, LEATHER);
		p.line(6, 13, 13, 21, 0xFF8E1A26); // sash
		p.limb(4, 13 + (f == 1 ? 1 : 0), 2, 9, LEATHER);
		p.limb(14, 13 + (f == 2 ? 1 : 0), 2, 9, LEATHER);
		p.blob(10, 7, 4.2, 4.5, SKIN, 1);
		p.blob(10, 5.5, 4.8, 4, LEATHER, 0.9); // hood
		p.rect(6, 8, 9, 3, 0xFF8E1A26); // mask
		p.rect(6, 8, 9, 1, 0xFFC42E3A);
		p.px(8, 6, 0xFFFFE08A);
		p.px(12, 6, 0xFFFFE08A);
		if (archer) {
			for (int y = 10; y < 26; y++) {
				int x = 17 + (int) Math.round(Math.sin((y - 10) / 15.0 * Math.PI) * 2);
				p.px(x, y, 0xFF8A5A3A);
			}
			p.line(16, 10, 16, 25, 0xFFE8E0D0);
		} else {
			p.line(16, 20, 19, 10, 0xFFC9C0B8); // a short blade
			p.px(16, 21, 0xFF5A3A28);
		}
		p.outline(0xFF060404);
		return p;
	}

	private static Painter trader(int f) {
		Painter p = new Painter(20, 36);
		p.limb(4, 12, 12, 23, CLOAK);
		for (int y = 26; y < 35; y++) {
			p.px(3, y, CLOAK[1]);
			p.px(16, y, CLOAK[0]);
		}
		// hood
		p.blob(10, 7, 5.5, 6, CLOAK, 0.9);
		p.rect(7, 7, 6, 4, 0xFF120A08);
		p.px(9, 8, 0xFFFFD27A);
		p.px(11, 8, 0xFFFFD27A);
		// satchel full of maps
		p.rect(2, 18, 6, 7, 0xFFB08A55);
		p.rect(2, 18, 6, 1, 0xFFC9A46A);
		p.rect(3, 16, 2, 2, 0xFFE6D6B4);
		p.rect(5, 15, 2, 3, 0xFFE6D6B4);
		// lantern on a hook
		p.line(15, 14, 17, 17, 0xFF8A8590);
		p.rect(16, 18, 3, 4, f == 0 ? 0xFFFFC04A : 0xFFFFD27A);
		p.px(17, 19, 0xFFFFF4D0);
		p.rect(16, 17, 3, 1, 0xFF5C5560);
		p.outline(0xFF0E0806);
		return p;
	}

	// ------------------------------------------------------------------ painter

	static final class Painter {
		final int w;
		final int h;
		final int[] p;

		Painter(int w, int h) {
			this.w = w;
			this.h = h;
			this.p = new int[w * h];
		}

		void px(int x, int y, int c) {
			if (x >= 0 && x < w && y >= 0 && y < h) {
				p[y * w + x] = c;
			}
		}

		void rect(int x, int y, int rw, int rh, int c) {
			for (int j = y; j < y + rh; j++) {
				for (int i = x; i < x + rw; i++) {
					px(i, j, c);
				}
			}
		}

		void line(int x0, int y0, int x1, int y1, int c) {
			int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
			for (int i = 0; i <= n; i++) {
				px(x0 + (x1 - x0) * i / Math.max(1, n), y0 + (y1 - y0) * i / Math.max(1, n), c);
			}
		}

		/** A lit ellipsoid: normal-based light from the top left, dithered across the ramp. */
		void blob(double cx, double cy, double rx, double ry, int[] ramp, double gain) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					double nx = (x + 0.5 - cx) / rx;
					double ny = (y + 0.5 - cy) / ry;
					double d = nx * nx + ny * ny;
					if (d > 1) {
						continue;
					}
					double nz = Math.sqrt(1 - d);
					double light = (-0.55 * nx - 0.65 * ny + 0.55 * nz) * gain;
					double level = Math.max(0, Math.min(3.999, (light + 0.35) * 3.2));
					int base = (int) level;
					int index = (level - base) * 16 > BAYER[y & 3][x & 3] ? base + 1 : base;
					p[y * w + x] = ramp[Math.min(3, index)];
				}
			}
		}

		/** A cylinder-shaded column (limbs, torsos): light on the left, dark on the right. */
		void limb(int x, int y, int lw, int lh, int[] ramp) {
			for (int i = 0; i < lw; i++) {
				double t = lw == 1 ? 0.5 : i / (double) (lw - 1);
				int c = ramp[t < 0.25 ? 3 : t < 0.6 ? 2 : t < 0.85 ? 1 : 0];
				for (int j = 0; j < lh; j++) {
					px(x + i, y + j, j == lh - 1 ? ramp[0] : c);
				}
			}
		}

		void flatBottom(int row, int c) {
			for (int x = 0; x < w; x++) {
				if (p[row * w + x] != 0) {
					p[row * w + x] = c;
				}
			}
		}

		void outline(int c) {
			int[] copy = p.clone();
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					if (copy[y * w + x] != 0) {
						continue;
					}
					boolean edge = (x > 0 && copy[y * w + x - 1] != 0) || (x < w - 1 && copy[y * w + x + 1] != 0)
							|| (y > 0 && copy[(y - 1) * w + x] != 0) || (y < h - 1 && copy[(y + 1) * w + x] != 0);
					if (edge) {
						p[y * w + x] = c;
					}
				}
			}
		}

		Image image() {
			WritableImage image = new WritableImage(w, h);
			image.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), p, 0, w);
			return image;
		}

		Image white() {
			int[] q = new int[p.length];
			for (int i = 0; i < p.length; i++) {
				q[i] = p[i] == 0 ? 0 : 0xFFFFFFFF;
			}
			WritableImage image = new WritableImage(w, h);
			image.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), q, 0, w);
			return image;
		}
	}
}
