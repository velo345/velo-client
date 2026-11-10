package net.veloclient.launcher.sandbox;

import javafx.scene.image.Image;
import net.veloclient.launcher.sandbox.MobArt.Painter;

import java.util.HashMap;
import java.util.Map;

/**
 * Pixel art for the three bosses, painted part by part (bodies, weapons, arms, hands) so they can still swing, slam and
 * flicker between phases. Lit volumes, cylinder-shaded limbs, outlines, trims and glints; cached per frame.
 */
final class BossArt {

	private static final Map<String, Image> CACHE = new HashMap<>();

	private BossArt() {
	}

	static void clear() {
		CACHE.clear();
	}

	static Image get(String part, int frame, boolean white) {
		String key = part + frame + white;
		Image cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		Painter p = switch (part) {
			case "warden" -> warden(frame % 10, frame >= 10);
			case "warden_sword" -> wardenSword();
			case "golem" -> golem(frame % 10, frame >= 10);
			case "golem_arm" -> golemArm();
			case "herald" -> herald(frame % 10, frame >= 10);
			default -> heraldHand();
		};
		Image image = white ? p.white() : p.image();
		CACHE.put(key, image);
		return image;
	}

	private static final int[] STEEL = {0xFF221E28, 0xFF423C4A, 0xFF6E6678, 0xFFB0A8BA};
	private static final int[] DARK_STEEL = {0xFF141218, 0xFF26222C, 0xFF3A3542, 0xFF5A5466};
	private static final int[] CRIMSON = {0xFF2E080C, 0xFF5A0E18, 0xFF8E1A26, 0xFFC42E3A};
	private static final int[] BONE = {0xFF6E6656, 0xFF9A8E78, 0xFFCFC4AE, 0xFFF4EBDA};
	private static final int[] ROCK = {0xFF221A1C, 0xFF3E302E, 0xFF5E4A42, 0xFF8A725E};
	private static final int[] LAVA = {0xFF8A2A0A, 0xFFE0501E, 0xFFFFA040, 0xFFFFE6A0};
	private static final int[] ROBE = {0xFF0C0618, 0xFF1C1034, 0xFF2E1A54, 0xFF4A2E80};
	private static final int[] TRIM = {0xFF5B3BA8, 0xFF8A5AE0, 0xFFC9A8FF, 0xFFF0E6FF};
	private static final int[] GOLD = {0xFF6A4A10, 0xFFA87A20, 0xFFE0B040, 0xFFFFE8A0};

	// ------------------------------------------------------------------ Crypt Warden (40 x 72)

