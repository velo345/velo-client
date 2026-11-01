package net.veloclient.velo.client.cosmetics.aura;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The auras' animations - plain Java (no Minecraft classes), so the launcher compiles the same
 * file for its previews (see the launcher's bundleAuraEffects task).
 *
 * <p>An {@link Emitter} per player keeps the state: particles live in <b>world</b> space (so they're
 * left behind when you move, like Lunar's), while rings, halos and arcs are drawn around the player.
 * Everything comes out as glowing {@link Out#sprite sprites} and {@link Out#beam beams} relative to
 * the player's feet (y up, world axes, blocks) - the game turns them into additive quads, the
 * launcher into soft dots on a canvas.
 */
public final class AuraEffects {

	public enum Kind {
		/** Three tilted rings of stars circling you, each with a comet tail. */
		ORBIT,
		/** Embers spiralling up from your feet in a double helix. */
		EMBER,
		/** A crown of ice above your head and snow falling around you. */
		FROST,
		/** A pulsing rune circle at your feet pulling wisps in. */
		VOID,
		/** Lightning arcs crackling around your body, throwing sparks. */
		STORM,
		/** Green code raining down a cylinder of light around you. */
		MATRIX,
		/** A roaring column of fire. */
		INFERNO,
		/** Vines growing up around you, leafing out and flowering, with fireflies. */
		BLOOM,
		/** A ring of hearts pulsing to a heartbeat, more floating up. */
		HEART,
		/** Northern-lights curtains waving around you. */
		AURORA,
		/** A hologram: a hexagon plate under you, scan rings sweeping up your body, data glyphs orbiting, bits of light rising. */
		HOLOGRAM,
		/** Christmas: a garland of lights spiralling up around you, baubles circling, a star above and snow drifting down. */
		FESTIVE,
		/** A halo above your head, light falling around you and feathers drifting down, rocking as they fall. */
		SERAPH,
		/** Cherry-blossom petals whirling up around you in a spiral of wind, blossoms circling your feet. */
		SAKURA,
		/** A spiral galaxy turning at your feet, nebula clouds in its arms, star dust rising and shooting stars. */
		GALAXY,
		/** Amethyst crystals floating around you, turning and flashing, shedding sparkles over a ring of small crystals. */
		CRYSTAL,
		/** Soul flames circling like will-o'-wisps, trailing ghostly smoke, over a ring of small blue flames. */
		SOUL,
		/** Glowing butterflies fluttering around you, wings beating, dropping glitter. */
		BUTTERFLY,
		/** An equalizer ring bouncing to a beat, shockwaves across the ground on every kick. */
		BASS,
		/** Rockets launching around you and bursting overhead into crackling showers of sparks. */
		FIREWORK,
		/** Two dragons, yin and yang, chasing each other round you over a turning yin-yang, breathing sparks. */
		DRAGON,
		/** Silk ribbons of light twisting and rippling around you, shedding shimmering dust. */
		RIBBON,
		/** A little rain cloud over your head: rain, splashes at your feet, now and then a lightning bolt. */
		RAIN,
		/** A glowing pond at your feet: koi circling, lily pads drifting, ripples spreading. */
		KOI,
		/** Spectral swords hovering tip-down in a ring around you, one at a time diving into the ground. */
		BLADES,
		/** Paper sky lanterns rising around you from a ring of tea lights. */
		LANTERN,
		/** A clock dial at your feet with sweeping hands, gyroscope rings and turning gears. */
		CHRONO,
		/** Bedtime: a crescent moon rocking over your head, a ring of stars twinkling round it, sleepy Zzz floating up, little clouds at your feet. */
		PYJAMA
	}

	/** Sprite shapes: a soft glowing ball, a four-pointed star, a flat glow on the ground plane, a heart, a leaf. */
	public static final int GLOW = 0;
	public static final int STAR = 1;
	public static final int DISC = 2;
	public static final int HEART = 3;
	public static final int LEAF = 4;
	/** More shapes: a thin ring, a smoke puff, a feather, a blossom petal, a crystal, a flame tongue (tip up), a lens sparkle. */
	public static final int HALO = 5;
	public static final int SMOKE = 6;
	public static final int FEATHER = 7;
	public static final int PETAL = 8;
	public static final int SHARD = 9;
	public static final int FLAME = 10;
	public static final int SPARKLE = 11;
	/** The yin-yang symbol (Spirit Dragons' ground). */
	public static final int TAIJI = 12;
	/** A koi seen from above (head up), a sword (tip up), a paper lantern, a gear, a lily pad, a clock dial. */
	public static final int KOI = 13;
	public static final int BLADE = 14;
	public static final int LANTERN = 15;
	public static final int GEAR = 16;
	public static final int LILY = 17;
	public static final int DIAL = 18;
	/** For the kill effects: a pixel, a crescent slash (bright edge up), a ghost, a starburst, ground cracks. */
	public static final int PIXEL = 19;
	public static final int SLASH = 20;
	public static final int GHOST = 21;
	public static final int BURST = 22;
	public static final int CRACKS = 23;
	/** A gravestone (R.I.P.) and a coin, for the kill effects. */
	public static final int TOMB = 24;
	public static final int COIN = 25;
	/** A great feathered wing (hinge on the left, leading edge up), for {@link Out#quad}. */
	public static final int BIRD_WING = 26;
	/**
	 * How far (blocks) a preview of {@code kind} is moved down so its top isn't cut off: previews frame about 2.2 blocks
	 * above the feet, and a few auras float higher (Pyjama's moon).
	 */
	public static float previewDrop(Kind kind) {
		return kind == Kind.PYJAMA ? 0.35f : 0f;
	}

	/** A crescent moon (horns to the right) and a sleepy "Z", for Pyjama. */
	public static final int MOON = 27;
	public static final int SNOOZE = 28;
	/** Added to a shape: only its ground-plane quad (a ring lying flat) / only its two upright quads (a flame standing up). */
	public static final int FLAT = 1 << 8;
	public static final int UPRIGHT = 1 << 9;
	/**
	 * Added to a shape: one upright quad turned to face whoever looks at it, instead of two crossed ones - for
	 * shapes with a front that must read the right way round (the moon, "Z"), which crossed quads show mirrored,
	 * doubled or edge-on depending on where you stand.
	 */
	public static final int FACING = 1 << 10;

	/** Atlas layout: {@link #ATLAS_COLUMNS} x {@link #ATLAS_ROWS} cells of {@link #ATLAS_CELL} pixels. */
	public static final int ATLAS_CELL = 256;
	public static final int ATLAS_COLUMNS = 8;
	public static final int ATLAS_ROWS = 6;

	/** Atlas cells (8 x 6 grid, see tools/cosmetics/make_aura_atlas.py) for {@link Out#panel}. */
	public static final int CELL_CURTAIN = 5;
	public static final int CELL_GLYPH = 8;
	/** A butterfly wing (hinge on the left edge, front at the top) and a silk band's profile, for {@link Out#quad}. */
	public static final int CELL_WING = 29;
	public static final int CELL_RIBBON = 30;
	/** The code-rain glyphs, in atlas order from {@link #CELL_GLYPH} (drawn mirrored there). */
	public static final String GLYPHS = "0123457ｱｶｻﾀﾅﾊﾏﾗﾜ";

	/** Colour choices: 0 = the aura's own colours, then these, then rainbow. */
	public static final int[] PALETTE = {0xFF4D4D, 0xFF8A1F, 0xFFD84D, 0x5CFF7A, 0x3FF0FF, 0x4D7CFF, 0xA65CFF, 0xFF4FD8, 0xF4F6FF};
	public static final String[] PALETTE_NAMES = {"Red", "Orange", "Gold", "Green", "Cyan", "Blue", "Purple", "Pink", "White"};
	public static final int RAINBOW = PALETTE.length + 1;

	/**
	 * How an aura is worn: {@code size} (0.5-1.6), {@code speed} (0.25-2.5), {@code density} (0.25-2),
	 * {@code color} (0 = default, 1..9 = {@link #PALETTE}, {@link #RAINBOW}).
	 */
	public record Settings(float size, float speed, float density, int color) {
		public static final Settings DEFAULT = new Settings(1f, 1f, 1f, 0);

		public Settings {
			size = clamp(size, 0.5f, 1.6f, 1f);
			speed = clamp(speed, 0.25f, 2.5f, 1f);
			density = clamp(density, 0.25f, 2f, 1f);
			color = color < 0 || color > RAINBOW ? 0 : color;
		}

		private static float clamp(float v, float min, float max, float fallback) {
			return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
		}

		/** "100;100;100;0" - percent and the colour index, network-safe. */
		public String encode() {
			return Math.round(size * 100) + ";" + Math.round(speed * 100) + ";" + Math.round(density * 100) + ";" + color;
		}

		public static Settings decode(String[] parts, int from) {
			try {
				return new Settings(Integer.parseInt(parts[from]) / 100f, Integer.parseInt(parts[from + 1]) / 100f,
						Integer.parseInt(parts[from + 2]) / 100f, Integer.parseInt(parts[from + 3]));
			} catch (RuntimeException e) {
				return DEFAULT;
			}
		}
	}

	/** Where the effects go. Positions are relative to the player's feet; colours are 0xRRGGBB. */
	public interface Out {
		void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape);

		void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha);

		/**
		 * A flat upright card {@code w} x {@code h} centred on (x, y, z), facing outwards along (nx, nz),
		 * showing atlas {@code cell} (a code glyph, a light curtain).
		 */
		void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha);

		/**
		 * A sprite turned by {@code rot} radians within each of its quads (tumbling petals, rocking feathers). Lying flat,
		 * its top points along (-sin rot, -cos rot) in x/z - so rot = -a - pi/2 points it outwards at angle a.
		 */
		default void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape, float rot) {
			sprite(x, y, z, size, rgb, alpha, shape);
		}

		/**
		 * A free quad showing atlas {@code cell}: {@code c} holds four corners (x y z each) - top-left, bottom-left,
		 * bottom-right, top-right of the cell - so wings can beat and ribbons twist in any direction.
		 */
		default void quad(float[] c, int cell, int rgb, float alpha) {
			float x = (c[0] + c[3] + c[6] + c[9]) / 4;
			float y = (c[1] + c[4] + c[7] + c[10]) / 4;
			float z = (c[2] + c[5] + c[8] + c[11]) / 4;
			float size = (float) Math.sqrt((c[6] - c[0]) * (c[6] - c[0]) + (c[7] - c[1]) * (c[7] - c[1]) + (c[8] - c[2]) * (c[8] - c[2])) / 2;
			sprite(x, y, z, size, rgb, alpha * 0.6f, GLOW);
		}
	}

	/** {@code out} with everything moved up by {@code dy} (blocks). */
	static Out raised(Out out, float dy) {
		return new Out() {
			@Override
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
				out.sprite(x, y + dy, z, size, rgb, alpha, shape);
			}

			@Override
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape, float rot) {
				out.sprite(x, y + dy, z, size, rgb, alpha, shape, rot);
			}

			@Override
			public void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha) {
				out.beam(x1, y1 + dy, z1, x2, y2 + dy, z2, width, rgb, alpha);
			}

			@Override
			public void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha) {
				out.panel(x, y + dy, z, w, h, nx, nz, cell, rgb, alpha);
			}

			@Override
			public void quad(float[] c, int cell, int rgb, float alpha) {
				float[] q = c.clone();
				for (int i = 1; i < q.length; i += 3) {
					q[i] += dy;
				}
				out.quad(q, cell, rgb, alpha);
			}
		};
	}

	/** The atlas cell a sprite shape (with or without {@link #FLAT} / {@link #UPRIGHT}) is drawn with. */
	public static int cell(int shape) {
		return switch (shape & 0xFF) {
			case STAR -> 1;
			case HEART -> 3;
			case LEAF -> 4;
			case HALO -> 6;
			case SMOKE -> 7;
			case FEATHER -> 24;
			case PETAL -> 25;
			case SHARD -> 26;
			case FLAME -> 27;
			case SPARKLE -> 28;
			case TAIJI -> 31;
			case KOI -> 32;
			case BLADE -> 33;
			case LANTERN -> 34;
			case GEAR -> 35;
			case LILY -> 36;
			case DIAL -> 37;
			case PIXEL -> 38;
			case SLASH -> 39;
			case GHOST -> 40;
			case BURST -> 41;
			case CRACKS -> 42;
			case TOMB -> 43;
			case COIN -> 44;
			case BIRD_WING -> 45;
			case MOON -> 46;
			case SNOOZE -> 47;
			default -> 0;
		};
	}

	/** Whether a sprite gets its two upright quads. */
	public static boolean upright(int shape) {
		return (shape & FLAT) == 0 && (shape & 0xFF) != DISC;
	}

	/** Whether a sprite gets its ground-plane quad (hearts only read upright). */
	public static boolean flat(int shape) {
		return (shape & (UPRIGHT | FACING)) == 0 && (shape & 0xFF) != HEART;
	}

	/** Whether a sprite is a single viewer-facing quad ({@link #FACING}). */
	public static boolean facing(int shape) {
		return (shape & FACING) != 0;
	}

	private AuraEffects() {
	}

	public static Kind kind(String id) {
		return switch (id) {
			case "orbit" -> Kind.ORBIT;
			case "ember" -> Kind.EMBER;
			case "frost" -> Kind.FROST;
			case "void" -> Kind.VOID;
			case "storm" -> Kind.STORM;
			case "matrix" -> Kind.MATRIX;
			case "inferno" -> Kind.INFERNO;
			case "bloom" -> Kind.BLOOM;
			case "heartbeat" -> Kind.HEART;
			case "aurora" -> Kind.AURORA;
			case "hologram" -> Kind.HOLOGRAM;
			case "festive" -> Kind.FESTIVE;
			case "seraph" -> Kind.SERAPH;
			case "sakura" -> Kind.SAKURA;
			case "galaxy" -> Kind.GALAXY;
			case "crystal" -> Kind.CRYSTAL;
			case "soul" -> Kind.SOUL;
			case "butterfly" -> Kind.BUTTERFLY;
			case "bass" -> Kind.BASS;
			case "firework" -> Kind.FIREWORK;
			case "dragon" -> Kind.DRAGON;
			case "ribbon" -> Kind.RIBBON;
			case "raincloud" -> Kind.RAIN;
			case "koi" -> Kind.KOI;
			case "blades" -> Kind.BLADES;
			case "lantern" -> Kind.LANTERN;
			case "chrono" -> Kind.CHRONO;
			case "pyjama" -> Kind.PYJAMA;
			default -> null;
		};
	}

	// ---- Colour ----

	/** The aura's own two colours. */
	private static int[] defaults(Kind kind) {
		return switch (kind) {
			case ORBIT -> new int[] {0x7CC8FF, 0xB47CFF};
			case EMBER -> new int[] {0xFFD36B, 0xFF4A1C};
			case FROST -> new int[] {0xDFF4FF, 0x7CC8FF};
			case VOID -> new int[] {0x7A2BFF, 0xFF5CF0};
			case STORM -> new int[] {0xFFE14D, 0x6FE6FF};
			case MATRIX -> new int[] {0xD8FFE0, 0x1FD65A};
			case INFERNO -> new int[] {0xFFF3B0, 0xE8320C};
			case BLOOM -> new int[] {0x9BE36B, 0xFF9ECF};
			case HEART -> new int[] {0xFF9ECF, 0xFF2D55};
			case AURORA -> new int[] {0x5CFF9D, 0xB47CFF};
			case HOLOGRAM -> new int[] {0x3FF0FF, 0x8A5CFF};
			case FESTIVE -> new int[] {0xFF3B3B, 0x3BD16F};
			case SERAPH -> new int[] {0xFFF4D6, 0xFFC23D};
			case SAKURA -> new int[] {0xFFE3EE, 0xFF6FA8};
			case GALAXY -> new int[] {0xC4E6FF, 0xA64DFF};
			case CRYSTAL -> new int[] {0xF3E2FF, 0x9447FF};
			case SOUL -> new int[] {0xD6FFF9, 0x1FB3D1};
			case BUTTERFLY -> new int[] {0x8FF7FF, 0xB46CFF};
			case BASS -> new int[] {0x3FF0FF, 0xFF3FD8};
			case FIREWORK -> new int[] {0xFFE17A, 0xFF4D6D};
			case DRAGON -> new int[] {0xFFF6E6, 0x7A5CFF};
			case RIBBON -> new int[] {0xFFA6E6, 0x7B8CFF};
			case RAIN -> new int[] {0xBFE3FF, 0x5C7CFF};
			case KOI -> new int[] {0xFF7A2E, 0xFFF4EA};
			case BLADES -> new int[] {0xEAF6FF, 0x5AA8FF};
			case LANTERN -> new int[] {0xFFD27A, 0xFF7A2E};
			case CHRONO -> new int[] {0xFFE3A3, 0x4DD8FF};
			case PYJAMA -> new int[] {0xFFE9A8, 0x7C8CFF};
		};
	}

	/** The aura's middle colour (RGB) for Velo Shaders' aura light, or -1 for an unknown id. */
	public static int glowColor(String auraId, Settings s, float time) {
		Kind k = auraId == null ? null : kind(auraId);
		return k == null ? -1 : color(k, s == null ? Settings.DEFAULT : s, 0.5f, 0.3f, time);
	}

	/** Colour at {@code f} (0..1) between the aura's two colours, or the chosen colour / rainbow. */
	private static int color(Kind kind, Settings s, float f, float hueSeed, float time) {
		return color(defaults(kind), s, f, hueSeed, time);
	}

	/**
	 * Colour at {@code f} (0..1) between an effect's own two colours {@code defaults}, or the chosen colour (lighter
	 * towards 0) / rainbow - for anything recolourable with {@link #PALETTE} (auras, kill effects).
	 */
	public static int color(int[] defaults, Settings s, float f, float hueSeed, float time) {
		if (s.color() == RAINBOW) {
			return hsv((hueSeed * 0.37f + time * 0.08f) % 1f, 0.75f, 1f);
		}
		if (s.color() > 0) {
			int base = PALETTE[s.color() - 1];
			return mix(base, mix(base, 0xFFFFFF, 0.45f), 1 - f);
		}
		return mix(defaults[0], defaults[1], f);
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	public static int hsv(float h, float s, float v) {
		float c = v * s;
		float x = c * (1 - Math.abs((h * 6) % 2 - 1));
		float m = v - c;
		float r;
		float g;
		float b;
		int sector = (int) (h * 6) % 6;
		switch (sector) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}
		return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
	}

	/** The colour shown for an aura on cards (its first colour, or the chosen one). */
	public static int accent(Kind kind, Settings s) {
		return color(kind, s, 0.3f, 0, 0);
	}

	// ---- Emitter ----

	/** A particle in world space. */
	private static final class P {
		double x;
		double y;
		double z;
		float vx;
		float vy;
		float vz;
		float age;
		float life;
		float size;
		float seed;
		int shape;
		/** Turn in its own plane and how fast it changes (petals, feathers); a fixed colour (firework sparks). */
		float rot;
		float spin;
		int tint;
	}

	/** A lightning arc around the player (player space). */
	private static final class Arc {
		float a1;
		float h1;
		float a2;
		float h2;
		float age;
		float life;
		long seed;
	}

	private static final int MAX_PARTICLES = 340;
	private static final float TAU = (float) (Math.PI * 2);

	private static float sin(float a) {
		return (float) Math.sin(a);
	}

	private static float cos(float a) {
		return (float) Math.cos(a);
	}

	/** One player's aura: its time, particles and arcs. Not thread-safe - one thread renders it. */
	public static final class Emitter {
		private final Kind kind;
		private final List<P> particles = new ArrayList<>();
		private final List<Arc> arcs = new ArrayList<>();
		private final Random random = new Random();
		private float time;
		/** Where the ground is below the feet (blocks, 0 or less), NaN when there's none close - see {@link #ground(float)}. */
		private float ground;
		private float emitDebt;
		private float arcDebt;
		private int emitted;
		private long lastNanos;

		public Emitter(Kind kind) {
			this.kind = kind;
		}

		public Kind kind() {
			return kind;
		}

		/** Seconds since this emitter was last advanced (0 the first time, at most 0.1). Also stamps "now". */
		public float tick(long nanos) {
			float dt = lastNanos == 0 ? 0 : Math.min(0.1f, Math.max(0, (nanos - lastNanos) / 1e9f));
			lastNanos = nanos;
			return dt;
		}

		public long lastNanos() {
			return lastNanos;
		}

		/**
		 * The ground under the player, relative to their feet (0 standing, -3 three blocks up in the air), NaN when
		 * there's no ground close below. Things lying on the ground (the yin-yang, the koi pond) are drawn there - not
		 * at the feet, where they'd float in the air or lie on top of whoever is below.
		 */
		public void ground(float relative) {
			ground = relative;
		}

		/** Advances the animation by {@code dt} real seconds; the player's feet are at (px, py, pz). */
		public void update(float dt, Settings s, double px, double py, double pz) {
			float t = dt * s.speed();
			time += t;
			if (t <= 0) {
				return;
			}
			switch (kind) {
				case ORBIT -> emitOrbit(t, s, px, py, pz);
				case EMBER -> emitEmber(t, s, px, py, pz);
				case FROST -> emitFrost(t, s, px, py, pz);
				case VOID -> emitVoid(t, s, px, py, pz);
				case STORM -> emitStorm(t, s, px, py, pz);
				case INFERNO -> emitInferno(t, s, px, py, pz);
				case BLOOM -> emitBloom(t, s, px, py, pz);
				case HEART -> emitHearts(t, s, px, py, pz);
				case MATRIX -> updateRain(t, s);
				case AURORA -> emitAurora(t, s, px, py, pz);
				case HOLOGRAM -> emitHologram(t, s, px, py, pz);
				case FESTIVE -> emitFestive(t, s, px, py, pz);
				case SERAPH -> emitSeraph(t, s, px, py, pz);
				case SAKURA -> emitSakura(t, s, px, py, pz);
				case GALAXY -> emitGalaxy(t, s, px, py, pz);
				case CRYSTAL -> emitCrystal(t, s, px, py, pz);
				case SOUL -> emitSoul(t, s, px, py, pz);
				case BUTTERFLY -> emitButterflies(t, s, px, py, pz);
				case BASS -> updateBass(t, s, px, py, pz);
				case FIREWORK -> emitFireworks(t, s, px, py, pz);
				case DRAGON -> emitDragon(t, s, px, py, pz);
				case RIBBON -> emitRibbons(t, s, px, py, pz);
				case RAIN -> emitRain(t, s, px, py, pz);
				case KOI -> {
					for (int k = Float.isNaN(ground) ? 0 : count(t, 6 * s.density(), 0); k > 0; k--) {
						float a = rnd(0, TAU);
						float r = (float) Math.sqrt(random.nextFloat()) * 1.2f * s.size();
						spawn(px + Math.cos(a) * r, py + ground + 0.07, pz + Math.sin(a) * r, rnd(0.6f, 1f), rnd(0.03f, 0.05f) * s.size(), SPARKLE);
					}
				}
				case BLADES -> updateBlades(t, s, px, py, pz);
				case LANTERN -> emitLanterns(t, s, px, py, pz);
				case CHRONO -> emitChrono(t, s, px, py, pz);
				case PYJAMA -> emitPyjama(t, s, px, py, pz);
			}
			for (int i = particles.size() - 1; i >= 0; i--) {
				P p = particles.get(i);
				p.age += t;
				if (p.age >= p.life) {
					particles.remove(i);
					if (p.shape == ROLE_ROCKET || p.shape == ROLE_RAIN) {
						bursts.add(p);
					}
					continue;
				}
				move(p, t, s, px, py, pz);
			}
			// Rockets that ran out burst now, raindrops splash (not inside the loop above: new particles may push old ones out).
			for (P p : bursts) {
				if (p.shape == ROLE_ROCKET) {
					burst(p, s);
				} else {
					splash(p, s);
				}
			}
			bursts.clear();
			for (int i = arcs.size() - 1; i >= 0; i--) {
				Arc a = arcs.get(i);
				a.age += t;
				if (a.age >= a.life) {
					arcs.remove(i);
				}
			}
		}

		private void add(P p) {
			if (particles.size() >= MAX_PARTICLES) {
				particles.remove(0);
			}
			p.seed = random.nextFloat();
			emitted++;
			particles.add(p);
		}

		/** How many particles to spawn this step at {@code perSecond}. */
		private int count(float t, float perSecond) {
			emitDebt += t * perSecond;
			int n = (int) emitDebt;
			emitDebt -= n;
			return n;
		}

		private float rnd(float min, float max) {
			return min + random.nextFloat() * (max - min);
		}

		// -- Emission --

		private void emitOrbit(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 18 * s.density());
			for (int k = 0; k < n; k++) {
				// A twinkle left behind by one of the stars.
				float[] at = orbitStar(random.nextInt(3), random.nextInt(orbsPerRing(s)), s, time);
				P p = new P();
				p.x = px + at[0];
				p.y = py + at[1];
				p.z = pz + at[2];
				p.vx = rnd(-0.15f, 0.15f);
				p.vy = rnd(-0.05f, 0.2f);
				p.vz = rnd(-0.15f, 0.15f);
				p.life = rnd(0.6f, 1.0f);
				p.size = rnd(0.035f, 0.06f) * s.size();
				p.shape = STAR;
				add(p);
			}
		}

		private void emitEmber(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 42 * s.density());
			for (int k = 0; k < n; k++) {
				float a = time * 2.4f + (emitted % 2) * (float) Math.PI + rnd(-0.25f, 0.25f);
				float r = 0.42f * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + 0.05;
				p.z = pz + Math.sin(a) * r;
				p.vx = (float) -Math.sin(a) * 0.5f;
				p.vy = rnd(0.7f, 1.05f);
				p.vz = (float) Math.cos(a) * 0.5f;
				p.life = rnd(1.3f, 1.9f);
				p.size = rnd(0.12f, 0.18f) * s.size();
				p.shape = GLOW;
				add(p);
			}
		}

		private void emitFrost(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 16 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = (float) Math.sqrt(random.nextFloat()) * 1.15f * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + 2.5 + rnd(0, 0.35f);
				p.z = pz + Math.sin(a) * r;
				p.vy = -rnd(0.3f, 0.45f);
				p.life = rnd(3.0f, 3.8f);
				p.size = rnd(0.035f, 0.06f) * s.size();
				p.shape = random.nextFloat() < 0.4f ? STAR : GLOW;
				add(p);
			}
		}

		private void emitVoid(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 22 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = 1.7f * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0.15f, 1.8f);
				p.z = pz + Math.sin(a) * r;
				p.life = rnd(1.4f, 1.9f);
				p.size = rnd(0.07f, 0.11f) * s.size();
				p.shape = GLOW;
				add(p);
			}
		}

		private void emitStorm(float t, Settings s, double px, double py, double pz) {
			arcDebt += t * 14 * s.density();
			while (arcDebt >= 1) {
				arcDebt -= 1;
				Arc arc = new Arc();
				arc.a1 = rnd(0, (float) (Math.PI * 2));
				arc.h1 = rnd(0.15f, 1.85f);
				arc.a2 = arc.a1 + (random.nextBoolean() ? 1 : -1) * rnd(0.6f, 1.7f);
				arc.h2 = Math.max(0.05f, Math.min(2f, arc.h1 + rnd(-0.7f, 0.7f)));
				arc.life = rnd(0.22f, 0.34f);
				arc.seed = random.nextLong();
				arcs.add(arc);
				// Sparks fly off where it hits.
				float r = 0.42f * s.size();
				for (int k = 0; k < 4; k++) {
					P p = new P();
					p.x = px + Math.cos(arc.a2) * r;
					p.y = py + arc.h2;
					p.z = pz + Math.sin(arc.a2) * r;
					p.vx = rnd(-1.4f, 1.4f) + (float) Math.cos(arc.a2) * 0.8f;
					p.vy = rnd(0.2f, 1.6f);
					p.vz = rnd(-1.4f, 1.4f) + (float) Math.sin(arc.a2) * 0.8f;
					p.life = rnd(0.3f, 0.55f);
					p.size = rnd(0.05f, 0.08f) * s.size();
					p.shape = STAR;
					add(p);
				}
			}
		}

		private void emitInferno(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 95 * s.density());
			for (int k = 0; k < n; k++) {
				// Around the body (not inside it, where the player would hide the flames).
				float a = rnd(0, (float) (Math.PI * 2));
				float r = rnd(0.4f, 0.62f) * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0, 0.2f);
				p.z = pz + Math.sin(a) * r;
				p.vy = rnd(1.6f, 2.7f);
				p.life = rnd(0.7f, 1.1f);
				p.size = rnd(0.2f, 0.32f) * s.size();
				p.shape = GLOW;
				add(p);
			}
			// Now and then a spark shoots up out of the fire.
			if (random.nextFloat() < t * 6 * s.density()) {
				P p = new P();
				p.x = px + rnd(-0.3f, 0.3f) * s.size();
				p.y = py + 0.6;
				p.z = pz + rnd(-0.3f, 0.3f) * s.size();
				p.vx = rnd(-0.5f, 0.5f);
				p.vy = rnd(2.5f, 3.6f);
				p.vz = rnd(-0.5f, 0.5f);
				p.life = rnd(0.8f, 1.2f);
				p.size = rnd(0.04f, 0.06f) * s.size();
				p.shape = STAR;
				add(p);
			}
		}

		private void emitBloom(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 8 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = rnd(0.55f, 1.05f) * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0.2f, 2.1f);
				p.z = pz + Math.sin(a) * r;
				float kind = random.nextFloat();
				if (kind < 0.7f) {
					// A firefly.
					p.shape = GLOW;
					p.size = rnd(0.035f, 0.055f) * s.size();
					p.life = rnd(2.0f, 3.2f);
				} else {
					// A petal falling from the flowers, turning slowly around you.
					p.shape = LEAF;
					p.size = rnd(0.07f, 0.11f) * s.size();
					p.life = rnd(2.6f, 3.6f);
					p.vy = -rnd(0.15f, 0.3f);
				}
				add(p);
			}
		}

		private void emitHearts(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 7 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = rnd(0.35f, 0.8f) * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0.8f, 1.7f);
				p.z = pz + Math.sin(a) * r;
				p.vy = rnd(0.45f, 0.7f);
				p.life = rnd(1.6f, 2.2f);
				p.size = rnd(0.07f, 0.12f) * s.size();
				p.shape = HEART;
				add(p);
			}
		}

		private void emitAurora(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 6 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = 1.15f * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0.6f, 1.6f);
				p.z = pz + Math.sin(a) * r;
				p.vy = rnd(0.1f, 0.25f);
				p.life = rnd(1.5f, 2.5f);
				p.size = rnd(0.03f, 0.05f) * s.size();
				p.shape = STAR;
				add(p);
			}
		}

		private void emitFestive(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 10 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = rnd(0.3f, 1.2f) * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(2.2f, 2.8f);
				p.z = pz + Math.sin(a) * r;
				p.vy = -rnd(0.3f, 0.55f);
				p.life = rnd(4f, 6f);
				p.size = rnd(0.03f, 0.055f) * s.size();
				p.shape = STAR;
				add(p);
			}
		}

		private void emitHologram(float t, Settings s, double px, double py, double pz) {
			int n = count(t, 28 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = rnd(0.35f, 0.95f) * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0f, 0.3f);
				p.z = pz + Math.sin(a) * r;
				p.vy = rnd(0.5f, 1.1f);
				p.life = rnd(1.2f, 2.0f);
				p.size = rnd(0.016f, 0.032f) * s.size();
				p.shape = random.nextInt(3) == 0 ? STAR : GLOW;
				add(p);
			}
		}

		// -- Code rain (kept per column, around the player) --

		/** Code rain: the height of one glyph row (small glyphs, packed close - a fine, dense rain). */
		private static final float RAIN_STEP = 0.095f;
		private float[] rainAngle = new float[0];
		private float[] rainHead;
		private float[] rainSpeed;
		private int[] rainLength;
		private long[] rainSeed;

		private void updateRain(float t, Settings s) {
			int columns = Math.max(10, Math.round(36 * s.density()));
			if (rainAngle.length != columns) {
				rainAngle = new float[columns];
				rainHead = new float[columns];
				rainSpeed = new float[columns];
				rainLength = new int[columns];
				rainSeed = new long[columns];
				for (int i = 0; i < columns; i++) {
					rainAngle[i] = (float) (i * Math.PI * 2 / columns) + rnd(-0.08f, 0.08f);
					resetColumn(i);
					rainHead[i] = rnd(-0.2f, 2.6f);
				}
			}
			for (int i = 0; i < columns; i++) {
				rainHead[i] -= rainSpeed[i] * t;
				if (rainHead[i] + rainLength[i] * RAIN_STEP < -0.1f) {
					resetColumn(i);
				}
			}
		}

		private void resetColumn(int i) {
			rainHead[i] = rnd(2.4f, 3.0f);
			rainSpeed[i] = rnd(1.0f, 2.4f);
			rainLength[i] = 10 + random.nextInt(14);
			rainSeed[i] = random.nextLong();
		}

		// -- Motion --

		private void move(P p, float t, Settings s, double px, double py, double pz) {
			switch (kind) {
				case EMBER -> {
					// Swirl around the vertical axis while rising, slowing a little.
					float w = 1.7f * t;
					float vx = p.vx * (float) Math.cos(w) - p.vz * (float) Math.sin(w);
					float vz = p.vx * (float) Math.sin(w) + p.vz * (float) Math.cos(w);
					p.vx = vx * (1 - 0.4f * t);
					p.vz = vz * (1 - 0.4f * t);
					p.vy *= 1 - 0.15f * t;
				}
				case FROST -> {
					// Gentle sway on the way down.
					p.vx = (float) Math.sin(p.age * 1.8f + p.seed * 20) * 0.22f;
					p.vz = (float) Math.cos(p.age * 1.4f + p.seed * 13) * 0.22f;
				}
				case VOID -> {
					// Spiral in towards the player's axis.
					double dx = px - p.x;
					double dz = pz - p.z;
					double d = Math.max(0.05, Math.sqrt(dx * dx + dz * dz));
					if (d < 0.18) {
						p.age = p.life;
						return;
					}
					float pull = 1.1f + 1.4f * (p.age / p.life);
					p.vx = (float) (dx / d * pull - dz / d * 1.25f);
					p.vz = (float) (dz / d * pull + dx / d * 1.25f);
					p.vy = (float) ((py + 1.0 - p.y) * 0.6);
				}
				case STORM -> p.vy -= 6f * t;
				case INFERNO -> {
					// Licking flames: a wobble, pulled back towards the middle, slowing near the top.
					p.vx += (float) Math.sin(p.age * 14 + p.seed * 30) * 2.2f * t;
					p.vz += (float) Math.cos(p.age * 12 + p.seed * 17) * 2.2f * t;
					// Held on a cylinder around the body, narrowing a little towards the top.
					double dx = p.x - px;
					double dz = p.z - pz;
					double d = Math.max(0.05, Math.sqrt(dx * dx + dz * dz));
					double want = (0.5 - 0.12 * Math.min(1, (p.y - py) / 2)) * s.size();
					p.vx += (float) (dx / d * (want - d) * 6 * t);
					p.vz += (float) (dz / d * (want - d) * 6 * t);
					if (p.shape == GLOW) {
						p.vy *= 1 - 0.5f * t;
					}
				}
				case BLOOM -> {
					// Turn slowly around the player's axis; fireflies wander.
					double dx = p.x - px;
					double dz = p.z - pz;
					double w = 0.35 * t;
					p.x = px + dx * Math.cos(w) - dz * Math.sin(w);
					p.z = pz + dx * Math.sin(w) + dz * Math.cos(w);
					if (p.shape == GLOW) {
						p.vx = (float) Math.sin(p.age * 2.3f + p.seed * 9) * 0.25f;
						p.vy = (float) Math.cos(p.age * 1.7f + p.seed * 5) * 0.15f;
						p.vz = (float) Math.cos(p.age * 2.1f + p.seed * 7) * 0.25f;
					} else {
						p.vx = (float) Math.sin(p.age * 1.6f + p.seed * 11) * 0.12f;
						p.vz = (float) Math.cos(p.age * 1.3f + p.seed * 3) * 0.12f;
					}
				}
				case HEART -> p.vx = (float) Math.sin(p.age * 3 + p.seed * 10) * 0.18f;
				case AURORA, MATRIX, HOLOGRAM -> {
				}
				case FESTIVE -> p.vx = (float) Math.sin(p.age * 1.7f + p.seed * 9) * 0.12f;
				case ORBIT -> {
					p.vx *= 1 - 1.5f * t;
					p.vz *= 1 - 1.5f * t;
				}
				default -> move2(p, t, px, pz);
			}
			p.x += p.vx * t;
			p.y += p.vy * t;
			p.z += p.vz * t;
		}

		// ---- Drawing ----

		/** Draws the aura for a player whose feet are at (px, py, pz) right now. */
		public void draw(Settings s, double px, double py, double pz, Out out) {
			float sz = s.size();
			switch (kind) {
				case ORBIT -> drawOrbit(s, out);
				case EMBER -> {
					float pulse = 0.32f + 0.08f * (float) Math.sin(time * 3.1f);
					out.sprite(0, 0.03f, 0, 1.25f * sz, color(kind, s, 0.6f, 0, time), pulse, DISC);
				}
				case FROST -> drawFrost(s, out);
				case VOID -> drawVoid(s, out);
				case STORM -> drawStorm(s, out);
				case MATRIX -> drawRain(s, out);
				case INFERNO -> {
					float pulse = 0.4f + 0.12f * (float) Math.sin(time * 9) + 0.06f * (float) Math.sin(time * 23);
					out.sprite(0, 0.03f, 0, 1.0f * sz, color(kind, s, 0.6f, 0, time), pulse, DISC);
					out.sprite(0, 0.9f, 0, 0.75f * sz, color(kind, s, 0.7f, 0, time), 0.08f, GLOW);
				}
				case BLOOM -> {
					out.sprite(0, 0.03f, 0, 1.25f * sz, color(kind, s, 0f, 0, time), 0.22f, DISC);
					drawVines(s, out);
					// Grass sparkles at your feet.
					for (int i = 0; i < 8; i++) {
						float a = i * 0.785f + time * 0.2f;
						float twinkle = (float) Math.max(0, Math.sin(time * 2.5f + i * 2.1f));
						out.sprite(0.75f * sz * (float) Math.cos(a), 0.06f, 0.75f * sz * (float) Math.sin(a), 0.05f * sz,
								color(kind, s, 0f, i / 8f, time), twinkle * 0.8f, STAR);
					}
				}
				case HEART -> drawHeartRing(s, out);
				case AURORA -> drawAurora(s, out);
				case HOLOGRAM -> drawHologram(s, out);
				case FESTIVE -> drawFestive(s, out);
				case SERAPH -> drawSeraph(s, out);
				case SAKURA -> drawSakura(s, out);
				case GALAXY -> drawGalaxy(s, out);
				case CRYSTAL -> drawCrystal(s, out);
				case SOUL -> drawSoul(s, out);
				case BUTTERFLY -> drawButterflies(s, out);
				case BASS -> drawBass(s, out);
				case FIREWORK -> out.sprite(0, 0.03f, 0, 1.1f * sz, color(kind, s, 0.5f, 0, time), 0.1f, DISC);
				case DRAGON -> drawDragon(s, out);
				case RIBBON -> drawRibbons(s, out);
				case RAIN -> drawRainCloud(s, out);
				case KOI -> {
					if (!Float.isNaN(ground)) {
						drawKoi(s, raised(out, ground));
					}
				}
				case BLADES -> drawBlades(s, out);
				case LANTERN -> drawLanterns(s, out);
				case CHRONO -> drawChrono(s, out);
				case PYJAMA -> drawPyjama(s, out);
			}
			for (P p : particles) {
				float f = p.age / p.life;
				float x = (float) (p.x - px);
				float y = (float) (p.y - py);
				float z = (float) (p.z - pz);
				switch (kind) {
					case EMBER -> {
						float flicker = 0.75f + 0.25f * (float) Math.sin(p.age * 26 + p.seed * 40);
						float alpha = (float) Math.pow(1 - f, 1.2) * flicker;
						int c = color(kind, s, Math.min(1, f * 1.4f), p.seed, time);
						out.sprite(x, y, z, p.size * (1 - 0.55f * f), c, alpha, GLOW);
						out.sprite(x, y, z, p.size * 0.35f * (1 - f), 0xFFF4D0, alpha * 0.8f, GLOW);
					}
					case FROST -> {
						float alpha = Math.min(1, Math.min(p.age * 3, (p.life - p.age) * 1.5f));
						out.sprite(x, y, z, p.size, color(kind, s, p.seed * 0.6f, p.seed, time), alpha * 0.9f, p.shape);
					}
					case VOID -> {
						float alpha = Math.min(1, p.age * 4) * (1 - f * 0.4f);
						int c = color(kind, s, f, p.seed, time);
						float size = p.size * (0.6f + 0.6f * (float) Math.sin(Math.PI * Math.min(1, f * 1.2f)));
						out.sprite(x, y, z, size, c, alpha, GLOW);
						// A short tail along its path.
						for (int k = 1; k <= 3; k++) {
							float back = 0.035f * k;
							out.sprite(x - p.vx * back, y - p.vy * back, z - p.vz * back, size * (1 - k * 0.22f), c, alpha * (0.55f - k * 0.13f), GLOW);
						}
					}
					case STORM -> out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), 1 - f, STAR);
					case ORBIT -> out.sprite(x, y, z, p.size * (1 - f * 0.5f), color(kind, s, p.seed, p.seed, time), (1 - f) * 0.9f, STAR);
					case INFERNO -> {
						if (p.shape == STAR) {
							out.sprite(x, y, z, p.size, 0xFFE9A0, 1 - f, STAR);
							break;
						}
						// White-hot at the root, through orange to deep red, then gone.
						float flicker = 0.85f + 0.15f * (float) Math.sin(p.age * 30 + p.seed * 50);
						int c = s.color() == 0 ? (f < 0.25f ? mix(0xFFF3B0, 0xFFB02E, f / 0.25f) : mix(0xFFB02E, 0xC7280C, (f - 0.25f) / 0.75f))
								: color(kind, s, f, p.seed, time);
						out.sprite(x, y, z, p.size * (1 - 0.65f * f), c, (float) Math.pow(1 - f, 1.4) * flicker, GLOW);
					}
					case BLOOM -> {
						float alpha = Math.min(1, Math.min(p.age * 2, (p.life - p.age) * 1.5f));
						if (p.shape == GLOW) {
							float blink = (float) Math.max(0, Math.sin(p.age * 3.4f + p.seed * 20));
							out.sprite(x, y, z, p.size, s.color() == 0 ? 0xE8FF8A : color(kind, s, 0.5f, p.seed, time), alpha * blink, GLOW);
						} else {
							out.sprite(x, y, z, p.size * 0.8f, color(kind, s, 1f, p.seed, time), alpha * 0.9f, LEAF);
						}
					}
					case HEART -> {
						float grow = Math.min(1, p.age * 3);
						float alpha = Math.min(1, (p.life - p.age) * 1.6f);
						out.sprite(x, y, z, p.size * grow, color(kind, s, p.seed, p.seed, time), alpha, HEART);
					}
					case AURORA -> out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), (1 - f) * 0.9f, STAR);
					case HOLOGRAM -> {
						float alpha = Math.min(1, Math.min(p.age * 4, (p.life - p.age) * 1.8f)) * (0.55f + 0.45f * (float) Math.abs(Math.sin(p.age * 14 + p.seed * 30)));
						out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), alpha, p.shape);
					}
					case FESTIVE -> {
						float alpha = Math.min(1, Math.min(p.age * 2, (p.life - p.age) * 1.5f));
						out.sprite(x, y, z, p.size, s.color() == 0 ? 0xEAF6FF : color(kind, s, 0.5f, p.seed, time), alpha * 0.9f, STAR);
					}
					case MATRIX -> {
					}
					default -> drawParticle(p, s, x, y, z, f, out);
				}
			}
		}

		private static int orbsPerRing(Settings s) {
			return Math.max(1, Math.round(1.5f * s.density()));
		}

		/** Where star {@code j} of ring {@code ring} is at {@code time} (player space). */
		private static float[] orbitStar(int ring, int j, Settings s, float time) {
			int n = orbsPerRing(s);
			float dir = ring % 2 == 0 ? 1 : -1;
			float theta = dir * time * (1.5f + 0.35f * ring) + j * (float) (Math.PI * 2 / n) + ring;
			return ringPoint(ring, theta, s, time);
		}

		private static float[] ringPoint(int ring, float theta, Settings s, float time) {
			float r = 0.85f * s.size();
			float incl = 0.5f;
			float node = ring * (float) (Math.PI * 2 / 3) + time * 0.12f;
			float x = r * (float) Math.cos(theta);
			float z = r * (float) Math.sin(theta);
			// Tilt the ring around x, then turn it around y.
			float y = -z * (float) Math.sin(incl);
			z = z * (float) Math.cos(incl);
			float xr = x * (float) Math.cos(node) - z * (float) Math.sin(node);
			float zr = x * (float) Math.sin(node) + z * (float) Math.cos(node);
			return new float[] {xr, 1.0f + y, zr};
		}

		private void drawOrbit(Settings s, Out out) {
			int n = orbsPerRing(s);
			for (int ring = 0; ring < 3; ring++) {
				int c = color(kind, s, ring / 2f, ring * 0.33f, time);
				float dir = ring % 2 == 0 ? 1 : -1;
				for (int j = 0; j < n; j++) {
					float theta = dir * time * (1.5f + 0.35f * ring) + j * (float) (Math.PI * 2 / n) + ring;
					float[] p = ringPoint(ring, theta, s, time);
					out.sprite(p[0], p[1], p[2], 0.17f * s.size(), c, 1f, GLOW);
					out.sprite(p[0], p[1], p[2], 0.06f * s.size(), 0xFFFFFF, 0.95f, GLOW);
					// Comet tail.
					for (int k = 1; k <= 14; k++) {
						float[] q = ringPoint(ring, theta - dir * k * 0.06f, s, time);
						float fade = 1 - k / 15f;
						out.sprite(q[0], q[1], q[2], 0.12f * s.size() * fade, c, 0.6f * fade, GLOW);
					}
				}
			}
		}

		private void drawFrost(Settings s, Out out) {
			float sz = s.size();
			float h = 2.15f;
			int stars = 14;
			for (int i = 0; i < stars; i++) {
				float a = i * (float) (Math.PI * 2 / stars) + time * 0.6f;
				float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 4 + i * 1.7f);
				float r = 0.34f * sz;
				float x = r * (float) Math.cos(a);
				float z = r * (float) Math.sin(a);
				float y = h + 0.03f * (float) Math.sin(a * 3 + time * 2);
				out.sprite(x, y, z, (0.07f + 0.05f * twinkle) * sz, color(kind, s, i % 2 == 0 ? 0f : 0.7f, i / (float) stars, time), 0.6f + 0.4f * twinkle, STAR);
			}
			out.sprite(0, h, 0, 0.8f * sz, color(kind, s, 0.5f, 0, time), 0.22f, DISC);
			out.sprite(0, 0.03f, 0, 1.4f * sz, color(kind, s, 0.8f, 0, time), 0.16f, DISC);
		}

		private void drawVoid(Settings s, Out out) {
			float sz = s.size();
			out.sprite(0, 0.03f, 0, 1.9f * sz, color(kind, s, 0.1f, 0, time), 0.26f + 0.06f * (float) Math.sin(time * 2.4f), DISC);
			runeRing(out, s, 0.95f * sz, 28, time * 0.35f, 0.045f * sz, 0.05f);
			runeRing(out, s, 0.62f * sz, 18, -time * 0.55f, 0.035f * sz, 0.07f);
			// Rune marks between the rings.
			for (int i = 0; i < 6; i++) {
				float a = i * (float) (Math.PI / 3) + time * 0.35f;
				float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3 + i);
				out.sprite(0.79f * sz * (float) Math.cos(a), 0.06f, 0.79f * sz * (float) Math.sin(a), 0.09f * sz, color(kind, s, 0.9f, i / 6f, time),
						0.5f + 0.5f * pulse, STAR);
			}
		}

		private void runeRing(Out out, Settings s, float r, int segments, float turn, float width, float y) {
			float step = (float) (Math.PI * 2 / segments);
			for (int i = 0; i < segments; i++) {
				float a1 = turn + i * step;
				float a2 = a1 + step * 0.62f;
				float pulse = (float) Math.pow(0.5 + 0.5 * Math.sin(time * 2.4f + i * 0.7f), 2);
				out.beam(r * (float) Math.cos(a1), y, r * (float) Math.sin(a1), r * (float) Math.cos(a2), y, r * (float) Math.sin(a2), width,
						color(kind, s, pulse, i / (float) segments, time), 0.35f + 0.65f * pulse);
			}
		}

		private void drawRain(Settings s, Out out) {
			float sz = s.size();
			float glyph = 0.105f * sz;
			out.sprite(0, 0.03f, 0, 1.2f * sz, color(kind, s, 1f, 0, time), 0.18f, DISC);
			for (int i = 0; i < rainAngle.length; i++) {
				float a = rainAngle[i];
				// Two layers: every other column a little further out, so the rain has depth.
				float r = (i % 2 == 0 ? 0.78f : 0.94f) * sz;
				float nx = (float) Math.cos(a);
				float nz = (float) Math.sin(a);
				// The glyphs change now and then (per column, from its seed and the time).
				long tick = (long) (time * 9);
				for (int k = 0; k < rainLength[i]; k++) {
					float y = rainHead[i] + k * RAIN_STEP;
					if (y < 0 || y > 2.7f) {
						continue;
					}
					float fade = 1 - k / (float) rainLength[i];
					long h = rainSeed[i] * 31 + k * 977 + (k == 0 ? tick : tick / (3 + k % 4));
					int g = (int) Math.floorMod(h ^ (h >>> 17), (long) GLYPHS.length());
					int c = k == 0 ? (s.color() == 0 ? 0xE8FFEE : 0xFFFFFF) : color(kind, s, 0.4f + 0.6f * (1 - fade), i / (float) rainAngle.length, time);
					// Fade in at the top and out at the feet.
					float edge = Math.min(1, Math.min(y * 3, (2.7f - y) * 2.5f));
					float alpha = (k == 0 ? 1f : 0.95f * fade * fade) * edge;
					// Twice at right angles, so no column is ever seen edge-on.
					out.panel(r * nx, y, r * nz, glyph, glyph, nx, nz, CELL_GLYPH + g, c, alpha);
					out.panel(r * nx, y, r * nz, glyph, glyph, -nz, nx, CELL_GLYPH + g, c, alpha * 0.8f);
					if (k == 0) {
						// the leading glyph glows
						out.sprite(r * nx, y, r * nz, glyph * 2.4f, color(kind, s, 1f, 0, time), 0.35f * edge, GLOW);
					}
				}
				// The column's faint light.
				float top = Math.min(2.7f, rainHead[i] + rainLength[i] * RAIN_STEP);
				float bottom = Math.max(0, rainHead[i]);
				if (top > bottom) {
					out.beam(r * nx, bottom, r * nz, r * nx, top, r * nz, 0.03f * sz, color(kind, s, 1f, 0, time), 0.16f);
				}
			}
		}

		/** Three vines spiralling up around you: they grow, leaf out, flower at the tip, fade and grow again. */
		private void drawVines(Settings s, Out out) {
			float sz = s.size();
			float period = 7f;
			int segments = 30;
			for (int v = 0; v < 3; v++) {
				float phase = (time + v * period / 3) % period;
				float grown = Math.min(1, phase / 4f);
				float fade = phase > period - 1.2f ? (period - phase) / 1.2f : 1;
				if (fade <= 0) {
					continue;
				}
				float a0 = v * (float) (Math.PI * 2 / 3) + 0.4f;
				int shown = Math.max(1, Math.round(segments * grown));
				float[] prev = null;
				for (int i = 0; i <= shown; i++) {
					float f = i / (float) segments;
					float a = a0 + f * (float) (Math.PI * 2.3) + 0.08f * (float) Math.sin(time * 0.8f + v + f * 6);
					float r = (0.42f + 0.07f * (float) Math.sin(f * 11 + v * 2)) * sz;
					float[] q = {r * (float) Math.cos(a), 0.04f + f * 2.0f * sz, r * (float) Math.sin(a)};
					if (prev != null) {
						// Darker and thicker at the root, fresh green at the tip.
						int c = mix(0x2E7D32, color(kind, s, 0f, v / 3f, time), f);
						out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], (0.09f - 0.045f * f) * sz, c, fade);
					}
					// A leaf every few segments, alternating sides, unfolding as the vine grows past it.
					if (i > 0 && i % 3 == 0 && i < shown) {
						float out1 = (i / 3) % 2 == 0 ? 1.18f : 0.82f;
						float unfold = Math.min(1, (shown - i) / 4f);
						int leaf = s.color() == 0 ? mix(0x3FAE4A, 0x9BE36B, (i % 5) / 4f) : color(kind, s, 0.2f, v / 3f + i, time);
						out.sprite(q[0] * out1, q[1] + 0.03f, q[2] * out1, 0.11f * sz * unfold, leaf, fade, LEAF);
					}
					prev = q;
				}
				// The flower at the tip once it's grown.
				if (grown >= 1 && prev != null) {
					float open = Math.min(1, (phase - 4f) / 0.8f);
					int petal = color(kind, s, 1f, v / 3f, time);
					for (int k = 0; k < 5; k++) {
						float pa = k * 1.2566f + time * 0.3f;
						float pr = 0.07f * sz * open;
						out.sprite(prev[0] + pr * (float) Math.cos(pa), prev[1] + 0.02f, prev[2] + pr * (float) Math.sin(pa), 0.06f * sz * open, petal,
								0.95f * fade, GLOW);
					}
					out.sprite(prev[0], prev[1] + 0.02f, prev[2], 0.05f * sz * open, 0xFFF4A0, fade, STAR);
				}
			}
		}

		private void drawHeartRing(Settings s, Out out) {
			float sz = s.size();
			// Lub-dub: two quick beats, then a pause.
			float beat = time % 1.1f;
			float pulse = (float) (Math.exp(-Math.pow((beat - 0.05) / 0.07, 2)) + 0.7 * Math.exp(-Math.pow((beat - 0.3) / 0.07, 2)));
			out.sprite(0, 1.15f, 0, (0.55f + 0.12f * pulse) * sz, color(kind, s, 0.9f, 0, time), 0.06f + 0.08f * pulse, GLOW);
			int hearts = 8;
			for (int i = 0; i < hearts; i++) {
				float a = i * (float) (Math.PI * 2 / hearts) + time * 0.7f;
				float r = 0.78f * sz;
				float y = 1.1f + 0.08f * (float) Math.sin(time * 1.5f + i);
				out.sprite(r * (float) Math.cos(a), y, r * (float) Math.sin(a), (0.11f + 0.05f * pulse) * sz,
						color(kind, s, 0.5f + 0.5f * pulse, i / (float) hearts, time), 0.75f + 0.25f * pulse, HEART);
			}
		}

		private void drawAurora(Settings s, Out out) {
			float sz = s.size();
			int segments = 30;
			float r = 1.15f * sz;
			float width = (float) (Math.PI * 2 * r / segments) * 1.25f;
			for (int i = 0; i < segments; i++) {
				float a = i * (float) (Math.PI * 2 / segments) + time * 0.12f;
				// The curtain waves: its radius, height and brightness move along the ring.
				float wave = (float) Math.sin(time * 0.9f + i * 0.45f);
				float rr = r * (1 + 0.06f * (float) Math.sin(time * 1.3f + i * 0.8f));
				float h = (1.0f + 0.35f * wave) * sz;
				float bottom = 0.45f + 0.12f * (float) Math.sin(time * 0.7f + i * 0.3f);
				float nx = (float) Math.cos(a);
				float nz = (float) Math.sin(a);
				float f = (float) (0.5 + 0.5 * Math.sin(time * 0.35f + i * 0.21f));
				float shimmer = 0.45f + 0.25f * (float) Math.sin(time * 2.2f + i * 1.3f);
				out.panel(rr * nx, bottom + h / 2, rr * nz, width, h, nx, nz, CELL_CURTAIN, color(kind, s, f, i / (float) segments, time), shimmer);
			}
		}

		/** Garland of lights spiralling up, baubles circling at three heights, a star above your head. */
		private void drawFestive(Settings s, Out out) {
			float sz = s.size();
			out.sprite(0, 0.03f, 0, 1.3f * sz, color(kind, s, 1f, 0, time), 0.14f, DISC);
			// The garland: a helix of green beams with lights along it, chasing round.
			int segments = 48;
			float turns = 2.5f;
			float r = 0.7f * sz;
			float px = 0;
			float py = 0;
			float pz = 0;
			int[] bulbColors = {0xFF3B3B, 0xFFD24D, 0x3BD16F, 0x4D8CFF};
			for (int i = 0; i <= segments; i++) {
				float f = i / (float) segments;
				float a = f * turns * (float) (Math.PI * 2) + time * 0.5f;
				float rr = r * (1.05f - f * 0.45f);
				float x = rr * (float) Math.cos(a);
				float y = 0.15f + f * 1.9f;
				float z = rr * (float) Math.sin(a);
				if (i > 0) {
					out.beam(px, py, pz, x, y, z, 0.03f * sz, s.color() == 0 ? 0x1E7A3C : color(kind, s, 1f, f, time), 0.7f);
				}
				if (i % 3 == 0) {
					float chase = 0.5f + 0.5f * (float) Math.cos((f * 6 - time * 1.6f) * Math.PI * 2);
					int c = s.color() == 0 ? bulbColors[(i / 3) % bulbColors.length] : color(kind, s, (i / 3) % 2, f, time);
					out.sprite(x, y, z, 0.07f * sz, c, 0.45f + 0.55f * chase, GLOW);
				}
				px = x;
				py = y;
				pz = z;
			}
			// Baubles orbiting.
			for (int i = 0; i < 6; i++) {
				float a = -time * 0.7f + i * (float) (Math.PI / 3);
				float y = 0.5f + (i % 3) * 0.55f + 0.05f * (float) Math.sin(time * 2 + i);
				float br = 0.95f * sz;
				int c = s.color() == 0 ? (i % 2 == 0 ? 0xFF3B3B : 0xFFD24D) : color(kind, s, i % 2, i / 6f, time);
				out.sprite(br * (float) Math.cos(a), y, br * (float) Math.sin(a), 0.11f * sz, c, 0.9f, GLOW);
				out.sprite(br * (float) Math.cos(a), y + 0.03f, br * (float) Math.sin(a), 0.04f * sz, 0xFFFFFF, 0.8f, GLOW);
			}
			// The star on top, twinkling.
			float twinkle = 0.75f + 0.25f * (float) Math.sin(time * 4);
			out.sprite(0, 2.25f * Math.max(0.85f, sz * 0.9f), 0, 0.28f * sz * twinkle, s.color() == 0 ? 0xFFE27A : color(kind, s, 0f, 0, time), 1f, STAR);
			out.sprite(0, 2.25f * Math.max(0.85f, sz * 0.9f), 0, 0.5f * sz, s.color() == 0 ? 0xFFD24D : color(kind, s, 0f, 0, time), 0.25f, GLOW);
		}

		/** A hexagon outline of radius {@code r} at height {@code y}, turned by {@code rot}. */
		private void drawHex(Out out, float r, float y, float rot, int rgb, float alpha, float width) {
			for (int i = 0; i < 6; i++) {
				float a1 = rot + i * (float) (Math.PI / 3);
				float a2 = a1 + (float) (Math.PI / 3);
				out.beam(r * (float) Math.cos(a1), y, r * (float) Math.sin(a1), r * (float) Math.cos(a2), y, r * (float) Math.sin(a2), width, rgb, alpha);
			}
		}

		private void drawHologram(Settings s, Out out) {
			float sz = s.size();
			// The plate: a faint glow, two hexagons turning against each other, light pillars at the corners.
			out.sprite(0, 0.03f, 0, 1.4f * sz, color(kind, s, 0.2f, 0, time), 0.16f, DISC);
			drawHex(out, 0.9f * sz, 0.04f, time * 0.35f, color(kind, s, 0.1f, 0, time), 0.85f, 0.022f * sz);
			drawHex(out, 0.84f * sz, 0.04f, time * 0.35f, color(kind, s, 0.1f, 0, time), 0.35f, 0.01f * sz);
			drawHex(out, 0.55f * sz, 0.04f, -time * 0.6f, color(kind, s, 0.85f, 0, time), 0.7f, 0.018f * sz);
			ring(out, 0.3f * sz, 0.04f, 48, 0.01f * sz, color(kind, s, 0.5f, 0, time), 0.6f);
			for (int i = 0; i < 6; i++) {
				float a = time * 0.35f + i * (float) (Math.PI / 3);
				float flick = 0.45f + 0.35f * (float) Math.sin(time * 4 + i * 1.7f);
				out.beam(0.9f * sz * (float) Math.cos(a), 0.04f, 0.9f * sz * (float) Math.sin(a), 0.9f * sz * (float) Math.cos(a), 0.55f + 0.2f * flick, 0.9f * sz * (float) Math.sin(a),
						0.02f * sz, color(kind, s, 0.3f, i / 6f, time), 0.35f * flick);
			}
			// Fine scan lines: a faint cylinder of thin rings drifting up, like a projected hologram.
			int lines = 14;
			for (int k = 0; k < lines; k++) {
				float y = 0.08f + ((k + time * 0.25f) % lines) / lines * 2.05f;
				float edge = Math.min(1, Math.min(y * 4, (2.15f - y) * 3));
				ring(out, 0.64f * sz, y, 64, 0.006f * sz, color(kind, s, 0.15f, k / (float) lines, time), 0.22f * edge);
			}
			// Two bright scan rings sweeping up through the body, each a sharp line with a softer twin.
			for (int k = 0; k < 2; k++) {
				float f = (time * 0.32f + k * 0.5f) % 1f;
				float y = 0.05f + f * 2.1f;
				float r = (0.6f + 0.08f * (float) Math.sin(time * 2 + k * 3)) * sz;
				float alpha = (float) Math.sin(Math.PI * f) * 0.9f;
				ring(out, r, y, 96, 0.014f * sz, color(kind, s, f, 0, time), alpha);
				ring(out, r * 1.02f, y - 0.035f * sz, 96, 0.008f * sz, color(kind, s, f, 0.5f, time), alpha * 0.45f);
			}
			// Data glyphs orbiting at different heights in two counter-turning bands, always facing outwards.
			for (int i = 0; i < 14; i++) {
				boolean outer = i % 2 == 0;
				float a = (outer ? time * 0.5f : -time * 0.35f) + i * (float) (Math.PI * 2 / 14);
				float r = (outer ? 0.8f : 0.7f) * sz;
				float y = 0.3f + (i * 0.29f) % 1.75f + 0.06f * (float) Math.sin(time * 1.5f + i);
				int g = (int) Math.floorMod((long) (time * 3) + i * 5L, (long) GLYPHS.length());
				float nx = (float) Math.cos(a);
				float nz = (float) Math.sin(a);
				float gs = (outer ? 0.12f : 0.09f) * sz;
				out.panel(r * nx, y, r * nz, gs, gs, nx, nz, CELL_GLYPH + g, color(kind, s, 0.2f + 0.6f * ((i % 3) / 2f), i / 14f, time), 0.9f);
				out.panel(r * nx, y, r * nz, gs, gs, -nz, nx, CELL_GLYPH + g, color(kind, s, 0.2f, i / 14f, time), 0.7f);
			}
		}

		/** A thin horizontal ring of {@code segments} beams (many, so it reads as a smooth circle). */
		private static void ring(Out out, float r, float y, int segments, float width, int color, float alpha) {
			if (alpha <= 0.01f) {
				return;
			}
			float step = (float) (Math.PI * 2 / segments);
			for (int i = 0; i < segments; i++) {
				float a1 = i * step;
				float a2 = a1 + step;
				out.beam(r * (float) Math.cos(a1), y, r * (float) Math.sin(a1), r * (float) Math.cos(a2), y, r * (float) Math.sin(a2), width, color, alpha);
			}
		}

		private void drawStorm(Settings s, Out out) {
			float sz = s.size();
			float r = 0.42f * sz;
			out.sprite(0, 1.0f, 0, 1.3f * sz, color(kind, s, 0.5f, 0, time), 0.05f + 0.04f * (float) Math.abs(Math.sin(time * 17)), GLOW);
			for (Arc arc : arcs) {
				// The jagged path changes every few hundredths of a second (flicker).
				Random jitter = new Random(arc.seed + (long) (arc.age * 30));
				int points = 7;
				float[] xs = new float[points];
				float[] ys = new float[points];
				float[] zs = new float[points];
				for (int i = 0; i < points; i++) {
					float f = i / (float) (points - 1);
					float a = arc.a1 + (arc.a2 - arc.a1) * f;
					float h = arc.h1 + (arc.h2 - arc.h1) * f;
					float rr = r + (i == 0 || i == points - 1 ? 0 : (jitter.nextFloat() - 0.5f) * 0.22f * sz);
					xs[i] = rr * (float) Math.cos(a);
					zs[i] = rr * (float) Math.sin(a);
					ys[i] = h + (i == 0 || i == points - 1 ? 0 : (jitter.nextFloat() - 0.5f) * 0.18f * sz);
				}
				float f = arc.age / arc.life;
				float alpha = (1 - f) * (0.7f + 0.3f * jitter.nextFloat());
				int c = color(kind, s, (arc.seed & 1) == 0 ? 0f : 1f, (arc.seed & 255) / 255f, time);
				for (int i = 0; i < points - 1; i++) {
					out.beam(xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], 0.17f * sz, c, alpha * 0.65f);
					out.beam(xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], 0.05f * sz, 0xFFFFFF, alpha);
				}
				out.sprite(xs[points - 1], ys[points - 1], zs[points - 1], 0.12f * sz, c, alpha, GLOW);
			}
		}

		// ==== Seraph, Sakura, Galaxy, Amethyst, Soulfire, Butterflies, Bass Drop, Fireworks, Spirit Dragon, Silk Ribbons ====

		/** Particle roles that aren't plain shapes (kept in {@link P#shape}). */
		private static final int ROLE_ROCKET = 1000;
		private static final int ROLE_SPARK = 1001;
		private static final int ROLE_WILLOW = 1002;
		private static final int ROLE_FLASH = 1003;
		private static final int ROLE_STREAK = 1004;
		private static final int ROLE_RING = 1005;
		private static final int ROLE_RISER = 1006;

		/** Firework colours when the aura's own colours are chosen. */
		private static final int[] FIREWORK_COLORS = {0xFF4D6D, 0xFFD84D, 0x5CFFB0, 0x6FA8FF, 0xD86CFF, 0xFF8A3D};

		private final float[] debts = new float[3];
		private final List<P> bursts = new ArrayList<>();

		/** Like {@link #count(float, float)}, for a further stream with its own rate. */
		private int count(float t, float perSecond, int slot) {
			debts[slot] += t * perSecond;
			int n = (int) debts[slot];
			debts[slot] -= n;
			return n;
		}

		private P spawn(double x, double y, double z, float life, float size, int shape) {
			P p = new P();
			p.x = x;
			p.y = y;
			p.z = z;
			p.life = life;
			p.size = size;
			p.shape = shape;
			add(p);
			return p;
		}

		/** Fades in over {@code 1 / in} seconds and out over the last {@code 1 / out}. */
		private static float fade(P p, float in, float out) {
			return Math.max(0, Math.min(1, Math.min(p.age * in, (p.life - p.age) * out)));
		}

		private static float twinkle(P p, float rate) {
			return 0.55f + 0.45f * sin(p.age * rate + p.seed * 40);
		}

		// -- Emission --

		private void emitSeraph(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int k = count(t, 3.2f * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.3f, 1.05f) * sz;
				P p = spawn(px + Math.cos(a) * r, py + rnd(2.35f, 2.75f), pz + Math.sin(a) * r, rnd(5.5f, 7f), rnd(0.1f, 0.14f) * sz, FEATHER);
				p.vy = -rnd(0.24f, 0.34f);
				p.spin = rnd(1.5f, 2.3f);
			}
			for (int k = count(t, 9 * s.density(), 1); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.25f, 0.95f) * sz;
				P p = spawn(px + Math.cos(a) * r, py + rnd(0.1f, 2.2f), pz + Math.sin(a) * r, rnd(1f, 1.6f), rnd(0.045f, 0.075f) * sz, SPARKLE);
				p.vy = rnd(0.12f, 0.3f);
			}
		}

		private void emitSakura(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int k = count(t, 24 * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.45f, 0.95f) * sz;
				P p = spawn(px + Math.cos(a) * r, py + rnd(0, 0.5f), pz + Math.sin(a) * r, rnd(3.2f, 4.4f), rnd(0.055f, 0.085f) * sz, PETAL);
				p.vy = rnd(0.4f, 0.65f);
				p.rot = rnd(0, TAU);
				p.spin = rnd(2f, 5f) * (random.nextBoolean() ? 1 : -1);
			}
		}

		private void emitGalaxy(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int k = count(t, 0.7f * s.density(), 0); k > 0; k--) {
				// A shooting star crossing above you.
				float a = rnd(0, TAU);
				float dir = random.nextBoolean() ? 1 : -1;
				float r = 1.3f * sz;
				P p = spawn(px + Math.cos(a) * r, py + rnd(2.3f, 2.9f), pz + Math.sin(a) * r, rnd(0.55f, 0.8f), rnd(0.06f, 0.08f) * sz, ROLE_STREAK);
				p.vx = -sin(a) * 2.2f * dir;
				p.vz = cos(a) * 2.2f * dir;
				p.vy = -rnd(1.0f, 1.5f);
			}
			for (int k = count(t, 10 * s.density(), 1); k > 0; k--) {
				// Star dust rising off the disc.
				float a = rnd(0, TAU);
				float r = (float) Math.sqrt(random.nextFloat()) * 1.3f * sz;
				P p = spawn(px + Math.cos(a) * r, py + 0.06, pz + Math.sin(a) * r, rnd(1.5f, 2.4f), rnd(0.025f, 0.045f) * sz, STAR);
				p.vy = rnd(0.25f, 0.5f);
			}
		}

		private void emitCrystal(float t, Settings s, double px, double py, double pz) {
			int n = shardCount(s);
			for (int k = count(t, 12 * s.density(), 0); k > 0; k--) {
				int i = random.nextInt(n);
				float[] at = shard(i, n, s);
				P p = spawn(px + at[0] + rnd(-0.06f, 0.06f), py + at[1] + rnd(-0.12f, 0.12f), pz + at[2] + rnd(-0.06f, 0.06f), rnd(0.9f, 1.5f),
						rnd(0.035f, 0.06f) * s.size(), SPARKLE);
				p.vx = rnd(-0.1f, 0.1f);
				p.vy = rnd(-0.18f, 0.05f);
				p.vz = rnd(-0.1f, 0.1f);
				p.tint = i;
			}
		}

		private void emitSoul(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int k = count(t, 14 * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.55f, 0.7f) * sz;
				P p = spawn(px + Math.cos(a) * r, py + 0.1, pz + Math.sin(a) * r, rnd(1.2f, 1.8f), rnd(0.035f, 0.055f) * sz, GLOW);
				p.vy = rnd(0.6f, 1.0f);
			}
		}

		private void emitButterflies(float t, Settings s, double px, double py, double pz) {
			int n = butterflyCount(s);
			for (int k = count(t, 10 * s.density(), 0); k > 0; k--) {
				int i = random.nextInt(n);
				float[] at = butterfly(i, s, time);
				P p = spawn(px + at[0], py + at[1], pz + at[2], rnd(1f, 1.6f), rnd(0.03f, 0.05f) * s.size(), SPARKLE);
				p.vx = rnd(-0.08f, 0.08f);
				p.vy = -rnd(0.1f, 0.3f);
				p.vz = rnd(-0.08f, 0.08f);
				p.tint = i;
			}
		}

		private void emitFireworks(float t, Settings s, double px, double py, double pz) {
			for (int k = count(t, 1.1f * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.55f, 0.9f) * s.size();
				P p = spawn(px + Math.cos(a) * r, py + 0.1, pz + Math.sin(a) * r, rnd(0.55f, 0.62f), 0.07f * s.size(), ROLE_ROCKET);
				p.vx = -cos(a) * rnd(0.1f, 0.3f);
				p.vy = rnd(4.2f, 4.6f);
				p.vz = -sin(a) * rnd(0.1f, 0.3f);
			}
		}

		private void burst(P rocket, Settings s) {
			float sz = s.size();
			int type = random.nextInt(3); // 0 peony, 1 ring, 2 golden willow
			int c1 = burstColor(s);
			int c2 = random.nextFloat() < 0.35f ? burstColor(s) : c1;
			P flash = spawn(rocket.x, rocket.y, rocket.z, 0.3f, 0.7f * sz, ROLE_FLASH);
			flash.tint = c1;
			int n = Math.round((type == 1 ? 30 : 44) * Math.min(1.6f, 0.6f + 0.4f * s.density()));
			float speed = (type == 2 ? 1.15f : 1.6f) * sz;
			float tilt = rnd(0.3f, 0.9f);
			float turn = rnd(0, TAU);
			for (int k = 0; k < n; k++) {
				float dx;
				float dy;
				float dz;
				if (type == 1) {
					// A ring, tilted towards you.
					float a = k * TAU / n;
					float rx = cos(a);
					float rz = sin(a);
					float ry = -rz * sin(tilt);
					rz *= cos(tilt);
					dx = rx * cos(turn) - rz * sin(turn);
					dz = rx * sin(turn) + rz * cos(turn);
					dy = ry;
				} else {
					dy = rnd(-1, 1);
					float a = rnd(0, TAU);
					float rr = (float) Math.sqrt(1 - dy * dy);
					dx = rr * cos(a);
					dz = rr * sin(a);
				}
				float v = speed * rnd(0.85f, 1.05f);
				P p = spawn(rocket.x, rocket.y, rocket.z, type == 2 ? rnd(1.6f, 2.1f) : rnd(0.95f, 1.35f), rnd(0.065f, 0.085f) * sz,
						type == 2 ? ROLE_WILLOW : ROLE_SPARK);
				p.vx = dx * v + rocket.vx * 0.3f;
				p.vy = dy * v + rocket.vy * 0.15f;
				p.vz = dz * v + rocket.vz * 0.3f;
				p.tint = type == 2 && s.color() == 0 ? 0xFFC45C : k % 2 == 0 ? c1 : c2;
			}
		}

		private int burstColor(Settings s) {
			if (s.color() == 0) {
				return FIREWORK_COLORS[random.nextInt(FIREWORK_COLORS.length)];
			}
			if (s.color() == RAINBOW) {
				return hsv(random.nextFloat(), 0.7f, 1f);
			}
			return color(kind, s, random.nextFloat(), 0, time);
		}

		private void emitDragon(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int d = 0; d < 2; d++) {
				boolean yin = d == 1;
				float[] head = dragonSpine(time, sz, yin);
				float[] neck = dragonSpine(time - 0.06f, sz, yin);
				float hx = head[0] - neck[0];
				float hy = head[1] - neck[1];
				float hz = head[2] - neck[2];
				float len = Math.max(1e-4f, (float) Math.sqrt(hx * hx + hy * hy + hz * hz));
				hx /= len;
				hy /= len;
				hz /= len;
				for (int k = count(t, 9 * s.density(), d); k > 0; k--) {
					// Breath: sparks blown forward out of the mouth.
					P p = spawn(px + head[0] + hx * 0.15f * sz, py + head[1] + hy * 0.15f * sz, pz + head[2] + hz * 0.15f * sz, rnd(0.5f, 0.8f),
							rnd(0.05f, 0.08f) * sz, GLOW);
					float v = rnd(0.8f, 1.4f);
					p.vx = hx * v + rnd(-0.35f, 0.35f);
					p.vy = hy * v + rnd(-0.2f, 0.35f);
					p.vz = hz * v + rnd(-0.35f, 0.35f);
					p.tint = d;
				}
			}
			for (int k = count(t, 6 * s.density(), 2); k > 0; k--) {
				// Pearls of light shed from the bodies.
				int d = random.nextInt(2);
				float[] at = dragonSpine(time - rnd(0.2f, 3.4f), sz, d == 1);
				P p = spawn(px + at[0], py + at[1], pz + at[2], rnd(1f, 1.5f), rnd(0.035f, 0.055f) * sz, SPARKLE);
				p.vy = -rnd(0.05f, 0.2f);
				p.tint = d;
			}
		}

		private void emitRibbons(float t, Settings s, double px, double py, double pz) {
			int n = ribbonCount(s);
			for (int k = count(t, 14 * s.density(), 0); k > 0; k--) {
				int i = random.nextInt(n);
				float f = rnd(0.1f, 0.9f);
				float[] at = ribbonPoint(i, n, f, s);
				P p = spawn(px + at[0], py + at[1], pz + at[2], rnd(1f, 1.6f), rnd(0.03f, 0.05f) * s.size(), random.nextInt(3) == 0 ? SPARKLE : GLOW);
				p.vx = rnd(-0.06f, 0.06f);
				p.vy = rnd(-0.08f, 0.1f);
				p.vz = rnd(-0.06f, 0.06f);
				p.rot = f;
			}
		}

		// -- Bass Drop: bars that follow a beat, shockwaves on the kicks --

		private static final int BARS = 32;
		private static final float BEAT = 0.47f;
		private final float[] barLevel = new float[BARS];
		private final float[] barPeak = new float[BARS];
		private final float[] peakFall = new float[BARS];
		private int lastBeat = -1;

		private float kick() {
			return (float) Math.exp(-(time % BEAT) / BEAT * 7);
		}

		private void updateBass(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			float env = kick();
			for (int i = 0; i < BARS; i++) {
				// A made-up spectrum: the kick lifts everything (most of all the bass bars), the rest wobbles.
				float bass = (float) Math.pow(Math.abs(cos(i * TAU / BARS * 0.5f)), 3);
				float target = 0.12f + env * (0.35f + 0.5f * bass) * (0.75f + 0.25f * sin(i * 0.9f + time * 2.1f))
						+ 0.22f * (0.5f + 0.5f * sin(time * 7.3f + i * 1.7f)) * (0.5f + 0.5f * sin(time * 3.1f - i * 0.6f));
				barLevel[i] = target > barLevel[i] ? target : Math.max(target, barLevel[i] - 2.2f * t);
				if (barLevel[i] >= barPeak[i]) {
					barPeak[i] = barLevel[i];
					peakFall[i] = 0;
				} else {
					peakFall[i] += 2.5f * t;
					barPeak[i] = Math.max(barLevel[i], barPeak[i] - peakFall[i] * t);
				}
			}
			int beat = (int) (time / BEAT);
			if (beat != lastBeat) {
				boolean drop = lastBeat >= 0 && beat % 4 == 0;
				lastBeat = beat;
				spawn(px, py + 0.04, pz, 0.65f, 0.3f * sz, ROLE_RING);
				if (drop) {
					// Every fourth kick: a ring rides up your body and sparks fly off the bars.
					spawn(px, py + 0.05, pz, 0.6f, 0.58f * sz, ROLE_RISER).vy = 3.6f;
					for (int k = 0; k < Math.round(12 * s.density()); k++) {
						float a = rnd(0, TAU);
						float r = 0.8f * sz;
						P p = spawn(px + Math.cos(a) * r, py + rnd(0.3f, 0.7f), pz + Math.sin(a) * r, rnd(0.5f, 0.75f), rnd(0.04f, 0.06f) * sz, SPARKLE);
						p.vx = cos(a) * rnd(1f, 1.8f);
						p.vy = rnd(1f, 2f);
						p.vz = sin(a) * rnd(1f, 1.8f);
					}
				}
			}
		}

		// -- Motion --

		private void move2(P p, float t, double px, double pz) {
			switch (kind) {
				case SERAPH -> {
					if (p.shape == FEATHER) {
						// A falling feather swings like a pendulum, tilting with each swing.
						float phase = p.age * p.spin + p.seed * 20;
						float axis = p.seed * TAU;
						p.rot = 0.85f * sin(phase);
						p.vx = cos(axis) * cos(phase) * 0.3f;
						p.vz = sin(axis) * cos(phase) * 0.3f;
					}
				}
				case SAKURA -> {
					// Carried round and up by the wind, drifting outwards, tumbling.
					double dx = p.x - px;
					double dz = p.z - pz;
					double w = (1.5 + 0.6 * p.seed) * t;
					double grow = 1 + 0.08 * t;
					p.x = px + (dx * Math.cos(w) - dz * Math.sin(w)) * grow;
					p.z = pz + (dx * Math.sin(w) + dz * Math.cos(w)) * grow;
					p.vy *= 1 - 0.12f * t;
					p.vx = sin(p.age * 3.1f + p.seed * 17) * 0.12f;
					p.vz = cos(p.age * 2.7f + p.seed * 11) * 0.12f;
					p.rot += p.spin * t;
				}
				case GALAXY -> {
					if (p.shape == STAR) {
						double dx = p.x - px;
						double dz = p.z - pz;
						double w = 0.45 * t;
						p.x = px + dx * Math.cos(w) - dz * Math.sin(w);
						p.z = pz + dx * Math.sin(w) + dz * Math.cos(w);
					}
				}
				case SOUL -> {
					p.vx = sin(p.age * 4 + p.seed * 10) * 0.15f;
					p.vz = cos(p.age * 3.4f + p.seed * 7) * 0.15f;
				}
				case BASS -> {
					if (p.shape == SPARKLE) {
						p.vy -= 5f * t;
					}
				}
				case FIREWORK -> {
					switch (p.shape) {
						case ROLE_ROCKET -> {
							p.vy -= 3f * t;
							p.vx += sin(p.age * 30 + p.seed * 40) * 0.6f * t;
						}
						case ROLE_SPARK -> {
							float drag = Math.max(0, 1 - 1.5f * t);
							p.vx *= drag;
							p.vy = p.vy * drag - 1.6f * t;
							p.vz *= drag;
						}
						case ROLE_WILLOW -> {
							float drag = Math.max(0, 1 - 1.1f * t);
							p.vx *= drag;
							p.vy = p.vy * drag - 1.2f * t;
							p.vz *= drag;
						}
						default -> {
						}
					}
				}
				case RAIN -> {
					if (p.shape == ROLE_DROPLET) {
						p.vy -= 6f * t;
					}
				}
				case BLADES -> {
					if (p.shape == SPARKLE) {
						p.vy -= 5f * t;
					}
				}
				case LANTERN -> {
					if (p.shape == LANTERN) {
						p.vx = sin(p.age * p.spin + p.seed * 10) * 0.06f;
						p.vz = cos(p.age * p.spin * 0.8f + p.seed * 7) * 0.06f;
						p.rot = sin(p.age * p.spin + p.seed * 10) * 0.08f;
					}
				}
				case CHRONO -> {
					double dx = p.x - px;
					double dz = p.z - pz;
					double w = 0.8 * t;
					p.x = px + dx * Math.cos(w) - dz * Math.sin(w);
					p.z = pz + dx * Math.sin(w) + dz * Math.cos(w);
				}
				case PYJAMA -> {
					if (p.shape == SNOOZE) {
						// Swaying from side to side as it floats up, like a lullaby.
						p.vx = sin(p.age * p.spin + p.seed * 9) * 0.14f;
						p.rot = 0.35f * sin(p.age * p.spin + p.seed * 9);
					} else if (p.shape == SMOKE) {
						// Clouds drift slowly round you.
						double dx = p.x - px;
						double dz = p.z - pz;
						double w = 0.18 * t;
						p.x = px + dx * Math.cos(w) - dz * Math.sin(w);
						p.z = pz + dx * Math.sin(w) + dz * Math.cos(w);
					}
				}
				case DRAGON -> {
					if (p.shape == GLOW) {
						float drag = Math.max(0, 1 - 1.8f * t);
						p.vx *= drag;
						p.vy = p.vy * drag + 0.3f * t;
						p.vz *= drag;
					}
				}
				default -> {
				}
			}
		}

		// -- Drawing --

		private void drawParticle(P p, Settings s, float x, float y, float z, float f, Out out) {
			switch (kind) {
				case SERAPH -> {
					if (p.shape == FEATHER) {
						int c = color(kind, s, 0.1f + 0.35f * p.seed, p.seed, time);
						out.sprite(x, y, z, p.size, c, fade(p, 1.2f, 0.8f) * 0.9f, FEATHER | UPRIGHT, p.rot);
						out.sprite(x, y, z, p.size * 1.4f, c, fade(p, 1.2f, 0.8f) * 0.12f, GLOW | UPRIGHT);
					} else {
						out.sprite(x, y, z, p.size, color(kind, s, 0.7f, p.seed, time), fade(p, 4, 2) * twinkle(p, 9), SPARKLE);
					}
				}
				case SAKURA -> out.sprite(x, y, z, p.size, color(kind, s, p.seed * 0.85f, p.seed, time), fade(p, 2, 1.2f) * 0.95f, PETAL | UPRIGHT, p.rot);
				case GALAXY -> {
					if (p.shape == ROLE_STREAK) {
						int c = color(kind, s, 0.15f, p.seed, time);
						float a = (float) Math.sin(Math.PI * f);
						out.beam(x, y, z, x - p.vx * 0.22f, y - p.vy * 0.22f, z - p.vz * 0.22f, 0.035f * s.size(), c, a * 0.6f);
						out.sprite(x, y, z, p.size, mix(c, 0xFFFFFF, 0.5f), a, SPARKLE);
					} else {
						out.sprite(x, y, z, p.size, color(kind, s, 0.2f + 0.6f * p.seed, p.seed, time), fade(p, 3, 1.5f) * twinkle(p, 7), STAR);
					}
				}
				case CRYSTAL -> out.sprite(x, y, z, p.size * (1 - 0.4f * f), mix(color(kind, s, p.seed, p.tint / 8f, time), 0xFFFFFF, 0.35f),
						fade(p, 6, 2) * twinkle(p, 11), SPARKLE);
				case SOUL -> out.sprite(x, y, z, p.size * (1 - 0.5f * f), color(kind, s, 0.2f + 0.5f * p.seed, p.seed, time),
						(float) Math.pow(1 - f, 1.2) * twinkle(p, 14), GLOW);
				case BUTTERFLY -> out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.tint / 6f, time), fade(p, 6, 1.6f) * twinkle(p, 12), SPARKLE);
				case BASS -> {
					switch (p.shape) {
						case ROLE_RING -> {
							// The shockwave: a flat ring racing out across the ground.
							float r = (0.35f + p.age * 3.4f) * s.size();
							out.sprite(x, y, z, r / 0.74f, color(kind, s, f, 0, time), (float) Math.pow(1 - f, 1.5) * 0.9f, HALO | FLAT);
						}
						case ROLE_RISER -> {
							float r = (p.size + 0.1f * f) / 0.74f;
							out.sprite(x, y, z, r, color(kind, s, 1 - f, 0.5f, time), (float) Math.sin(Math.PI * f) * 0.95f, HALO | FLAT);
						}
						default -> out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), 1 - f, SPARKLE);
					}
				}
				case FIREWORK -> drawFireworkParticle(p, s, x, y, z, f, out);
				case DRAGON -> {
					int c = dragonGlow(s, p.tint == 1, 0.2f + 0.5f * f);
					if (p.shape == GLOW) {
						out.sprite(x, y, z, p.size * (0.6f + 1.2f * f), c, (float) Math.pow(1 - f, 1.3) * 0.85f, GLOW);
					} else {
						out.sprite(x, y, z, p.size, c, fade(p, 5, 1.5f) * twinkle(p, 10), SPARKLE);
					}
				}
				case RIBBON -> out.sprite(x, y, z, p.size, color(kind, s, p.rot, p.seed, time), fade(p, 5, 1.6f) * twinkle(p, 10), p.shape);
				case RAIN -> {
					int c = color(kind, s, 0.25f, p.seed, time);
					switch (p.shape) {
						case ROLE_RAIN -> out.beam(x, y, z, x, y + p.size, z, 0.022f * s.size(), c, 0.75f);
						case ROLE_SPLASH -> out.sprite(x, y, z, (0.02f + f * p.size) / 0.74f, mix(c, 0xFFFFFF, 0.4f), (1 - f) * 0.7f, HALO | FLAT);
						default -> out.sprite(x, y, z, p.size, c, 1 - f, GLOW);
					}
				}
				case KOI -> out.sprite(x, y, z, p.size, 0xFFFFFF, fade(p, 6, 3) * twinkle(p, 12) * 0.8f, SPARKLE | FLAT);
				case BLADES -> {
					if (p.shape == ROLE_RING) {
						float r = (0.1f + p.age * 2.4f) * s.size();
						out.sprite(x, y, z, r / 0.74f, mix(color(kind, s, 0.5f, 0, time), 0xFFFFFF, 0.3f), (float) Math.pow(1 - f, 1.4) * 0.9f, HALO | FLAT);
					} else {
						out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), 1 - f, SPARKLE);
					}
				}
				case LANTERN -> {
					if (p.shape == LANTERN) {
						float alpha = fade(p, 1, 0.6f);
						float flicker = 0.85f + 0.15f * sin(p.age * 13 + p.seed * 30);
						out.sprite(x, y, z, p.size * 2.4f, color(kind, s, 0.6f, p.seed, time), alpha * 0.32f * flicker, GLOW);
						out.sprite(x, y, z, p.size, color(kind, s, 0.15f + 0.3f * p.seed, p.seed, time), alpha * 0.95f, LANTERN | UPRIGHT, p.rot);
						out.sprite(x, y - p.size * 0.5f, z, p.size * 0.35f, 0xFFF2C0, alpha * flicker, GLOW);
					} else {
						out.sprite(x, y, z, p.size * (1 - 0.5f * f), color(kind, s, 0.3f + 0.5f * f, p.seed, time), (1 - f) * twinkle(p, 14), GLOW);
					}
				}
				case CHRONO -> out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), fade(p, 3, 1.5f) * twinkle(p, 9), p.shape);
				case PYJAMA -> {
					if (p.shape == SNOOZE) {
						// Growing as it rises and fades.
						out.sprite(x, y, z, p.size * (1 + 0.7f * f), mix(color(kind, s, 0.15f, p.seed, time), 0xFFFFFF, 0.25f), fade(p, 2, 1) * 0.95f,
								SNOOZE | FACING, p.rot);
					} else if (p.shape == SMOKE) {
						out.sprite(x, y, z, p.size, mix(color(kind, s, 1f, p.seed, time), 0xFFFFFF, 0.55f), fade(p, 1, 0.8f) * 0.32f, SMOKE);
					} else {
						out.sprite(x, y, z, p.size, color(kind, s, p.seed, p.seed, time), fade(p, 3, 2) * twinkle(p, 7), STAR);
					}
				}
				default -> {
				}
			}
		}

		private void drawFireworkParticle(P p, Settings s, float x, float y, float z, float f, Out out) {
			float sz = s.size();
			switch (p.shape) {
				case ROLE_ROCKET -> {
					// A bright head with a short burning tail.
					out.beam(x, y, z, x - p.vx * 0.14f, y - p.vy * 0.14f, z - p.vz * 0.14f, 0.05f * sz, 0xFFB347, 0.6f);
					for (int k = 1; k <= 3; k++) {
						float back = 0.05f * k;
						out.sprite(x - p.vx * back, y - p.vy * back, z - p.vz * back, 0.05f * sz * (1 - k / 4f), 0xFF9A3D, 0.45f, GLOW);
					}
					out.sprite(x, y, z, p.size, 0xFFF1C2, 1f, GLOW);
				}
				case ROLE_FLASH -> out.sprite(x, y, z, p.size * (1 + f * 1.5f), mix(p.tint, 0xFFFFFF, 0.55f), (1 - f) * 0.7f, GLOW);
				default -> {
					// A spark: white-hot as it leaves, then its colour; crackling as it dies. Willows sag and go gold.
					boolean willow = p.shape == ROLE_WILLOW;
					int c = mix(0xFFFFFF, p.tint, Math.min(1, p.age * 5));
					float alpha = (float) Math.pow(1 - f, 1.1);
					if (f > 0.55f && sin(p.age * 55 + p.seed * 80) < 0) {
						alpha *= 0.15f;
					}
					float trail = willow ? 0.12f : 0.07f;
					out.beam(x, y, z, x - p.vx * trail, y - p.vy * trail, z - p.vz * trail, p.size * 0.7f, p.tint, alpha * 0.55f);
					out.sprite(x, y, z, p.size * 2.2f * (1 - 0.35f * f), p.tint, alpha * 0.3f, GLOW);
					out.sprite(x, y, z, p.size * (1 - 0.35f * f), c, alpha, SPARKLE);
				}
			}
		}

		private void drawSeraph(Settings s, Out out) {
			float sz = s.size();
			// The halo, floating and glowing above your head.
			float hy = 2.17f + 0.03f * sin(time * 1.6f);
			float hr = 0.4f * (0.8f + 0.2f * sz);
			float shimmer = 0.5f + 0.5f * sin(time * 2.3f);
			out.sprite(0, hy, 0, hr, color(kind, s, 0.55f, 0, time), 0.95f, HALO | FLAT);
			out.sprite(0, hy, 0, hr * 1.12f, color(kind, s, 0.2f, 0, time), 0.25f + 0.2f * shimmer, HALO | FLAT);
			out.sprite(0, hy, 0, hr * 1.4f, color(kind, s, 0.8f, 0, time), 0.25f, DISC);
			for (int i = 0; i < 6; i++) {
				float a = i * TAU / 6 + time * 0.8f;
				float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(time * 3.2f + i * 2.1f), 3);
				out.sprite(hr * 0.74f * cos(a), hy, hr * 0.74f * sin(a), (0.04f + 0.06f * tw) * sz, mix(color(kind, s, 0.3f, i / 6f, time), 0xFFFFFF, 0.4f),
						0.4f + 0.6f * tw, SPARKLE);
			}
			// Soft light falling around you.
			for (int i = 0; i < 9; i++) {
				float a = i * TAU / 9 + time * 0.15f;
				float r = (0.6f + 0.18f * sin(i * 2.7f)) * sz;
				float glow = 0.5f + 0.5f * sin(time * 1.3f + i * 2.1f);
				int c = color(kind, s, 0.15f, i / 9f, time);
				float x = r * cos(a);
				float z = r * sin(a);
				out.beam(x, 2.75f, z, x * 1.08f, 1.35f, z * 1.08f, 0.22f * sz, c, 0.075f * glow);
				out.beam(x * 1.08f, 1.35f, z * 1.08f, x * 1.15f, 0.02f, z * 1.15f, 0.22f * sz, c, 0.035f * glow);
			}
			out.sprite(0, 0.03f, 0, 1.15f * sz, color(kind, s, 0.5f, 0, time), 0.2f, DISC);
			out.sprite(0, 0.04f, 0, 0.9f * sz / 0.74f, color(kind, s, 0.6f, 0, time), 0.15f + 0.1f * shimmer, HALO | FLAT);
		}

		private void drawSakura(Settings s, Out out) {
			float sz = s.size();
			out.sprite(0, 0.03f, 0, 1.2f * sz, color(kind, s, 0.9f, 0, time), 0.16f, DISC);
			// Blossoms circling your feet, opening and closing a little.
			int blossoms = 9;
			for (int b = 0; b < blossoms; b++) {
				float a = b * TAU / blossoms + time * 0.25f;
				float bx = 0.8f * sz * cos(a);
				float bz = 0.8f * sz * sin(a);
				float open = 0.85f + 0.15f * sin(time * 1.4f + b * 1.9f);
				float spin = a * 1.7f + time * 0.3f;
				int c = color(kind, s, 0.25f + 0.5f * ((b % 3) / 2f), b / (float) blossoms, time);
				for (int k = 0; k < 5; k++) {
					float pa = spin + k * TAU / 5;
					float pr = 0.045f * sz * open;
					out.sprite(bx + pr * cos(pa), 0.05f, bz + pr * sin(pa), 0.05f * sz, c, 0.75f, PETAL | FLAT, -pa - (float) Math.PI / 2);
				}
				out.sprite(bx, 0.06f, bz, 0.03f * sz, 0xFFF4C2, 0.9f, SPARKLE | FLAT);
			}
			// Wind streaks spiralling up with the petals.
			for (int k = 0; k < 3; k++) {
				float f0 = (time * 0.45f + k / 3f) % 1f;
				float a0 = k * TAU / 3 + time * 1.4f;
				float r = (0.75f + f0 * 0.25f) * sz;
				float[] prev = null;
				for (int j = 0; j <= 12; j++) {
					float a = a0 + j * 0.12f;
					float[] q = {r * cos(a), 0.1f + f0 * 2.1f + j * 0.025f, r * sin(a)};
					if (prev != null) {
						float alpha = (float) Math.sin(Math.PI * f0) * 0.3f * (float) Math.sin(Math.PI * j / 12f);
						out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.025f * sz, color(kind, s, 0f, k / 3f, time), alpha);
					}
					prev = q;
				}
			}
		}

		private void drawGalaxy(Settings s, Out out) {
			float sz = s.size();
			float big = 1.35f * sz;
			// The bright core and the glow of the disc.
			out.sprite(0, 0.04f, 0, 0.7f * sz, color(kind, s, 0f, 0, time), 0.7f, DISC);
			out.sprite(0, 0.03f, 0, big * 1.1f, color(kind, s, 0.6f, 0, time), 0.24f, DISC);
			// Nebula clouds along the two arms.
			for (int j = 0; j < 14; j++) {
				int arm = j % 2;
				float rf = 0.22f + 0.75f * ((j / 2) / 6f);
				float a = arm * (float) Math.PI + rf * 3.4f + time * 0.55f / (0.35f + rf);
				out.sprite(rf * big * cos(a), 0.05f, rf * big * sin(a), (0.36f + 0.18f * rf) * sz, color(kind, s, 0.25f + 0.7f * rf, j / 14f, time),
						0.38f + 0.08f * sin(time + j), SMOKE | FLAT, a + time * 0.1f);
			}
			// Stars: two spiral arms, the inner part turning faster.
			int stars = Math.max(40, Math.min(220, Math.round(110 * s.density())));
			for (int i = 0; i < stars; i++) {
				float h1 = hash(i, 1);
				float h2 = hash(i, 2);
				float rf = 0.12f + 0.88f * (float) Math.sqrt(h1);
				float a = (i % 2) * (float) Math.PI + rf * 3.4f + (h2 - 0.5f) * 0.6f * (1.2f - rf * 0.5f) + time * 0.55f / (0.35f + rf);
				float y = 0.05f + (hash(i, 3) - 0.5f) * 0.08f * (1 - rf);
				float tw = 0.7f + 0.3f * sin(time * 3 + i * 1.3f);
				int shape = i % 9 == 0 ? SPARKLE : STAR;
				out.sprite(rf * big * cos(a), y, rf * big * sin(a), (0.034f + 0.045f * hash(i, 4)) * sz * tw, color(kind, s, rf, h1, time),
						0.55f + 0.45f * tw, shape);
			}
		}

		private static float hash(int i, int salt) {
			int h = i * 0x27d4eb2d + salt * 0x165667b1;
			h ^= h >>> 15;
			h *= 0x85ebca6b;
			h ^= h >>> 13;
			return (h & 0xFFFFFF) / (float) 0x1000000;
		}

		private static int shardCount(Settings s) {
			return Math.max(4, Math.min(12, Math.round(7 * (0.6f + 0.4f * s.density()))));
		}

		/** Where crystal {@code i} of {@code n} floats right now (player space). */
		private float[] shard(int i, int n, Settings s) {
			float a = i * TAU / n + time * 0.35f;
			float r = (0.74f + 0.06f * sin(time * 0.8f + i * 1.7f)) * s.size();
			float y = 0.5f + ((i * 0.618f) % 1f) * 1.3f + 0.08f * sin(time * 1.4f + i * 2.3f);
			return new float[] {r * cos(a), y, r * sin(a)};
		}

		private void drawCrystal(Settings s, Out out) {
			float sz = s.size();
			int n = shardCount(s);
			for (int i = 0; i < n; i++) {
				float[] at = shard(i, n, s);
				float size = (0.2f + 0.09f * hash(i, 5)) * sz;
				int c = color(kind, s, hash(i, 6), i / (float) n, time);
				out.sprite(at[0], at[1], at[2], size * 1.7f, color(kind, s, 1f, i / (float) n, time), 0.24f, GLOW);
				out.sprite(at[0], at[1], at[2], size, c, 0.92f, SHARD | UPRIGHT, 0.25f * sin(time * 0.9f + i) + time * 0.4f * (i % 2 == 0 ? 1 : -1) * 0.3f);
				// Now and then light catches it.
				float glint = (float) Math.pow(Math.max(0, Math.sin(time * 1.7f + i * 2.4f)), 24);
				if (glint > 0.02f) {
					out.sprite(at[0], at[1] + size * 0.3f, at[2], 0.14f * sz * glint, mix(c, 0xFFFFFF, 0.6f), glint, SPARKLE);
				}
			}
			// A ring of small crystals on the ground, a pulse running round it.
			out.sprite(0, 0.03f, 0, 1.3f * sz, color(kind, s, 0.9f, 0, time), 0.15f, DISC);
			out.sprite(0, 0.04f, 0, 0.95f * sz / 0.74f, color(kind, s, 0.7f, 0, time), 0.22f, HALO | FLAT);
			for (int i = 0; i < 12; i++) {
				float a = i * TAU / 12 + time * 0.1f;
				float pulse = (float) Math.pow(0.5 + 0.5 * Math.sin(time * 2.2f - i * 0.9f), 3);
				out.sprite(0.62f * sz * cos(a), 0.05f, 0.62f * sz * sin(a), 0.09f * sz, color(kind, s, 0.3f + 0.5f * pulse, i / 12f, time),
						0.45f + 0.55f * pulse, SHARD | FLAT, -a - (float) Math.PI / 2);
			}
		}

		private static int wispCount(Settings s) {
			return Math.max(3, Math.min(6, Math.round(4 * (0.5f + 0.5f * s.density()))));
		}

		/** Where soul wisp {@code i} is at time {@code tt} (player space). */
		private static float[] wisp(int i, int n, float tt, Settings s) {
			float dir = i % 2 == 0 ? 1 : -1;
			float a = tt * (0.9f + 0.15f * i) * dir + i * TAU / n;
			float r = (0.75f + 0.15f * sin(tt * 1.3f + i * 2)) * s.size();
			float y = 0.95f + 0.55f * sin(tt * 0.8f + i * 1.9f) + 0.1f * sin(tt * 2.6f + i);
			return new float[] {r * cos(a), y, r * sin(a)};
		}

		private void drawSoul(Settings s, Out out) {
			float sz = s.size();
			int n = wispCount(s);
			for (int i = 0; i < n; i++) {
				// Ghostly smoke trailing behind, spreading as it fades.
				for (int k = 12; k >= 1; k--) {
					float[] q = wisp(i, n, time - k * 0.045f, s);
					float fade = 1 - k / 13f;
					out.sprite(q[0], q[1] + k * 0.012f, q[2], (0.08f + k * 0.012f) * sz, color(kind, s, 0.3f + 0.7f * k / 12f, i / (float) n, time),
							0.3f * fade, SMOKE, k * 0.5f + time);
				}
				float[] p = wisp(i, n, time, s);
				float flicker = 0.9f + 0.1f * sin(time * 19 + i * 5);
				int c = color(kind, s, 0.25f, i / (float) n, time);
				out.sprite(p[0], p[1], p[2], 0.3f * sz, color(kind, s, 0.7f, i / (float) n, time), 0.3f, GLOW);
				out.sprite(p[0], p[1] + 0.05f * sz, p[2], 0.17f * sz * flicker, c, 0.95f, FLAME | UPRIGHT);
				out.sprite(p[0], p[1], p[2], 0.06f * sz, mix(c, 0xFFFFFF, 0.7f), 0.9f, GLOW);
			}
			// A ring of small soul flames at your feet.
			out.sprite(0, 0.03f, 0, 1.25f * sz, color(kind, s, 1f, 0, time), 0.2f + 0.05f * sin(time * 1.7f), DISC);
			for (int i = 0; i < 18; i++) {
				float a = i * TAU / 18 + time * 0.2f;
				float h = (0.07f + 0.04f * (0.5f + 0.5f * sin(time * (9 + i % 5) + i * 3))) * sz;
				out.sprite(0.62f * sz * cos(a), h * 0.8f, 0.62f * sz * sin(a), h, color(kind, s, 0.15f + 0.6f * ((i % 3) / 2f), i / 18f, time), 0.75f,
						FLAME | UPRIGHT);
			}
		}

		private static int butterflyCount(Settings s) {
			return Math.max(2, Math.min(8, Math.round(5 * (0.5f + 0.5f * s.density()))));
		}

		/** Where butterfly {@code i} flies at time {@code tt} (player space). */
		private static float[] butterfly(int i, Settings s, float tt) {
			float dir = i % 2 == 0 ? 1 : -1;
			float a = tt * 0.55f * dir + i * 2.4f + 0.4f * sin(tt * 0.9f + i);
			float r = (0.8f + 0.2f * sin(tt * 0.7f + i * 1.3f)) * s.size();
			float y = 1.05f + 0.45f * sin(tt * 0.5f + i * 2.2f) + 0.04f * sin(tt * 14 + i * 1.7f);
			return new float[] {r * cos(a), y, r * sin(a)};
		}

		private void drawButterflies(Settings s, Out out) {
			float sz = s.size();
			int n = butterflyCount(s);
			out.sprite(0, 0.03f, 0, 1.1f * sz, color(kind, s, 0.5f, 0, time), 0.12f, DISC);
			for (int i = 0; i < n; i++) {
				float[] p = butterfly(i, s, time);
				float[] ahead = butterfly(i, s, time + 0.03f);
				// Heading along its path (kept fairly level), sideways and up from there.
				float hx = ahead[0] - p[0];
				float hy = (ahead[1] - p[1]) * 0.3f;
				float hz = ahead[2] - p[2];
				float hl = Math.max(1e-5f, (float) Math.sqrt(hx * hx + hy * hy + hz * hz));
				hx /= hl;
				hy /= hl;
				hz /= hl;
				float sxl = (float) Math.sqrt(hx * hx + hz * hz);
				float sx = hz / Math.max(1e-5f, sxl);
				float szz = -hx / Math.max(1e-5f, sxl);
				// Wings beating: from nearly flat to nearly closed above the back.
				float flap = 0.1f + 1.1f * (0.5f + 0.5f * sin(time * 14 + i * 1.7f));
				float span = 0.17f * sz;
				float length = 0.18f * sz;
				int c = color(kind, s, 0.15f + 0.7f * hash(i, 7), i / (float) n, time);
				for (int side = -1; side <= 1; side += 2) {
					float wx = sx * side * cos(flap);
					float wy = sin(flap);
					float wz = szz * side * cos(flap);
					float cx = p[0] + wx * span;
					float cy = p[1] + wy * span;
					float cz = p[2] + wz * span;
					float ux = wx * span;
					float uy = wy * span;
					float uz = wz * span;
					float vx = hx * length;
					float vy = hy * length;
					float vz = hz * length;
					out.quad(new float[] {cx - ux + vx, cy - uy + vy, cz - uz + vz, cx - ux - vx, cy - uy - vy, cz - uz - vz, cx + ux - vx, cy + uy - vy,
							cz + uz - vz, cx + ux + vx, cy + uy + vy, cz + uz + vz}, CELL_WING, c, 0.92f);
				}
				out.beam(p[0] - hx * 0.07f * sz, p[1] - hy * 0.07f * sz, p[2] - hz * 0.07f * sz, p[0] + hx * 0.07f * sz, p[1] + hy * 0.07f * sz,
						p[2] + hz * 0.07f * sz, 0.03f * sz, mix(c, 0xFFFFFF, 0.6f), 0.85f);
				out.sprite(p[0], p[1], p[2], 0.22f * sz, c, 0.4f, GLOW);
			}
		}

		private void drawBass(Settings s, Out out) {
			float sz = s.size();
			float env = kick();
			out.sprite(0, 0.03f, 0, 1.3f * sz, color(kind, s, 0.3f, 0, time), 0.12f + 0.3f * env, DISC);
			float r = 0.8f * sz;
			for (int i = 0; i < BARS; i++) {
				float a = i * TAU / BARS + time * 0.1f;
				float x = r * cos(a);
				float z = r * sin(a);
				float top = 0.05f + 1.0f * barLevel[i] * sz;
				// Coloured from the first colour at the floor to the second at the top.
				for (int k = 0; k < 3; k++) {
					float y0 = 0.03f + (top - 0.03f) * k / 3f;
					float y1 = 0.03f + (top - 0.03f) * (k + 1) / 3f;
					out.beam(x, y0, z, x, y1, z, 0.09f * sz, color(kind, s, (k + 0.5f) / 3f * Math.min(1, barLevel[i] * 1.3f), i / (float) BARS, time), 0.85f);
				}
				float peak = 0.05f + 1.0f * barPeak[i] * sz + 0.04f;
				out.beam(x, peak, z, x, peak + 0.03f, z, 0.1f * sz, mix(color(kind, s, 1f, i / (float) BARS, time), 0xFFFFFF, 0.5f), 0.95f);
			}
		}

		/**
		 * A dragon's spine at {@code u} (its head is at u = time; the body trails behind). Yin flies half a turn
		 * behind yang, rising where yang dips, so the two chase each other round you.
		 */
		private static float[] dragonSpine(float u, float sz, boolean yin) {
			float a = u * 0.95f + (yin ? (float) Math.PI : 0);
			float r = (0.95f + 0.13f * sin(u * 2.1f + (yin ? 1.5f : 0))) * sz;
			float y = 1.05f + (yin ? -0.45f : 0.45f) * sin(u * 0.83f) + 0.12f * sin(u * 2.9f + (yin ? 2 : 0));
			return new float[] {r * cos(a), y, r * sin(a)};
		}

		/** The light a dragon gives off: yang warm white (or the chosen colour, lightened), yin the second colour. */
		private int dragonGlow(Settings s, boolean yin, float f) {
			if (s.color() == RAINBOW) {
				return color(kind, s, f, yin ? 0.5f : 0f, time + (yin ? 6 : 0));
			}
			int c = color(kind, s, yin ? 1f : 0f, 0, time);
			return yin ? mix(c, 0x000000, 0.15f * f) : mix(c, 0xFFE7B0, 0.25f * f);
		}

		/** A dragon's body: yang glows white-hot through, yin is dark inside its light. */
		private int dragonCore(Settings s, boolean yin, float f) {
			return yin ? mix(dragonGlow(s, true, f), 0x07060C, 0.88f) : mix(dragonGlow(s, false, f), 0xFFFFFF, 0.55f);
		}

		private void drawDragon(Settings s, Out out) {
			float sz = s.size();
			// The yin-yang turning on the ground: the light half in yang's colour, the dark half filled in with yin's.
			float turn = time * 0.6f;
			if (!Float.isNaN(ground)) {
				out.sprite(0, ground + 0.035f, 0, 0.95f * sz, dragonGlow(s, false, 0), 0.6f, TAIJI | FLAT, turn);
				out.sprite(0, ground + 0.04f, 0, 0.95f * sz, dragonCore(s, true, 0), 0.75f, TAIJI | FLAT, turn + (float) Math.PI);
				out.sprite(0, ground + 0.03f, 0, 1.4f * sz, dragonGlow(s, true, 0.5f), 0.14f, DISC);
			}
			drawDragon(s, out, false);
			drawDragon(s, out, true);
		}

		private void drawDragon(Settings s, Out out, boolean yin) {
			float sz = s.size();
			int segments = 42;
			float step = 0.085f;
			float[][] spine = new float[segments + 1][];
			for (int k = 0; k <= segments; k++) {
				spine[k] = dragonSpine(time - k * step, sz, yin);
			}
			// The body: a tube thickest behind the head, tapering to the tail, scales shimmering along it.
			for (int k = 0; k < segments; k++) {
				float f = k / (float) segments;
				float thick = sz * 0.105f * (k < 4 ? 0.75f + k * 0.07f : 1 - (k - 4) / (float) (segments - 4) * 0.85f);
				float[] a = spine[k];
				float[] b = spine[k + 1];
				int c = dragonGlow(s, yin, f);
				float wave = 0.5f + 0.5f * sin(time * 6 - k * 0.6f);
				out.beam(a[0], a[1], a[2], b[0], b[1], b[2], thick * 3, c, yin ? 0.45f : 0.38f);
				out.beam(a[0], a[1], a[2], b[0], b[1], b[2], thick * 1.1f, dragonCore(s, yin, f), yin ? 0.95f : 0.9f);
				out.sprite(a[0], a[1], a[2], thick * 1.6f, c, (yin ? 0.2f : 0.3f) + 0.3f * wave, GLOW);
				if (k % 2 == 0 && k > 2) {
					// Dorsal spines.
					out.sprite(a[0], a[1] + thick * 1.4f, a[2], thick * 0.9f, mix(c, 0xFFFFFF, 0.3f), 0.5f + 0.4f * wave, STAR);
				}
			}
			int c0 = dragonGlow(s, yin, 0);
			// The tail fin.
			float[] tail = spine[segments];
			float[] before = spine[segments - 1];
			float tx = tail[0] - before[0];
			float tz = tail[2] - before[2];
			float tl = Math.max(1e-5f, (float) Math.sqrt(tx * tx + tz * tz));
			for (int k = -1; k <= 1; k++) {
				float ex = tail[0] + tx / tl * 0.16f * sz;
				float ey = tail[1] + k * 0.08f * sz;
				float ez = tail[2] + tz / tl * 0.16f * sz;
				out.beam(tail[0], tail[1], tail[2], ex, ey, ez, 0.025f * sz, dragonGlow(s, yin, 1f), 0.6f);
			}
			// The head: snout, glowing eyes, horns swept back and whiskers trailing.
			float[] head = spine[0];
			float[] neck = spine[1];
			float hx = head[0] - neck[0];
			float hy = head[1] - neck[1];
			float hz = head[2] - neck[2];
			float hl = Math.max(1e-5f, (float) Math.sqrt(hx * hx + hy * hy + hz * hz));
			hx /= hl;
			hy /= hl;
			hz /= hl;
			float sl = Math.max(1e-5f, (float) Math.sqrt(hx * hx + hz * hz));
			float sx = hz / sl;
			float sz2 = -hx / sl;
			out.sprite(head[0], head[1], head[2], 0.2f * sz, c0, 0.6f, GLOW);
			out.beam(head[0], head[1], head[2], head[0] + hx * 0.15f * sz, head[1] + hy * 0.15f * sz, head[2] + hz * 0.15f * sz, 0.1f * sz,
					dragonCore(s, yin, 0), 0.92f);
			for (int side = -1; side <= 1; side += 2) {
				float ex = head[0] + hx * 0.05f * sz + sx * side * 0.05f * sz;
				float ey = head[1] + 0.04f * sz;
				float ez = head[2] + hz * 0.05f * sz + sz2 * side * 0.05f * sz;
				// Yang's eyes burn dark gold, yin's glow white.
				out.sprite(ex, ey, ez, 0.05f * sz, yin ? 0xFFFFFF : 0xFFB02E, 1f, SPARKLE);
				// A horn.
				out.beam(head[0] - hx * 0.02f * sz + sx * side * 0.03f * sz, head[1] + 0.05f * sz, head[2] - hz * 0.02f * sz + sz2 * side * 0.03f * sz,
						head[0] - hx * 0.15f * sz + sx * side * 0.065f * sz, head[1] + 0.17f * sz, head[2] - hz * 0.15f * sz + sz2 * side * 0.065f * sz,
						0.022f * sz, c0, 0.85f);
				// A whisker, waving.
				float[] prev = {head[0] + hx * 0.14f * sz + sx * side * 0.03f * sz, head[1], head[2] + hz * 0.14f * sz + sz2 * side * 0.03f * sz};
				for (int j = 1; j <= 6; j++) {
					float[] q = {prev[0] - hx * 0.04f * sz + sx * side * 0.014f * sz, prev[1] + sin(time * 5 + j * 0.9f + (yin ? 2 : 0)) * 0.012f * j * sz,
							prev[2] - hz * 0.04f * sz + sz2 * side * 0.014f * sz};
					out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.012f * sz, c0, 0.8f * (1 - j / 7f));
					prev = q;
				}
			}
		}

		private static int ribbonCount(Settings s) {
			return Math.max(2, Math.min(5, Math.round(3 + (s.density() - 1) * 2)));
		}

		/** A point on ribbon {@code i} of {@code n}, {@code f} along it (0 bottom, 1 top). */
		private float[] ribbonPoint(int i, int n, float f, Settings s) {
			float sz = s.size();
			float a = i * TAU / n + time * 1.0f + f * 5.5f;
			float r = (0.62f + 0.16f * sin(f * 6.5f - time * 2.4f + i)) * sz;
			float y = 0.12f + f * 1.95f + 0.1f * sin(f * 4 - time * 1.8f + i * 2);
			return new float[] {r * cos(a), y, r * sin(a)};
		}

		private void drawRibbons(Settings s, Out out) {
			float sz = s.size();
			int n = ribbonCount(s);
			int segments = 40;
			for (int i = 0; i < n; i++) {
				float[] prevA = null;
				float[] prevB = null;
				for (int k = 0; k <= segments; k++) {
					float f = k / (float) segments;
					float[] p = ribbonPoint(i, n, f, s);
					// The band's width turns between upright and outward as it twists.
					float twist = f * 6 - time * 2 + i;
					float rl = Math.max(1e-5f, (float) Math.sqrt(p[0] * p[0] + p[2] * p[2]));
					float half = 0.075f * sz * (float) Math.pow(Math.sin(Math.PI * f), 0.6);
					float wx = p[0] / rl * sin(twist) * half;
					float wy = cos(twist) * half;
					float wz = p[2] / rl * sin(twist) * half;
					float[] a = {p[0] - wx, p[1] - wy, p[2] - wz};
					float[] b = {p[0] + wx, p[1] + wy, p[2] + wz};
					if (prevA != null) {
						float shine = 0.75f + 0.25f * sin(f * 14 - time * 5);
						out.quad(new float[] {prevA[0], prevA[1], prevA[2], a[0], a[1], a[2], b[0], b[1], b[2], prevB[0], prevB[1], prevB[2]}, CELL_RIBBON,
								color(kind, s, f, i / (float) n, time), 0.85f * shine);
					}
					if (k % 4 == 2) {
						out.sprite(p[0], p[1], p[2], 0.12f * sz, color(kind, s, f, i / (float) n, time), 0.12f, GLOW);
					}
					prevA = a;
					prevB = b;
				}
			}
		}

		// ==== Rain Cloud, Koi Pond, Spirit Blades, Sky Lanterns, Timekeeper ====

		private static final int ROLE_RAIN = 1007;
		private static final int ROLE_SPLASH = 1008;
		private static final int ROLE_DROPLET = 1009;
		private static final float STRIKE = 1.4f;
		private int lastStrike = -1;

		private void emitRain(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int k = count(t, 38 * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = (float) Math.sqrt(random.nextFloat()) * 0.5f * sz;
				float vy = rnd(4.2f, 4.8f);
				P p = spawn(px + Math.cos(a) * r, py + 2.3, pz + Math.sin(a) * r, 2.28f / vy, 0.14f, ROLE_RAIN);
				p.vy = -vy;
			}
		}

		/** A raindrop that reached the ground: a little ring and two droplets bouncing off. */
		private void splash(P drop, Settings s) {
			spawn(drop.x, drop.y + 0.02, drop.z, 0.35f, 0.12f * s.size(), ROLE_SPLASH);
			if (random.nextFloat() < 0.6f * Math.min(1, s.density())) {
				for (int k = 0; k < 2; k++) {
					P p = spawn(drop.x, drop.y + 0.03, drop.z, rnd(0.25f, 0.35f), 0.02f * s.size(), ROLE_DROPLET);
					p.vx = rnd(-0.45f, 0.45f);
					p.vy = rnd(0.6f, 1f);
					p.vz = rnd(-0.45f, 0.45f);
				}
			}
		}

		private void drawRainCloud(Settings s, Out out) {
			float sz = s.size();
			float ph = time % 3.1f;
			long strike = (long) (time / 3.1f);
			float flash = ph < 0.22f ? 1 - ph / 0.22f : 0;
			out.sprite(0, 0.03f, 0, 1.0f * sz, color(kind, s, 0.6f, 0, time), 0.12f + 0.3f * flash, DISC);
			// The cloud: soft puffs churning slowly, darker underneath, lit up by the lightning.
			for (int i = 0; i < 16; i++) {
				float x = (hash(i, 11) - 0.5f) * 1.25f * sz + 0.04f * sin(time * 0.7f + i);
				float z = (hash(i, 12) - 0.5f) * 0.95f * sz + 0.04f * cos(time * 0.6f + i * 2);
				float y = 2.45f + (hash(i, 13) - 0.5f) * 0.2f + 0.03f * sin(time * 0.8f + i);
				float under = Math.max(0, (2.45f - y) / 0.1f);
				int c = mix(mix(color(kind, s, 0.1f, i / 16f, time), 0x232B40, 0.5f + 0.3f * under), 0xFFFFFF, flash * 0.6f);
				out.sprite(x, y, z, (0.32f + 0.16f * hash(i, 14)) * sz, c, 0.75f, SMOKE, i + time * 0.1f * (i % 2 == 0 ? 1 : -1));
			}
			if (flash > 0) {
				// A bolt from the cloud to the ground beside you, flickering.
				Random jitter = new Random(strike * 31 + (long) (ph * 40));
				Random where = new Random(strike * 977);
				float a = where.nextFloat() * TAU;
				float gr = (0.6f + where.nextFloat() * 0.3f) * sz;
				float x0 = (where.nextFloat() - 0.5f) * 0.5f * sz;
				float z0 = (where.nextFloat() - 0.5f) * 0.4f * sz;
				float x1 = gr * cos(a);
				float z1 = gr * sin(a);
				float[] prev = {x0, 2.35f, z0};
				int c = color(kind, s, 0.7f, 0, time);
				for (int k = 1; k <= 8; k++) {
					float f = k / 8f;
					float jx = k == 8 ? 0 : (jitter.nextFloat() - 0.5f) * 0.25f * sz;
					float jz = k == 8 ? 0 : (jitter.nextFloat() - 0.5f) * 0.25f * sz;
					float[] q = {x0 + (x1 - x0) * f + jx, 2.35f * (1 - f), z0 + (z1 - z0) * f + jz};
					out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.18f * sz, c, 0.5f * flash);
					out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.05f * sz, 0xFFFFFF, flash);
					prev = q;
				}
				out.sprite(x1, 0.04f, z1, 0.5f * sz, c, 0.6f * flash, DISC);
			}
		}

		private static int koiCount(Settings s) {
			return Math.max(3, Math.min(6, Math.round(4 * (0.5f + 0.5f * s.density()))));
		}

		private void drawKoi(Settings s, Out out) {
			float sz = s.size();
			int water = s.color() == 0 ? 0x2BB8C9 : mix(color(kind, s, 1f, 0, time), 0x000000, 0.25f);
			out.sprite(0, 0.03f, 0, 1.35f * sz, water, 0.32f, DISC);
			out.sprite(0, 0.035f, 0, 0.9f * sz, mix(water, 0xFFFFFF, 0.3f), 0.15f, DISC);
			out.sprite(0, 0.04f, 0, 1.15f * sz / 0.74f, mix(water, 0xFFFFFF, 0.4f), 0.3f, HALO | FLAT);
			// Ripples spreading from random spots.
			for (int k = 0; k < 4; k++) {
				float per = 2.4f;
				float tt = time + k * 0.6f;
				float ph = (tt % per) / per;
				int at = (int) (tt / per) * 7 + k;
				float a = hash(at, 15) * TAU;
				float r = (0.3f + 0.7f * hash(at, 16)) * sz;
				float rr = 0.03f + ph * 0.3f * sz;
				out.sprite(r * cos(a), 0.045f, r * sin(a), rr / 0.74f, mix(water, 0xFFFFFF, 0.6f), (1 - ph) * 0.6f, HALO | FLAT);
			}
			// Lily pads drifting round the edge, some flowering.
			for (int i = 0; i < 5; i++) {
				float a = i * TAU / 5 + 0.3f + time * 0.05f;
				float r = (0.95f + 0.1f * hash(i, 17)) * sz;
				float x = r * cos(a);
				float z = r * sin(a);
				out.sprite(x, 0.05f, z, 0.15f * sz, s.color() == 0 ? 0x56C26A : color(kind, s, 1f, i / 5f, time), 0.85f, LILY | FLAT,
						hash(i, 18) * TAU + 0.15f * sin(time * 0.4f + i));
				if (i % 2 == 0) {
					int petal = s.color() == 0 ? 0xFF9ECF : color(kind, s, 0f, i / 5f, time);
					for (int k = 0; k < 6; k++) {
						float pa = k * TAU / 6 + i;
						out.sprite(x + 0.03f * sz * cos(pa), 0.07f, z + 0.03f * sz * sin(pa), 0.035f * sz, petal, 0.9f, PETAL | FLAT, -pa - (float) Math.PI / 2);
					}
					out.sprite(x, 0.08f, z, 0.03f * sz, 0xFFF4A0, 0.9f, SPARKLE | FLAT);
				}
			}
			// The koi, circling and wiggling.
			int n = koiCount(s);
			for (int i = 0; i < n; i++) {
				float dir = i % 2 == 0 ? 1 : -1;
				float a = time * (0.6f + 0.15f * i) * dir + i * TAU / n;
				float r = (0.55f + 0.25f * hash(i, 9) + 0.08f * sin(time * 0.7f + i)) * sz;
				float x = r * cos(a);
				float z = r * sin(a);
				float heading = a + dir * (float) Math.PI / 2 + 0.18f * sin(time * 7 + i);
				int c = color(kind, s, i % 3 == 1 ? 1f : 0f, i / (float) n, time);
				out.sprite(x, 0.05f, z, 0.2f * sz, c, 0.2f, DISC);
				out.sprite(x, 0.07f, z, 0.21f * sz * (0.85f + 0.3f * hash(i, 10)), c, 0.95f, KOI | FLAT, -heading - (float) Math.PI / 2);
			}
		}

		private static int bladeCount(Settings s) {
			return Math.max(5, Math.min(12, Math.round(8 * (0.5f + 0.5f * s.density()))));
		}

		private static float ease(float x) {
			x = Math.max(0, Math.min(1, x));
			return x * x * (3 - 2 * x);
		}

		/** Blade {@code i} at time {@code tt}: hovering tip-down in a ring, now and then one dives into the ground. */
		private static float[] blade(int i, int n, float tt, Settings s) {
			int strike = (int) (tt / STRIKE);
			float ph = (tt % STRIKE) / STRIKE;
			float dive = 0;
			if (Math.floorMod(strike, n) == i) {
				dive = ph < 0.35f ? ease(ph / 0.35f) * ease(ph / 0.35f) : ph < 0.55f ? 1 : 1 - ease((ph - 0.55f) / 0.45f);
			}
			float a = i * TAU / n + tt * 0.5f;
			float r = 0.85f * s.size() * (1 + 0.12f * dive);
			float y = 1.25f + 0.06f * sin(tt * 1.6f + i) - dive * 0.92f;
			return new float[] {r * cos(a), y, r * sin(a)};
		}

		private void updateBlades(float t, Settings s, double px, double py, double pz) {
			int strike = (int) (time / STRIKE);
			float ph = (time % STRIKE) / STRIKE;
			if (ph >= 0.35f && strike != lastStrike) {
				lastStrike = strike;
				int n = bladeCount(s);
				float[] at = blade(Math.floorMod(strike, n), n, time, s);
				spawn(px + at[0], py + 0.04, pz + at[2], 0.55f, 0.1f * s.size(), ROLE_RING);
				for (int k = 0; k < Math.round(10 * s.density()); k++) {
					P p = spawn(px + at[0], py + 0.06, pz + at[2], rnd(0.4f, 0.7f), rnd(0.04f, 0.06f) * s.size(), SPARKLE);
					float a = rnd(0, TAU);
					p.vx = cos(a) * rnd(0.6f, 1.4f);
					p.vy = rnd(1f, 2.2f);
					p.vz = sin(a) * rnd(0.6f, 1.4f);
				}
			}
		}

		private void drawBlades(Settings s, Out out) {
			float sz = s.size();
			int n = bladeCount(s);
			float ph = (time % STRIKE) / STRIKE;
			float impact = ph >= 0.35f ? (float) Math.exp(-(ph - 0.35f) * 9) : 0;
			// The circle on the ground: an eight-pointed star in two turning squares, flaring on each strike.
			float gr = 0.95f * sz;
			int gc = color(kind, s, 0.6f, 0, time);
			for (int sq = 0; sq < 2; sq++) {
				float base = time * 0.2f * (sq == 0 ? 1 : -1) + sq * (float) Math.PI / 4;
				for (int k = 0; k < 4; k++) {
					float a1 = base + k * (float) Math.PI / 2;
					float a2 = a1 + (float) Math.PI / 2;
					out.beam(gr * cos(a1), 0.04f, gr * sin(a1), gr * cos(a2), 0.04f, gr * sin(a2), 0.025f * sz, gc, 0.35f + 0.5f * impact);
				}
			}
			out.sprite(0, 0.045f, 0, gr / 0.74f, gc, 0.3f + 0.4f * impact, HALO | FLAT);
			out.sprite(0, 0.045f, 0, 0.6f * sz / 0.74f, gc, 0.2f + 0.3f * impact, HALO | FLAT);
			out.sprite(0, 0.03f, 0, 1.3f * sz, color(kind, s, 1f, 0, time), 0.1f + 0.25f * impact, DISC);
			for (int i = 0; i < n; i++) {
				int c = color(kind, s, hash(i, 19) * 0.6f, i / (float) n, time);
				float sway = 0.08f * sin(time * 1.3f + i * 1.7f);
				// After-images first, fading behind it.
				for (int k = 3; k >= 1; k--) {
					float[] g = blade(i, n, time - k * 0.035f, s);
					out.sprite(g[0], g[1], g[2], 0.33f * sz, c, 0.22f * (1 - k / 4f), BLADE | UPRIGHT, (float) Math.PI + sway);
				}
				float[] p = blade(i, n, time, s);
				out.sprite(p[0], p[1], p[2], 0.3f * sz, c, 0.18f, GLOW);
				out.sprite(p[0], p[1], p[2], 0.33f * sz, mix(c, 0xFFFFFF, 0.25f), 0.95f, BLADE | UPRIGHT, (float) Math.PI + sway);
			}
		}

		private void emitLanterns(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			for (int k = count(t, 2.2f * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.45f, 1.1f) * sz;
				P p = spawn(px + Math.cos(a) * r, py + rnd(0.2f, 1.2f), pz + Math.sin(a) * r, rnd(6f, 8f), rnd(0.12f, 0.16f) * sz, LANTERN);
				p.vy = rnd(0.3f, 0.45f);
				p.spin = rnd(0.8f, 1.4f);
			}
			for (int k = count(t, 5 * s.density(), 1); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = 0.7f * sz;
				P p = spawn(px + Math.cos(a) * r, py + 0.1, pz + Math.sin(a) * r, rnd(1f, 1.6f), rnd(0.025f, 0.04f) * sz, GLOW);
				p.vy = rnd(0.3f, 0.6f);
			}
		}

		private void emitPyjama(float t, Settings s, double px, double py, double pz) {
			float sz = s.size();
			// Zzz drifting up beside your head (kept low enough that previews never cut them off).
			for (int k = count(t, 1.1f * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				P p = spawn(px + Math.cos(a) * 0.3 * sz, py + 1.7, pz + Math.sin(a) * 0.3 * sz, rnd(2f, 2.5f), rnd(0.06f, 0.085f) * sz, SNOOZE);
				p.vy = rnd(0.16f, 0.22f);
				p.spin = rnd(1.6f, 2.2f);
			}
			// Stars winking on and off around you.
			for (int k = count(t, 4 * s.density(), 1); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.6f, 1.2f) * sz;
				P p = spawn(px + Math.cos(a) * r, py + rnd(0.3f, 2.1f), pz + Math.sin(a) * r, rnd(1.2f, 2f), rnd(0.05f, 0.08f) * sz, STAR);
				p.vy = rnd(-0.03f, 0.03f);
			}
			// Little clouds at your feet.
			for (int k = count(t, 1.5f * s.density(), 2); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.5f, 0.9f) * sz;
				spawn(px + Math.cos(a) * r, py + rnd(0.05f, 0.25f), pz + Math.sin(a) * r, rnd(3f, 4f), rnd(0.25f, 0.35f) * sz, SMOKE);
			}
		}

		private void drawPyjama(Settings s, Out out) {
			float sz = s.size();
			// Night falling at your feet.
			out.sprite(0, 0.03f, 0, 1.2f * sz, color(kind, s, 1f, 0, time), 0.2f, DISC);
			// The moon, rocking gently right over your head, with its glow. It faces the viewer: crossed quads showed
			// it mirrored or edge-on from most sides, and an offset to one side sat in front of your face facing east.
			float rock = 0.18f * sin(time * 0.8f);
			float bob = 0.035f * sin(time * 1.1f);
			int moon = color(kind, s, 0f, 0, time);
			// Sizes are half-widths in the world: at 2.12 the moon reached down to 1.88 and its glow to eye level, so it
			// sat in your head. Now its lowest point clears the top of the head (and a hat).
			float moonY = 2.0f + 0.32f * sz + bob;
			out.sprite(0, moonY, 0, 0.4f * sz, moon, 0.26f, GLOW);
			out.sprite(0, moonY, 0, 0.24f * sz, mix(moon, 0xFFFFFF, 0.35f), 0.98f, MOON | FACING, rock);
			// A ring of stars circling slowly round your head, each twinkling.
			for (int i = 0; i < 10; i++) {
				float a = i * TAU / 10 + time * 0.25f;
				float r = 0.75f * sz;
				float tw = 0.55f + 0.45f * sin(time * (2.3f + i % 3) + i * 1.7f);
				int c = color(kind, s, i % 2 == 0 ? 0f : 1f, i / 10f, time);
				out.sprite(r * cos(a), 1.9f + 0.08f * sin(time + i), r * sin(a), 0.07f * sz * (0.7f + 0.5f * tw), mix(c, 0xFFFFFF, 0.3f), tw, STAR);
			}
		}

		private void drawLanterns(Settings s, Out out) {
			float sz = s.size();
			out.sprite(0, 0.03f, 0, 1.2f * sz, color(kind, s, 0.5f, 0, time), 0.2f, DISC);
			// Tea lights round your feet.
			for (int i = 0; i < 12; i++) {
				float a = i * TAU / 12 + 0.2f;
				float x = 0.7f * sz * cos(a);
				float z = 0.7f * sz * sin(a);
				float flicker = 0.85f + 0.15f * sin(time * (11 + i % 4) + i * 3);
				out.sprite(x, 0.06f, z, 0.13f * sz, color(kind, s, 0.6f, i / 12f, time), 0.3f * flicker, GLOW);
				out.sprite(x, 0.07f, z, 0.045f * sz * flicker, mix(color(kind, s, 0f, i / 12f, time), 0xFFFFFF, 0.3f), 0.95f, FLAME | UPRIGHT);
			}
		}

		/** A point on gyroscope ring {@code k} at angle {@code th} (player space). */
		private float[] gyro(int k, float th, Settings s) {
			float r = 1.08f * s.size();
			float tilt = k == 0 ? 1.15f : -1.15f;
			float spin = time * (0.6f + 0.3f * k) * (k % 2 == 0 ? 1 : -1) + k * 1.3f;
			float x = r * cos(th);
			float z = r * sin(th);
			float y = -z * sin(tilt);
			z = z * cos(tilt);
			return new float[] {x * cos(spin) - z * sin(spin), 1.05f + y, x * sin(spin) + z * cos(spin)};
		}

		private void drawChrono(Settings s, Out out) {
			float sz = s.size();
			// The dial at your feet with its hands sweeping round.
			out.sprite(0, 0.03f, 0, 1.3f * sz, color(kind, s, 1f, 0, time), 0.15f, DISC);
			out.sprite(0, 0.04f, 0, 1.05f * sz, color(kind, s, 0f, 0, time), 0.85f, DIAL | FLAT, -time * 0.05f);
			float am = time * 0.9f;
			float ah = time * 0.075f;
			int c1 = color(kind, s, 1f, 0, time);
			out.beam(0, 0.05f, 0, 0.82f * sz * cos(am), 0.05f, 0.82f * sz * sin(am), 0.03f * sz, c1, 0.9f);
			out.sprite(0.82f * sz * cos(am), 0.06f, 0.82f * sz * sin(am), 0.06f * sz, mix(c1, 0xFFFFFF, 0.5f), 1f, SPARKLE);
			out.beam(0, 0.05f, 0, 0.55f * sz * cos(ah), 0.05f, 0.55f * sz * sin(ah), 0.045f * sz, color(kind, s, 0f, 0, time), 0.9f);
			// Two gyroscope rings turning round you, marks running along them.
			for (int k = 0; k < 2; k++) {
				int c = color(kind, s, k, k * 0.5f, time);
				float[] prev = gyro(k, 0, s);
				for (int j = 1; j <= 40; j++) {
					float[] q = gyro(k, j * TAU / 40, s);
					out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.02f * sz, c, 0.7f);
					prev = q;
				}
				for (int j = 0; j < 12; j++) {
					float[] q = gyro(k, j * TAU / 12 + time * 1.2f * (k == 0 ? 1 : -1), s);
					out.sprite(q[0], q[1], q[2], (j % 3 == 0 ? 0.06f : 0.035f) * sz, mix(c, 0xFFFFFF, 0.4f), 0.9f, SPARKLE);
				}
			}
			// Gears turning above your shoulders.
			for (int g = 0; g < 3; g++) {
				float a = g * TAU / 3 + time * 0.15f;
				float size = (g == 0 ? 0.16f : g == 1 ? 0.12f : 0.2f) * sz;
				float x = 0.7f * sz * cos(a);
				float z = 0.7f * sz * sin(a);
				float y = 1.55f + 0.12f * g + 0.04f * sin(time + g);
				out.sprite(x, y, z, size * 1.6f, color(kind, s, 0.5f, g / 3f, time), 0.12f, GLOW);
				out.sprite(x, y, z, size, color(kind, s, 0.3f * g, g / 3f, time), 0.85f, GEAR | UPRIGHT, time * 0.18f / size * (g % 2 == 0 ? 1 : -1));
			}
		}

		private void emitChrono(float t, Settings s, double px, double py, double pz) {
			for (int k = count(t, 10 * s.density(), 0); k > 0; k--) {
				float a = rnd(0, TAU);
				float r = rnd(0.3f, 1.0f) * s.size();
				P p = spawn(px + Math.cos(a) * r, py + 0.1, pz + Math.sin(a) * r, rnd(1.6f, 2.4f), rnd(0.025f, 0.045f) * s.size(), random.nextBoolean() ? SPARKLE : GLOW);
				p.vy = rnd(0.3f, 0.6f);
			}
		}
	}
}
