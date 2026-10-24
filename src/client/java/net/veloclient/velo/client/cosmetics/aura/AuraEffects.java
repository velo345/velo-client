package net.veloclient.velo.client.cosmetics.aura;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The five auras' animations - plain Java (no Minecraft classes), so the launcher compiles the same
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
		FESTIVE
	}

	/** Sprite shapes: a soft glowing ball, a four-pointed star, a flat glow on the ground plane, a heart, a leaf. */
	public static final int GLOW = 0;
	public static final int STAR = 1;
	public static final int DISC = 2;
	public static final int HEART = 3;
	public static final int LEAF = 4;

	/** Atlas cells (8 x 4 grid, see tools/cosmetics/make_aura_atlas.py) for {@link Out#panel}. */
	public static final int CELL_CURTAIN = 5;
	public static final int CELL_GLYPH = 8;
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
		};
	}

	/** The aura's middle colour (RGB) for Velo Shaders' aura light, or -1 for an unknown id. */
	public static int glowColor(String auraId, Settings s, float time) {
		Kind k = auraId == null ? null : kind(auraId);
		return k == null ? -1 : color(k, s == null ? Settings.DEFAULT : s, 0.5f, 0.3f, time);
	}

	/** Colour at {@code f} (0..1) between the aura's two colours, or the chosen colour / rainbow. */
	private static int color(Kind kind, Settings s, float f, float hueSeed, float time) {
		if (s.color() == RAINBOW) {
			return hsv((hueSeed * 0.37f + time * 0.08f) % 1f, 0.75f, 1f);
		}
		if (s.color() > 0) {
			int base = PALETTE[s.color() - 1];
			return mix(base, mix(base, 0xFFFFFF, 0.45f), 1 - f);
		}
		int[] c = defaults(kind);
		return mix(c[0], c[1], f);
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int hsv(float h, float s, float v) {
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

	private static final int MAX_PARTICLES = 260;

	/** One player's aura: its time, particles and arcs. Not thread-safe - one thread renders it. */
	public static final class Emitter {
		private final Kind kind;
		private final List<P> particles = new ArrayList<>();
		private final List<Arc> arcs = new ArrayList<>();
		private final Random random = new Random();
		private float time;
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
			}
			for (int i = particles.size() - 1; i >= 0; i--) {
				P p = particles.get(i);
				p.age += t;
				if (p.age >= p.life) {
					particles.remove(i);
					continue;
				}
				move(p, t, s, px, py, pz);
			}
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
				p.y = py + rnd(1.4f, 2.4f);
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
			int n = count(t, 16 * s.density());
			for (int k = 0; k < n; k++) {
				float a = rnd(0, (float) (Math.PI * 2));
				float r = rnd(0.35f, 0.95f) * s.size();
				P p = new P();
				p.x = px + Math.cos(a) * r;
				p.y = py + rnd(0f, 0.3f);
				p.z = pz + Math.sin(a) * r;
				p.vy = rnd(0.5f, 1.1f);
				p.life = rnd(1.2f, 2.0f);
				p.size = rnd(0.025f, 0.045f) * s.size();
				p.shape = random.nextInt(3) == 0 ? STAR : GLOW;
				add(p);
			}
		}

		// -- Code rain (kept per column, around the player) --

		private float[] rainAngle = new float[0];
		private float[] rainHead;
		private float[] rainSpeed;
		private int[] rainLength;
		private long[] rainSeed;

		private void updateRain(float t, Settings s) {
			int columns = Math.max(6, Math.round(20 * s.density()));
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
				if (rainHead[i] + rainLength[i] * 0.13f < -0.1f) {
					resetColumn(i);
				}
			}
		}

		private void resetColumn(int i) {
			rainHead[i] = rnd(2.4f, 3.0f);
			rainSpeed[i] = rnd(1.1f, 2.2f);
			rainLength[i] = 7 + random.nextInt(9);
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
			float r = 0.8f * sz;
			float glyph = 0.15f * sz;
			out.sprite(0, 0.03f, 0, 1.2f * sz, color(kind, s, 1f, 0, time), 0.18f, DISC);
			for (int i = 0; i < rainAngle.length; i++) {
				float a = rainAngle[i];
				float nx = (float) Math.cos(a);
				float nz = (float) Math.sin(a);
				// The glyphs change now and then (per column, from its seed and the time).
				long tick = (long) (time * 9);
				for (int k = 0; k < rainLength[i]; k++) {
					float y = rainHead[i] + k * 0.13f;
					if (y < 0 || y > 2.7f) {
						continue;
					}
					float fade = 1 - k / (float) rainLength[i];
					long h = rainSeed[i] * 31 + k * 977 + (k == 0 ? tick : tick / (3 + k % 4));
					int g = (int) Math.floorMod(h ^ (h >>> 17), (long) GLYPHS.length());
					int c = k == 0 ? (s.color() == 0 ? 0xE8FFEE : 0xFFFFFF) : color(kind, s, 0.4f + 0.6f * (1 - fade), i / (float) rainAngle.length, time);
					// Fade in at the top and out at the feet.
					float edge = Math.min(1, Math.min(y * 3, (2.7f - y) * 2.5f));
					float alpha = (k == 0 ? 1f : 0.95f * fade) * edge;
					// Twice at right angles, so no column is ever seen edge-on.
					out.panel(r * nx, y, r * nz, glyph, glyph, nx, nz, CELL_GLYPH + g, c, alpha);
					out.panel(r * nx, y, r * nz, glyph, glyph, -nz, nx, CELL_GLYPH + g, c, alpha * 0.8f);
				}
				// The column's faint light.
				float top = Math.min(2.7f, rainHead[i] + rainLength[i] * 0.13f);
				float bottom = Math.max(0, rainHead[i]);
				if (top > bottom) {
					out.beam(r * nx, bottom, r * nz, r * nx, top, r * nz, 0.05f * sz, color(kind, s, 1f, 0, time), 0.22f);
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
				float bottom = 1.25f + 0.12f * (float) Math.sin(time * 0.7f + i * 0.3f);
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
			drawHex(out, 0.9f * sz, 0.04f, time * 0.35f, color(kind, s, 0.1f, 0, time), 0.85f, 0.035f * sz);
			drawHex(out, 0.55f * sz, 0.04f, -time * 0.6f, color(kind, s, 0.85f, 0, time), 0.7f, 0.03f * sz);
			for (int i = 0; i < 6; i++) {
				float a = time * 0.35f + i * (float) (Math.PI / 3);
				float flick = 0.45f + 0.35f * (float) Math.sin(time * 4 + i * 1.7f);
				out.beam(0.9f * sz * (float) Math.cos(a), 0.04f, 0.9f * sz * (float) Math.sin(a), 0.9f * sz * (float) Math.cos(a), 0.55f + 0.2f * flick, 0.9f * sz * (float) Math.sin(a),
						0.02f * sz, color(kind, s, 0.3f, i / 6f, time), 0.35f * flick);
			}
			// Two scan rings sweeping up through the body.
			for (int k = 0; k < 2; k++) {
				float f = (time * 0.32f + k * 0.5f) % 1f;
				float y = 0.05f + f * 2.1f;
				float r = (0.6f + 0.08f * (float) Math.sin(time * 2 + k * 3)) * sz;
				float alpha = (float) Math.sin(Math.PI * f) * 0.9f;
				int segments = 28;
				for (int i = 0; i < segments; i++) {
					float a1 = i * (float) (Math.PI * 2 / segments);
					float a2 = (i + 1) * (float) (Math.PI * 2 / segments);
					out.beam(r * (float) Math.cos(a1), y, r * (float) Math.sin(a1), r * (float) Math.cos(a2), y, r * (float) Math.sin(a2), 0.028f * sz,
							color(kind, s, f, i / (float) segments, time), alpha);
				}
			}
			// Data glyphs orbiting at different heights, always facing outwards.
			for (int i = 0; i < 6; i++) {
				float a = time * 0.5f + i * (float) (Math.PI / 3);
				float r = 0.78f * sz;
				float y = 0.35f + (i * 0.37f) % 1.7f + 0.08f * (float) Math.sin(time * 1.5f + i);
				int g = (int) Math.floorMod((long) (time * 3) + i * 5L, (long) GLYPHS.length());
				float nx = (float) Math.cos(a);
				float nz = (float) Math.sin(a);
				out.panel(r * nx, y, r * nz, 0.17f * sz, 0.17f * sz, nx, nz, CELL_GLYPH + g, color(kind, s, 0.2f + 0.6f * ((i % 3) / 2f), i / 6f, time), 0.9f);
				out.panel(r * nx, y, r * nz, 0.17f * sz, 0.17f * sz, -nz, nx, CELL_GLYPH + g, color(kind, s, 0.2f, i / 6f, time), 0.7f);
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
	}
}