	/** 0 idle, 1-2 walking, 3 crouched (wind-up). */
	private static Painter warden(int frame, boolean rage) {
		Painter p = new Painter(44, 74);
		int crouch = frame == 3 ? 3 : 0;
		int step = frame == 1 ? 2 : frame == 2 ? -2 : 0;
		// tattered cape behind
		for (int y = 18 + crouch; y < 66; y++) {
			int w = 9 + (y - 18) / 6;
			for (int x = 22 - w; x <= 22 + w; x++) {
				if (y > 60 && ((x * 7 + y * 3) % 5 == 0)) {
					continue; // ragged hem
				}
				double t = (x - (22 - w)) / (double) (2 * w);
				p.px(x, y, CRIMSON[t < 0.25 ? 2 : t < 0.7 ? 1 : 0]);
			}
		}
		// legs: greaves with knee plates and heavy sabatons
		p.limb(14 + step, 50, 6, 20, STEEL);
		p.limb(24 - step, 50, 6, 20, STEEL);
		p.blob(17 + step, 56, 3.4, 2.6, STEEL, 1);
		p.blob(27 - step, 56, 3.4, 2.6, STEEL, 1);
		p.rect(12 + step, 69, 9, 4, DARK_STEEL[2]);
		p.rect(12 + step, 69, 9, 1, DARK_STEEL[3]);
		p.rect(23 - step, 69, 9, 4, DARK_STEEL[2]);
		p.rect(23 - step, 69, 9, 1, DARK_STEEL[3]);
		// tabard hanging between the legs
		for (int y = 46 + crouch; y < 62; y++) {
			for (int x = 18; x < 27; x++) {
				p.px(x, y, CRIMSON[x < 20 ? 3 : x < 24 ? 2 : 1]);
			}
		}
		p.rect(21, 48 + crouch, 3, 10, GOLD[2]);
		// breastplate
		p.blob(22, 36 + crouch, 11, 12, STEEL, 1);
		p.line(22, 27 + crouch, 22, 46 + crouch, STEEL[0]);
		p.rect(13, 45 + crouch, 19, 3, DARK_STEEL[1]);
		p.rect(19, 44 + crouch, 7, 5, BONE[2]); // skull buckle
		p.px(20, 45 + crouch, BONE[0]);
		p.px(23, 45 + crouch, BONE[0]);
		p.rect(21, 47 + crouch, 3, 1, BONE[0]);
		// pauldrons with spikes
		p.blob(10, 28 + crouch, 7, 5.5, STEEL, 1);
		p.blob(34, 28 + crouch, 7, 5.5, STEEL, 1);
		for (int i = 0; i < 3; i++) {
			p.line(6 + i * 3, 24 + crouch, 5 + i * 3, 19 + crouch, BONE[2 + (i % 2)]);
			p.line(32 + i * 3, 24 + crouch, 33 + i * 3, 19 + crouch, BONE[2 + (i % 2)]);
		}
		// great helm with a visor slit and horns
		p.blob(22, 17 + crouch, 8, 9, STEEL, 1);
		p.rect(15, 18 + crouch, 15, 8, STEEL[2]);
		p.rect(15, 18 + crouch, 1, 8, STEEL[3]);
		p.rect(29, 18 + crouch, 1, 8, STEEL[1]);
		p.rect(16, 18 + crouch, 13, 3, 0xFF0A0608);
		p.rect(21, 21 + crouch, 3, 5, 0xFF0A0608);
		for (int i = 0; i < 9; i++) {
			p.px(13 - i / 2, 12 + crouch - i, BONE[i < 5 ? 2 : 3]);
			p.px(14 - i / 2, 12 + crouch - i, BONE[1]);
			p.px(31 + i / 2, 12 + crouch - i, BONE[i < 5 ? 2 : 3]);
			p.px(30 + i / 2, 12 + crouch - i, BONE[1]);
		}
		if (rage) {
			// burning hem, red-hot horn tips and spikes, the cape scorched
			for (int x = 6; x < 39; x++) {
				for (int y = 58; y < 66; y++) {
					if (p.p[y * p.w + x] != 0 && ((x * 13 + y * 7) % 6 == 0)) {
						p.px(x, y, LAVA[(x + y) % 3 + 1]);
					}
				}
			}
			for (int i = 5; i < 9; i++) {
				p.px(13 - i / 2, 12 + crouch - i, LAVA[2]);
				p.px(31 + i / 2, 12 + crouch - i, LAVA[2]);
			}
			for (int i = 0; i < 3; i++) {
				p.px(5 + i * 3, 19 + crouch, LAVA[3]);
				p.px(33 + i * 3, 19 + crouch, LAVA[3]);
			}
		}
		p.outline(0xFF060406);
		return p;
	}

	private static Painter wardenSword() {
		Painter p = new Painter(12, 46);
		p.limb(5, 0, 3, 9, DARK_STEEL); // grip
		p.rect(4, 0, 5, 2, GOLD[2]);
		p.rect(0, 9, 13, 3, GOLD[1]); // cross-guard
		p.rect(0, 9, 13, 1, GOLD[3]);
		for (int y = 12; y < 44; y++) {
			int w = y > 38 ? 44 - y : 5;
			for (int x = 6 - w / 2; x < 6 - w / 2 + w; x++) {
				p.px(x, y, x == 6 ? BONE[1] : x < 6 ? BONE[3] : BONE[2]);
			}
		}
		for (int y = 14; y < 36; y += 4) {
			p.px(6, y, CRIMSON[3]); // runes down the fuller
		}
		p.outline(0xFF060406);
		return p;
	}

	// ------------------------------------------------------------------ Furnace Golem (52 x 82)

	private static Painter golem(int frame, boolean rage) {
		Painter p = new Painter(56, 84);
		int step = frame == 1 ? 2 : frame == 2 ? -2 : 0;
		// stumpy legs
		p.blob(18 + step, 72, 8, 10, ROCK, 1);
		p.blob(38 - step, 72, 8, 10, ROCK, 1);
		// massive body
		p.blob(28, 46, 22, 24, ROCK, 1);
		// molten veins running through the stone
		int[][] veins = {{12, 38, 18, 48}, {18, 48, 16, 60}, {40, 34, 44, 44}, {44, 44, 38, 58}, {24, 62, 30, 68}, {34, 26, 30, 32}};
		for (int[] v : veins) {
			p.line(v[0], v[1], v[2], v[3], LAVA[rage ? 2 : 1]);
			p.line(v[0] + 1, v[1], v[2] + 1, v[3], LAVA[rage ? 3 : 2]);
		}
		if (rage) {
			// split open: more cracks, bright magma showing through everywhere
			int[][] more = {{8, 52, 14, 64}, {46, 50, 42, 66}, {20, 30, 26, 36}, {36, 60, 40, 70}, {10, 44, 6, 50}, {48, 40, 52, 46}};
			for (int[] v : more) {
				p.line(v[0], v[1], v[2], v[3], LAVA[2]);
				p.line(v[0], v[1] + 1, v[2], v[3] + 1, LAVA[3]);
			}
		}
		// the furnace mouth in its chest (the core glows over it at runtime)
		p.blob(28, 44, 8, 7, new int[] {0xFF1A0A06, 0xFF2A1208, 0xFF3A1A0A, 0xFF4A220E}, 1);
		p.rect(21, 37, 15, 2, DARK_STEEL[2]);
		p.rect(21, 51, 15, 2, DARK_STEEL[2]);
		for (int x = 22; x < 36; x += 3) {
			p.rect(x, 39, 1, 12, DARK_STEEL[1]); // grate
		}
		// head sunk between the shoulders
		p.blob(28, 18, 9, 8, ROCK, 1);
		p.rect(20, 14, 17, 3, ROCK[0]); // heavy brow
		p.rect(22, 18, 4, 2, LAVA[3]);
		p.rect(31, 18, 4, 2, LAVA[3]);
		p.rect(24, 23, 9, 1, LAVA[rage ? 3 : 1]);
		if (rage) {
			p.line(22, 10, 26, 16, LAVA[2]);
			p.line(34, 10, 31, 15, LAVA[2]);
		}
		// shoulder boulders
		p.blob(8, 30, 8, 7, ROCK, 1);
		p.blob(48, 30, 8, 7, ROCK, 1);
		p.outline(0xFF0A0606);
		return p;
	}

	private static Painter golemArm() {
		Painter p = new Painter(18, 34);
		p.limb(5, 0, 8, 20, ROCK);
		p.line(8, 4, 9, 16, LAVA[1]);
		p.blob(9, 26, 8.5, 7.5, ROCK, 1); // fist
		p.line(4, 25, 13, 25, ROCK[0]);
		p.line(4, 28, 13, 28, ROCK[0]);
		p.px(6, 22, LAVA[2]);
		p.outline(0xFF0A0606);
		return p;
	}

	// ------------------------------------------------------------------ Void Herald (48 x 66)

	/** 0/1: the robe's hem ripples. */
	private static Painter herald(int frame, boolean rage) {
		Painter p = new Painter(50, 68);
		int[] trim = rage ? new int[] {0xFF8A1218, 0xFFD7262D, 0xFFFF6B70, 0xFFFFD0D4} : TRIM;
		// flowing robe widening to a ragged, rippling hem
		for (int y = 18; y < 64; y++) {
			int half = 6 + (y - 18) * 11 / 46;
			double wave = Math.sin(y * 0.4 + frame * Math.PI) * 1.5;
			for (int x = (int) (25 - half + wave); x <= (int) (25 + half + wave); x++) {
				if (y > 56 && ((x + frame * 2 + y) % 4 == 0)) {
					continue;
				}
				double t = (x - (25 - half + wave)) / (2.0 * half);
				int c = ROBE[t < 0.2 ? 3 : t < 0.5 ? 2 : t < 0.85 ? 1 : 0];
				p.px(x, y, c);
			}
		}
		// trims down the front and along the hem
		for (int y = 22; y < 60; y++) {
			p.px(24, y, trim[1]);
			p.px(26, y, trim[2]);
		}
		for (int x = 12; x < 39; x += 2) {
			p.px(x + (frame == 0 ? 0 : 1), 55, trim[2]);
		}
		// runes on the robe
		int[][] runes = {{18, 32}, {32, 36}, {20, 44}, {30, 48}};
		for (int[] r : runes) {
			p.px(r[0], r[1], trim[3]);
			p.px(r[0] + 1, r[1] + 1, trim[1]);
			p.px(r[0] - 1, r[1] + 1, trim[1]);
		}
		// collar of shards
		for (int i = 0; i < 7; i++) {
			int x = 15 + i * 3;
			p.line(x, 20, x - 1 + (i % 2) * 2, 15 - (i % 3), trim[i % 2 == 0 ? 2 : 1]);
		}
		// deep hood, darkness inside, the eye goes in at runtime
		p.blob(25, 12, 9, 10, ROBE, 1);
		p.blob(25, 13, 6, 7, new int[] {0xFF020104, 0xFF06040C, 0xFF0A0614, 0xFF0E0A1C}, 1);
		p.line(16, 6, 25, 1, trim[2]);
		p.line(34, 6, 25, 1, trim[1]);
		p.outline(0xFF04020A);
		return p;
	}

	private static Painter heraldHand() {
		Painter p = new Painter(14, 14);
		p.blob(7, 7, 4.5, 4, ROBE, 1);
		for (int i = 0; i < 4; i++) {
			p.line(4 + i * 2, 10, 3 + i * 2, 13, TRIM[2]); // claws
		}
		p.px(6, 5, TRIM[3]);
		p.outline(0xFF04020A);
		return p;
	}
}
