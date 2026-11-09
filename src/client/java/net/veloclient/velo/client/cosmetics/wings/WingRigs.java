package net.veloclient.velo.client.cosmetics.wings;

import java.util.ArrayList;
import java.util.List;

/**
 * The moving parts some pairs have on top of their wingbeat, shared with the launcher (plain Java, copied into its
 * build like AuraEffects - keep it free of Minecraft and Velo imports):
 * <ul>
 * <li>Arms move one by one: {@link #arms} finds every arm in the (welded, one-piece) model - a merge tree over
 * the surface distance from the shoulder, each branch that sticks out far enough is an arm: a tentacle, a feather,
 * a blade, a claw - and gives it a chain of {@link Arms#SEGMENTS} bones along its centre line. {@link #pose} moves
 * each chain the pair's way ({@link Motion}): tentacles writhe, flames lick, feathers flutter, blades fan, shards
 * float, claws clench, wisps wave. Everything repeats every {@link Arms#LOOP} time units, so the launcher can bake
 * one loop.</li>
 * <li>Bat wings' membranes billow ({@link #billow}).</li>
 * <li>Parts of the texture glow ({@link Glow}): picked by colour, lit in the pair's pattern - fire, a pulse, a neon
 * chase, embers, frost glitter, a nebula swirl, fairy lights, circuit pulses.</li>
 * <li>Particles ({@link Particles}), only what comes off the wing itself: embers, flames, snow, falling feathers,
 * sparks, glitter on its surface -
 * stateless, worked out from the time alone, so game and launcher agree and nothing has to be kept per player.</li>
 * <li>Clockwork turns gears: real toothed gears ({@link #gearMesh}) put over the gear hubs painted on the model (a
 * remeshed Meshy model is one welded surface, its own gears can't be cut out and turned), meshing pairs turning
 * against each other.</li>
 * <li>Polished metal shines: highlights slide over the feathers as their normals turn with the wingbeat
 * ({@link #specular}), a white-hot glint sweeps across now and then ({@link #glint}, {@link #glintCore}), little
 * four-pointed stars flash on single feathers ({@link #star}) and the feather tips ripple a little ({@link #ripple}).</li>
 * </ul>
 */
public final class WingRigs {

	/**
	 * A gear on the +x half (mirrored on the other one), in the .vmesh's model space relative to the hinge (blocks
	 * before the pair's scale, y up, +z towards the wearer); {@code ratio}: turns per turn of the pair's drive gear
	 * (negative = the other way).
	 */
	public record Gear(float x, float y, float z, float radius, int teeth, float ratio) {
	}

	/** A pair's gears and where in its texture their faces, edges and hubs take their colour from. */
	public record Rig(Gear[] gears, float[] faceUv, float[] edgeUv, float[] hubUv) {
	}

	/** A gear's geometry in body space (x sideways, y down, z backwards), centred on the gear: {x, y, z, nx, ny, nz} per vertex. */
	public record GearMesh(float[] geometry, float[] uv, int[] triangles) {
	}

	/** Colour codes (wing settings): 0 the pair's own, 1-360 a hue (+1), then white and a cycling rainbow. */
	public static final int TINT_WHITE = 361;
	public static final int TINT_RAINBOW = 362;

	/** Whether pair {@code id} takes a colour (its art is white, tinted to the wearer's pick). */
	public static boolean recolorable(String id) {
		return "neon".equals(id) || "chainwings".equals(id);
	}

	/**
	 * The colour (RGB) a recolourable pair has when the wearer kept its own (code 0), for pairs whose art is grey and
	 * always tinted - Chain Wings are dark crimson. -1: its art already has its own colours (Neon: white).
	 */
	public static int ownTint(String id) {
		return "chainwings".equals(id) ? 0xFFFFFF : -1; // its art is already coloured: "Own" = as painted
	}

	/** {@link #tint(int, float)} for pair {@code id}: its own colour ({@link #ownTint}) for code 0. */
	public static int tint(String id, int code, float seconds) {
		int t = tint(code, seconds);
		return t < 0 ? ownTint(id) : t;
	}

	/** The RGB a colour code stands for at {@code seconds} (the rainbow cycles), or -1 for the pair's own. */
	public static int tint(int code, float seconds) {
		if (code <= 0) {
			return -1;
		}
		if (code == TINT_WHITE) {
			return 0xFFFFFF;
		}
		float hue = code == TINT_RAINBOW ? (seconds * 45) % 360 : code - 1;
		// A neon tube: strongly coloured, a touch of white in its core.
		float s = 0.85f;
		float h = hue / 60f;
		int i = (int) Math.floor(h) % 6;
		float f = h - (float) Math.floor(h);
		float p = 1 - s;
		float q = 1 - s * f;
		float u = 1 - s * (1 - f);
		float[] rgb = switch (i) {
			case 0 -> new float[] {1, u, p};
			case 1 -> new float[] {q, 1, p};
			case 2 -> new float[] {p, 1, u};
			case 3 -> new float[] {p, q, 1};
			case 4 -> new float[] {u, p, 1};
			default -> new float[] {1, p, q};
		};
		return Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
	}

	/** {@code glow} drawn in {@code rgb} instead of its first colour (a recoloured pair). */
	public static Glow tinted(Glow glow, int rgb) {
		return new Glow(glow.pattern(), glow.hue(), glow.hueWidth(), glow.minSat(), glow.maxSat(), glow.minVal(), glow.maxVal(), rgb,
				glow.colorB(), glow.strength());
	}

	/** Reads a pair's texture: its colour (ARGB) at (u, v). */
	@FunctionalInterface
	public interface Sampler {
		int argb(float u, float v);
	}

	/** A polished pair: the colour (RGB) of its glints. */
	public record Shine(int color) {
	}

	/** How a pair's arms move. */
	public enum Motion {
		/** Arms: a wave down each arm, each on its own rhythm. */
		WRITHE,
		/** Flames: quick small licks, stretching and shrinking. */
		FLICKER,
		/** Feathers: a light flutter, one by one. */
		FLUTTER,
		/** Blades and quills: the fan spreads open and back, rippling across, the blades never crossing. */
		FAN,
		/** Shards: drift away from the wing and back. */
		FLOAT,
		/** Claws: curl in and open, one after the other. */
		FLEX,
		/** Wisps: a slow flowing wave, mostly out of the wing's plane. */
		WAVE
	}

	/** How a glow is lit over time. */
	public enum Pattern {
		/** Flickering fire, yellow to red. */
		FLAME,
		/** A pulse running from the shoulder out. */
		PULSE,
		/** Neon: a bright head running out along the frame, a trail behind, now and then a buzz. */
		CHASE,
		/** A coloured sheen sweeping across, from one colour to the other. */
		SHEEN,
		/** Embers: each spot glowing up and dimming on its own. */
		EMBER,
		/** Nebula: bands of colour swirling around the shoulder. */
		SWIRL,
		/** Fairy lights: spots blinking between the two colours. */
		BLINK,
		/** Circuits: short pulses racing out from the shoulder. */
		CIRCUIT,
		/** Frost: bright glints flashing on single spots. */
		FROST,
		/** A slow, soft breathing glow. */
		HALO
	}

	/**
	 * A glow over the parts of the texture with this colour: {@code hue} 0..1 (negative: any) within {@code hueWidth},
	 * saturation and brightness within their bounds; drawn in {@code pattern} between {@code colorA} and {@code colorB}
	 * (RGB), at most {@code strength} opaque.
	 */
	public record Glow(Pattern pattern, float hue, float hueWidth, float minSat, float maxSat, float minVal, float maxVal,
			int colorA, int colorB, float strength) {
	}

	public enum Kind {
		/** Rising sparks of fire. */
		EMBER,
		/** Tongues of flame licking up off the tips. */
		FLAME,
		/** Flakes drifting down. */
		SNOW,
		/** Feathers falling and swaying. */
		FEATHER,
		/** Sparks bursting out. */
		SPARK,
		/** Glitter flashing in place. */
		GLITTER
	}

	/** Particles of one kind, tinted {@code color} (RGB), {@code count} per half at a time. */
	public record Particles(Kind kind, int color, int count) {
	}

	/** Where particles start: anywhere on the wing, on the glowing parts, or on the outer parts. */
	public enum Source { ANY, GLOW, TIPS }

	/**
	 * Everything a pair does on top of its wingbeat: how its arms move (null: they don't), how strongly its
	 * membranes billow, its glow (or null) and its particles (and where they start).
	 */
	public record Effect(Motion motion, float billow, Glow glow, Source source, Particles... particles) {
	}

	private static Glow glow(Pattern pattern, float hueDegrees, float hueWidthDegrees, float minSat, float minVal, int a, int b, float strength) {
		return new Glow(pattern, hueDegrees < 0 ? -1 : hueDegrees / 360f, hueWidthDegrees / 360f, minSat, 1f, minVal, 1f, a, b, strength);
	}

	private static Effect neon(int color) {
		return new Effect(Motion.FAN, 0, glow(Pattern.CHASE, -1, 0, 0, 0.15f, color, 0xFFFFFF, 0.95f), Source.TIPS,
				new Particles(Kind.SPARK, color, 6));
	}

	/** What pair {@code id} does, or null. Tentacle-style pairs writhe whatever this says. */
	public static Effect effect(String id) {
		return switch (id) {
			case "phoenix" -> new Effect(Motion.FLICKER, 0, glow(Pattern.FLAME, 28, 34, 0.5f, 0.45f, 0xFFD848, 0xFF3A0A, 0.85f), Source.GLOW,
					new Particles(Kind.EMBER, 0xFF9A30, 14), new Particles(Kind.FLAME, 0xFFB040, 8));
			case "angelic" -> new Effect(Motion.FLUTTER, 0, glow(Pattern.HALO, 47, 14, 0.35f, 0.5f, 0xFFE9A0, 0xFFFFFF, 0.9f), Source.ANY,
					new Particles(Kind.FEATHER, 0xFFFFFF, 5), new Particles(Kind.GLITTER, 0xFFE38A, 8));
			case "raven" -> new Effect(Motion.FLUTTER, 0, new Glow(Pattern.SHEEN, -1, 0, 0, 1, 0, 0.45f, 0x3A7BFF, 0x9A4BFF, 0.7f), Source.ANY,
					new Particles(Kind.FEATHER, 0x1A1A24, 4));
			case "shadowfang" -> new Effect(Motion.FLEX, 1.8f, glow(Pattern.PULSE, 280, 30, 0.35f, 0.35f, 0xC070FF, 0x8030FF, 0.9f), Source.GLOW);
			case "bonedragon" -> new Effect(Motion.FLEX, 0, glow(Pattern.PULSE, 105, 40, 0.3f, 0.35f, 0x9CFF6A, 0x3AFF40, 0.9f), Source.GLOW);
			case "cobweb" -> new Effect(Motion.FLUTTER, 1f, glow(Pattern.EMBER, 22, 20, 0.55f, 0.45f, 0xFFB040, 0xFF3A00, 0.9f), Source.GLOW,
					new Particles(Kind.EMBER, 0xFF7A20, 10));
			case "frost" -> new Effect(Motion.FLOAT, 0, glow(Pattern.FROST, 195, 30, 0.15f, 0.6f, 0xE0F8FF, 0x8FE4FF, 0.85f), Source.ANY,
					new Particles(Kind.SNOW, 0xFFFFFF, 14), new Particles(Kind.GLITTER, 0xC8F0FF, 6));
			case "wraith" -> new Effect(Motion.WAVE, 0, glow(Pattern.PULSE, 275, 40, 0.3f, 0.45f, 0xD49BFF, 0x9B4DFF, 0.6f), Source.TIPS);
			case "candycane" -> new Effect(Motion.FLUTTER, 0, glow(Pattern.BLINK, 48, 22, 0.5f, 0.6f, 0xFFE060, 0xFF3030, 0.95f), Source.ANY,
					new Particles(Kind.SNOW, 0xFFFFFF, 12));
			case "snowdrift" -> new Effect(Motion.FLUTTER, 0, new Glow(Pattern.FROST, -1, 0, 0, 0.25f, 0.82f, 1, 0xFFFFFF, 0xCFEFFF, 0.8f), Source.ANY,
					new Particles(Kind.SNOW, 0xFFFFFF, 18), new Particles(Kind.GLITTER, 0xE0F6FF, 6));
			case "neon", "neon_white" -> neon(0xFFFFFF);
			case "neon_cyan" -> neon(0x40F0FF);
			case "neon_pink" -> neon(0xFF5AC8);
			case "neon_purple" -> neon(0xB45AFF);
			case "neon_red" -> neon(0xFF4040);
			case "scifi_circuit" -> new Effect(Motion.FAN, 0, glow(Pattern.CIRCUIT, 188, 26, 0.35f, 0.4f, 0x5CF4FF, 0xFFFFFF, 0.95f), Source.GLOW,
					new Particles(Kind.SPARK, 0x5CF4FF, 5));
			case "ice_star" -> new Effect(Motion.FLOAT, 0, new Glow(Pattern.FROST, -1, 0, 0, 0.35f, 0.78f, 1, 0xE6F8FF, 0xA8E6FF, 0.8f), Source.ANY,
					new Particles(Kind.GLITTER, 0xD8F4FF, 12), new Particles(Kind.SNOW, 0xFFFFFF, 6));
			case "nebula" -> new Effect(Motion.FLOAT, 0, glow(Pattern.SWIRL, -1, 0, 0, 0, 0xA060FF, 0x4A8CFF, 0.55f), Source.ANY,
					new Particles(Kind.GLITTER, 0xE0D0FF, 10));
			case "katana" -> new Effect(Motion.FAN, 0, null, Source.TIPS, new Particles(Kind.SPARK, 0xFFFFFF, 3));
			default -> null;
		};
	}

	/** How much (0..1) a texture colour (ARGB) belongs to {@code glow}'s parts. */
	public static float mask(Glow glow, int argb) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float sat = max <= 0 ? 0 : (max - min) / max;
		float hue = 0;
		if (max > min) {
			if (max == r) {
				hue = ((g - b) / (max - min)) / 6f;
			} else if (max == g) {
				hue = (2 + (b - r) / (max - min)) / 6f;
			} else {
				hue = (4 + (r - g) / (max - min)) / 6f;
			}
			if (hue < 0) {
				hue += 1;
			}
		}
		float m = ramp(sat, glow.minSat(), 0.08f) * ramp(glow.maxSat(), sat, 0.08f) * ramp(max, glow.minVal(), 0.08f) * ramp(glow.maxVal(), max, 0.08f);
		if (glow.hue() >= 0) {
			float d = Math.abs(hue - glow.hue());
			d = Math.min(d, 1 - d);
			m *= ramp(glow.hueWidth(), d, 0.03f);
		}
		return m;
	}

	/** 0 below {@code edge}, 1 a {@code soft} above it, smooth in between. */
	private static float ramp(float value, float edge, float soft) {
		float x = Math.max(0f, Math.min(1f, (value - edge) / soft + 0.5f));
		return x * x * (3 - 2 * x);
	}

	/** A stable 0..1 per spot on the surface (the same for the split copies of a vertex). */
	public static float hash(float x, float y, float z) {
		double h = Math.sin(Math.round(x * 997) * 12.9898 + Math.round(y * 997) * 78.233 + Math.round(z * 997) * 37.719) * 43758.5453;
		return (float) (h - Math.floor(h));
	}

	/**
	 * What a vertex's glow is keyed on (0..1) in {@code pattern}: its spot ({@link #hash}), how far out it is
	 * ({@code reach}), where across the wing ({@link #band}) or where around the shoulder.
	 */
	public static float glowKey(Pattern pattern, float x, float y, float z, int side, float reach) {
		return switch (pattern) {
			case FLAME, EMBER, FROST, BLINK -> hash(x, y, z);
			case PULSE, CHASE, CIRCUIT, HALO -> reach;
			case SHEEN -> band(x, y, side);
			case SWIRL -> {
				double a = Math.atan2(y, side * x) / (Math.PI * 2) + reach * 0.8;
				yield (float) (a - Math.floor(a));
			}
		};
	}

	/** The glow (ARGB, alpha = how opaque) of a vertex with {@code key} and {@code mask} after {@code seconds}. */
	public static int glowColor(Glow g, float key, float mask, float seconds) {
		if (mask <= 0.01f) {
			return 0;
		}
		float a;
		float mix;
		switch (g.pattern()) {
			case FLAME -> {
				float f = (float) ((0.5 + 0.5 * Math.sin(seconds * 9 + key * 40)) * (0.55 + 0.45 * Math.sin(seconds * 5.3 + key * 23)));
				a = 0.3f + 0.7f * f;
				mix = 1 - f;
			}
			case PULSE -> {
				float f = (float) (0.5 + 0.5 * Math.sin(seconds * 3.2 - key * 7));
				a = 0.3f + 0.7f * f * f;
				mix = 1 - f;
			}
			case HALO -> {
				float f = (float) (0.5 + 0.5 * Math.sin(seconds * 1.6 - key * 1.5));
				a = 0.35f + 0.5f * f;
				mix = f;
			}
			case CHASE -> {
				float head = (seconds % 1.4f) / 1.4f * 1.3f - 0.1f;
				float d = key - head;
				float spot = (float) Math.exp(-(d / 0.05f) * (d / 0.05f));
				float trail = d < 0 ? (float) Math.exp(d / 0.18f) * 0.55f : 0;
				// Now and then the tube buzzes and dips.
				float buzz = Math.sin(seconds * 37.0) * Math.sin(seconds * 11.3) > 0.82 ? 0.35f : 1f;
				a = (0.3f + spot + trail) * buzz;
				mix = Math.min(1f, spot);
			}
			case SHEEN -> {
				float front = (seconds % 4.2f) / 4.2f * 1.6f - 0.3f;
				float d = (key - front) / 0.14f;
				a = (float) Math.exp(-d * d);
				mix = key;
			}
			case EMBER -> {
				float f = (float) (0.5 + 0.5 * Math.sin(seconds * 1.7 + key * 50) * Math.sin(seconds * 2.9 + key * 31));
				a = 0.2f + 0.8f * f * f;
				mix = 1 - f;
			}
			case SWIRL -> {
				float f = (float) (0.5 + 0.5 * Math.sin(key * Math.PI * 2 * 3 - seconds * 1.4));
				a = 0.25f + 0.55f * f;
				mix = f;
			}
			case BLINK -> {
				long step = (long) Math.floor(seconds * 1.8 + key * 7);
				boolean on = ((step + (long) (key * 13)) & 1) == 0;
				a = on ? 1f : 0.12f;
				mix = (step % 3) / 2f;
			}
			case CIRCUIT -> {
				double p = key * 3 - seconds * 0.9;
				p -= Math.floor(p);
				float spot = (float) Math.exp(-((p - 0.5) / 0.05) * ((p - 0.5) / 0.05));
				a = 0.25f + spot;
				mix = spot;
			}
			case FROST -> {
				double s = Math.sin(seconds * 2.1 + key * 60);
				float f = s > 0 ? (float) Math.pow(s, 24) : 0;
				a = 0.12f + 0.88f * f;
				mix = f;
			}
			default -> {
				a = 0;
				mix = 0;
			}
		}
		a = Math.min(1f, a) * mask * g.strength();
		if (a < 0.01f) {
			return 0;
		}
		int ca = g.colorA();
		int cb = g.colorB();
		mix = Math.max(0, Math.min(1, mix));
		int r = Math.round(((ca >> 16) & 0xFF) + (((cb >> 16) & 0xFF) - ((ca >> 16) & 0xFF)) * mix);
		int gr = Math.round(((ca >> 8) & 0xFF) + (((cb >> 8) & 0xFF) - ((ca >> 8) & 0xFF)) * mix);
		int b = Math.round((ca & 0xFF) + ((cb & 0xFF) - (ca & 0xFF)) * mix);
		return Math.round(a * 255) << 24 | r << 16 | gr << 8 | b;
	}

	/** How far (blocks, backwards) a membrane point with {@code weight} (0 shoulder, 1 tip) billows ({@code time} as {@link #pose}'s). */
	public static float billow(float billow, float amount, float weight, float time) {
		if (billow <= 0) {
			return 0;
		}
		return billow * amount * 0.09f * weight * (float) Math.sqrt(weight)
				* (float) Math.sin(Math.PI * 2 * (3 / Arms.LOOP) * time - weight * 5);
	}

	/** Aura atlas cells the particles are drawn with (see make_aura_atlas.py). */
	public static final int CELL_GLOW = 0;
	public static final int CELL_FEATHER = 24;
	public static final int CELL_FLAME = 27;
	public static final int CELL_SPARKLE = 28;

	/** The aura atlas cell a kind is drawn with. */
	public static int cell(Kind kind) {
		return switch (kind) {
			case EMBER, SNOW -> CELL_GLOW;
			case FLAME -> CELL_FLAME;
			case FEATHER -> CELL_FEATHER;
			case SPARK, GLITTER -> CELL_SPARKLE;
		};
	}

	/**
	 * Particle {@code index} of {@code p} on half {@code half} at {@code seconds}: {@code out} = {which of the
	 * {@code sources} it starts on, dx, dy, dz (blocks, body space, from that point), size (blocks), alpha 0..1,
	 * turn (rad)}. Alpha 0: not showing now.
	 */
	public static void particle(Particles p, int index, int half, float seconds, int sources, float[] out) {
		float life = switch (p.kind()) {
			case EMBER -> 1.6f;
			case FLAME -> 0.55f;
			case SNOW -> 2.6f;
			case FEATHER -> 3.2f;
			case SPARK -> 0.6f;
			case GLITTER -> 0.9f;
		};
		double t = seconds / life + (index + 0.5) / p.count() + half * 0.37;
		long slot = (long) Math.floor(t);
		float l = (float) (t - slot);
		double h1 = Math.sin(slot * 127.1 + index * 311.7 + half * 74.7 + p.kind().ordinal() * 19.3) * 43758.5453;
		h1 -= Math.floor(h1);
		double h2 = Math.sin(slot * 269.5 + index * 183.3 + half * 246.1) * 43758.5453;
		h2 -= Math.floor(h2);
		out[0] = (int) (h1 * sources);
		float wobble = (float) Math.sin(l * 9 + h2 * 20);
		float rise = (float) Math.sin(Math.PI * l);
		switch (p.kind()) {
			case EMBER -> set(out, wobble * 0.04f, -0.38f * l, 0.12f * l, 0.04f * (1 - 0.5f * l), rise, 0);
			case FLAME -> set(out, 0, -0.12f * l, 0.02f, 0.1f * (1 - l * 0.6f), rise, (float) (h2 - 0.5) * 0.6f);
			case SNOW -> set(out, 0.06f * (float) Math.sin(l * 6 + h2 * 9), 0.55f * l, 0.08f * l, 0.03f + 0.015f * (float) h2, rise, 0);
			case FEATHER -> set(out, 0.14f * (float) Math.sin(l * 5 + h2 * 9), 0.5f * l, 0.12f * l, 0.09f, rise, 0.8f * (float) Math.sin(l * 4 + h2 * 7));
			case SPARK -> {
				double a = h2 * Math.PI * 2;
				set(out, (float) Math.cos(a) * 0.28f * l, (float) Math.sin(a) * 0.28f * l, 0.1f * l, 0.035f * (1 - l), 1 - l, (float) a);
			}
			case GLITTER -> set(out, 0, 0, 0.03f, 0.05f * rise * rise, rise, l * 2);
		}
	}

	private static void set(float[] out, float dx, float dy, float dz, float size, float alpha, float turn) {
		out[1] = dx;
		out[2] = dy;
		out[3] = dz;
		out[4] = size;
		out[5] = alpha;
		out[6] = turn;
	}

	/** Clockwork: a big gear on the shoulder hub, a smaller one on the upper hub, meshing (14 : 12 teeth). */
	private static final Rig CLOCKWORK = new Rig(new Gear[] {
			new Gear(0.18f, 0.053f, -0.075f, 0.17f, 14, 1f),
			new Gear(0.39f, 0.255f, -0.07f, 0.148f, 12, -14f / 12f)},
			new float[] {0.2015f, 0.6265f}, new float[] {0.1373f, 0.1441f}, new float[] {0.7188f, 0.6328f});

	private WingRigs() {
	}

	/** How pair {@code id} shines, or null. */
	public static Shine shine(String id) {
		return switch (id) {
			case "gold" -> new Shine(0xFFF0C4);
			case "silver" -> new Shine(0xE8F2FF);
			case "katana" -> new Shine(0xF4F8FF);
			default -> null;
		};
	}

	/** Where a point (body space, relative to its shoulder) is across the wing, 0..1 along the glint's way: from low by the body out to the top tips. */
	public static float band(float x, float y, int side) {
		return Math.max(0f, Math.min(1f, (side * x * 0.8f - y * 0.6f + 0.35f) / 1.45f));
	}

	/** Seconds per glint sweep, and the share of it the sweep takes (the rest it rests). */
	private static final float GLINT_CYCLE = 3.2f;
	private static final float GLINT_SWEEP = 0.45f;

	private static float glintFront(float seconds) {
		float p = (seconds % GLINT_CYCLE) / GLINT_CYCLE;
		return -0.15f + 1.3f * p / GLINT_SWEEP;
	}

	/** The tinted halo of the glint at {@code band} after {@code seconds} (0..1). */
	public static float glint(float band, float seconds) {
		float d = (band - glintFront(seconds)) / 0.09f;
		return (float) Math.exp(-d * d);
	}

	/** The glint's narrow white-hot core (0..1), and a thinner echo just behind it. */
	public static float glintCore(float band, float seconds) {
		float front = glintFront(seconds);
		float d = (band - front) / 0.022f;
		float e = (band - front + 0.07f) / 0.012f;
		return (float) Math.min(1.0, Math.exp(-d * d) + 0.6 * Math.exp(-e * e));
	}

	/**
	 * A mirror highlight (0..1) for a feather facing {@code nx, ny, nz} (body space): the light comes from above and
	 * behind, as seen from behind the wearer - it slides over the feathers as the wingbeat turns them.
	 */
	public static float specular(float nx, float ny, float nz) {
		// Half-way between the light (up, back) and the eye (back): normalised (0.25, -0.62, 0.74).
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) {
			return 0;
		}
		float d = Math.abs(nx * 0.25f - ny * 0.62f + nz * 0.74f) / len;
		return (float) Math.pow(d, 28);
	}

	/** Stars twinkling at a time per half. */
	public static final int STARS = 7;

	/**
	 * Star {@code k} of half {@code half}: {@code out[0]} which vertex it sits on (of {@code vertices}), {@code out[1]}
	 * how bright it is (0..1, up and down in a flash) and {@code out[2]} its turn (rad).
	 */
	public static void star(int k, int half, float seconds, int vertices, float[] out) {
		double t = seconds * 0.9 + k * 0.618 + half * 0.31;
		long slot = (long) Math.floor(t);
		double life = t - slot;
		double h = Math.sin(slot * 127.1 + k * 311.7 + half * 74.7) * 43758.5453;
		h -= Math.floor(h);
		out[0] = (int) (h * vertices);
		// Off most of the time: a quick flash in the middle of its slot.
		double f = Math.max(0, 1 - Math.abs(life - 0.5) / 0.22);
		out[1] = (float) (f * f * (3 - 2 * f));
		out[2] = (float) (life * 1.6);
	}

	/** How far (blocks, backwards) a feather at {@code x, y} with {@code weight} (0 shoulder, 1 tip) is lifted by the ripple. */
	public static float ripple(float x, float y, float weight, float seconds) {
		return 0.02f * weight * weight * (float) Math.sin(seconds * 5.2 + x * 16 + y * 11);
	}

	/** The gears of pair {@code id}, or null. */
	public static Rig rig(String id) {
		return "clockwork".equals(id) ? CLOCKWORK : null;
	}

	/** The arms of one half: which arm each vertex is on (-1: the body they grow from) and how far along it, and how they move. */
	public static final class Arms {
		/** Bones per arm. */
		public static final int SEGMENTS = 8;
		/** Every arm's rhythm repeats after this many time units. */
		public static final float LOOP = 4f;

		final Motion motion;
		final int[] arm;
		final float[] along;
		final int arms;
		/** Per arm: where it sits in the fan (0..1, by direction from the shoulder) and the way it points (x, y, z). */
		final float[] rank;
		final float[] direction;
		/** Per arm: its centre line at rest, {@link #SEGMENTS} + 1 points (x, y, z). */
		final float[] joints;
		/** Per arm: the in-plane axis it swings out of the wing's plane around. */
		final float[] swingAxis;
		/** Per arm: rhythm (cycles per time unit, a multiple of 1 / LOOP), phase and strength. */
		final float[] frequency;
		final float[] phase;
		final float[] strength;

		Arms(Motion motion, int[] arm, float[] along, int arms, float[] joints, float[] swingAxis, float[] rank, float[] direction) {
			this.motion = motion;
			this.rank = rank;
			this.direction = direction;
			this.arm = arm;
			this.along = along;
			this.arms = arms;
			this.joints = joints;
			this.swingAxis = swingAxis;
			frequency = new float[arms];
			phase = new float[arms];
			strength = new float[arms];
			for (int a = 0; a < arms; a++) {
				// Spread out like the golden angle, so neighbours never move in step.
				double g = a * 0.6180339887;
				g -= Math.floor(g);
				frequency[a] = (3 + a % 3) / LOOP;
				phase[a] = (float) (g * Math.PI * 2);
				strength[a] = 0.8f + 0.4f * (float) ((a * 0.3819660113) % 1.0);
			}
		}

		public int arms() {
			return arms;
		}
	}

	/** Below this share of the longest reach, a branch is a bump, not an arm. */
	private static final float MIN_ARM = 0.06f;

	/**
	 * Finds the arms of a half, to move them {@code motion}'s way: {@code geometry} has {@code stride} floats per vertex (x, y, z first, body space,
	 * relative to the shoulder), {@code triangles} index it. Null when there aren't at least two arms.
	 */
	public static Arms arms(float[] geometry, int stride, int[] triangles, Motion motion) {
		int count = geometry.length / stride;
		// Weld: the model's vertices are split along texture seams, the surface isn't.
		java.util.Map<Long, Integer> weld = new java.util.HashMap<>();
		int[] node = new int[count];
		List<float[]> positions = new ArrayList<>();
		for (int v = 0; v < count; v++) {
			float x = geometry[v * stride];
			float y = geometry[v * stride + 1];
			float z = geometry[v * stride + 2];
			long key = (Math.round(x * 1e4) & 0x1FFFFF) << 42 | (Math.round(y * 1e4) & 0x1FFFFF) << 21 | (Math.round(z * 1e4) & 0x1FFFFF);
			Integer id = weld.get(key);
			if (id == null) {
				id = positions.size();
				weld.put(key, id);
				positions.add(new float[] {x, y, z});
			}
			node[v] = id;
		}
		int n = positions.size();
		List<java.util.Set<Integer>> links = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			links.add(new java.util.HashSet<>());
		}
		for (int t = 0; t + 2 < triangles.length; t += 3) {
			for (int c = 0; c < 3; c++) {
				int a = node[triangles[t + c]];
				int b = node[triangles[t + (c + 1) % 3]];
				if (a != b) {
					links.get(a).add(b);
					links.get(b).add(a);
				}
			}
		}
		int[][] adjacent = new int[n][];
		for (int i = 0; i < n; i++) {
			adjacent[i] = links.get(i).stream().mapToInt(Integer::intValue).toArray();
		}

		// Distance over the surface from the shoulder (starting at the few points closest to it).
		double[] dist = new double[n];
		java.util.Arrays.fill(dist, Double.MAX_VALUE);
		java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
		Integer[] byReach = new Integer[n];
		for (int i = 0; i < n; i++) {
			byReach[i] = i;
		}
		java.util.Arrays.sort(byReach, (a, b) -> Float.compare(length(positions.get(a)), length(positions.get(b))));
		for (int i = 0; i < Math.min(3, n); i++) {
			dist[byReach[i]] = length(positions.get(byReach[i]));
			queue.add(new double[] {dist[byReach[i]], byReach[i]});
		}
		while (true) {
			while (!queue.isEmpty()) {
				double[] e = queue.poll();
				int u = (int) e[1];
				if (e[0] > dist[u]) {
					continue;
				}
				for (int w : adjacent[u]) {
					float[] pu = positions.get(u);
					float[] pw = positions.get(w);
					double d = e[0] + Math.sqrt(sq(pu[0] - pw[0]) + sq(pu[1] - pw[1]) + sq(pu[2] - pw[2]));
					if (d < dist[w]) {
						dist[w] = d;
						queue.add(new double[] {d, w});
					}
				}
			}
			// A loose piece (a blade or shard not welded to the rest): from its point closest to the shoulder.
			int loose = -1;
			for (int i : byReach) {
				if (dist[i] == Double.MAX_VALUE) {
					loose = i;
					break;
				}
			}
			if (loose < 0) {
				break;
			}
			dist[loose] = length(positions.get(loose));
			queue.add(new double[] {dist[loose], loose});
		}
		double longest = 0;
		for (double d : dist) {
			if (d < Double.MAX_VALUE) {
				longest = Math.max(longest, d);
			}
		}
		if (longest <= 0) {
			return null;
		}

		// Merge tree: sweep from the far end in. A point with no swept neighbour is a new tip; where two branches
		// meet, the one reaching farther lives on and the other ends there.
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		java.util.Arrays.sort(order, (a, b) -> Double.compare(dist[b], dist[a]));
		int[] parent = new int[n];
		int[] label = new int[n];
		double[] birth = new double[n];
		double[] death = new double[n];
		int[] absorbedBy = new int[n];
		boolean[] swept = new boolean[n];
		java.util.Arrays.fill(absorbedBy, -1);
		for (int i = 0; i < n; i++) {
			parent[i] = i;
		}
		for (int u : order) {
			if (dist[u] == Double.MAX_VALUE) {
				label[u] = -1;
				continue;
			}
			int survivor = -1;
			List<Integer> roots = new ArrayList<>();
			for (int w : adjacent[u]) {
				if (swept[w]) {
					int r = find(parent, w);
					if (!roots.contains(r)) {
						roots.add(r);
					}
				}
			}
			swept[u] = true;
			if (roots.isEmpty()) {
				birth[u] = dist[u];
				label[u] = u;
				continue;
			}
			for (int r : roots) {
				if (survivor < 0 || birth[r] > birth[survivor]) {
					survivor = r;
				}
			}
			for (int r : roots) {
				if (r != survivor) {
					death[r] = dist[u];
					absorbedBy[r] = survivor;
					parent[r] = survivor;
				}
			}
			parent[u] = survivor;
			label[u] = survivor;
		}
		double min = MIN_ARM * longest;
		boolean[] significant = new boolean[n];
		for (int i = 0; i < n; i++) {
			significant[i] = label[i] == i && birth[i] - death[i] > min;
		}
		// Where each arm leaves the body: the highest point where another real arm joined it (or where it joined; a
		// loose piece's own start).
		double[] base = new double[n];
		for (int i = 0; i < n; i++) {
			int r = find(parent, i);
			if (absorbedBy[r] < 0) {
				base[r] = base[r] == 0 ? dist[i] : Math.min(base[r], dist[i]);
			}
		}
		for (int i = 0; i < n; i++) {
			if (label[i] == i && absorbedBy[i] >= 0) {
				base[i] = 0;
			}
		}
		for (int i = 0; i < n; i++) {
			if (significant[i]) {
				base[i] = Math.max(base[i], death[i]);
				if (absorbedBy[i] >= 0) {
					int into = resolve(absorbedBy[i], significant, absorbedBy);
					if (into >= 0) {
						base[into] = Math.max(base[into], death[i]);
					}
				}
			}
		}
		int[] armOfRoot = new int[n];
		java.util.Arrays.fill(armOfRoot, -1);
		int arms = 0;
		for (int i = 0; i < n; i++) {
			if (significant[i]) {
				armOfRoot[i] = arms++;
			}
		}
		if (arms < 2) {
			return null;
		}
		int[] rootOfArm = new int[arms];
		for (int i = 0; i < n; i++) {
			if (armOfRoot[i] >= 0) {
				rootOfArm[armOfRoot[i]] = i;
			}
		}
		int[] nodeArm = new int[n];
		float[] nodeAlong = new float[n];
		for (int i = 0; i < n; i++) {
			int root = label[i] < 0 ? -1 : resolve(label[i], significant, absorbedBy);
			if (root < 0 || dist[i] < base[root]) {
				nodeArm[i] = -1;
				continue;
			}
			nodeArm[i] = armOfRoot[root];
			nodeAlong[i] = (float) Math.min(1, (dist[i] - base[root]) / Math.max(1e-6, birth[root] - base[root]));
		}

		// Each arm's centre line: the middle of its points at each step along it.
		int k = Arms.SEGMENTS;
		float[] joints = new float[arms * (k + 1) * 3];
		float[] weights = new float[arms * (k + 1)];
		for (int i = 0; i < n; i++) {
			if (nodeArm[i] < 0) {
				continue;
			}
			int j = Math.round(nodeAlong[i] * k);
			int at = nodeArm[i] * (k + 1) + j;
			float[] p = positions.get(i);
			joints[at * 3] += p[0];
			joints[at * 3 + 1] += p[1];
			joints[at * 3 + 2] += p[2];
			weights[at]++;
		}
		float[] swing = new float[arms * 3];
		float[] direction = new float[arms * 3];
		float[] heading = new float[arms];
		float sideSum = 0;
		for (float[] p : positions) {
			sideSum += p[0];
		}
		int side = sideSum < 0 ? -1 : 1;
		for (int a = 0; a < arms; a++) {
			for (int j = 0; j <= k; j++) {
				int at = a * (k + 1) + j;
				if (weights[at] > 0) {
					for (int c = 0; c < 3; c++) {
						joints[at * 3 + c] /= weights[at];
					}
				}
			}
			// Steps with no points in them: in between their neighbours.
			for (int j = 0; j <= k; j++) {
				int at = a * (k + 1) + j;
				if (weights[at] > 0) {
					continue;
				}
				int lo = j - 1;
				while (lo >= 0 && weights[a * (k + 1) + lo] == 0) {
					lo--;
				}
				int hi = j + 1;
				while (hi <= k && weights[a * (k + 1) + hi] == 0) {
					hi++;
				}
				for (int c = 0; c < 3; c++) {
					float from = lo >= 0 ? joints[(a * (k + 1) + lo) * 3 + c] : hi <= k ? joints[(a * (k + 1) + hi) * 3 + c] : 0;
					float to = hi <= k ? joints[(a * (k + 1) + hi) * 3 + c] : from;
					float f = lo >= 0 && hi <= k ? (float) (j - lo) / (hi - lo) : 0;
					joints[at * 3 + c] = from + (to - from) * f;
				}
			}
			float dx = joints[(a * (k + 1) + k) * 3] - joints[a * (k + 1) * 3];
			float dy = joints[(a * (k + 1) + k) * 3 + 1] - joints[a * (k + 1) * 3 + 1];
			float len = (float) Math.sqrt(dx * dx + dy * dy);
			swing[a * 3] = len > 1e-6f ? dy / len : 1;
			swing[a * 3 + 1] = len > 1e-6f ? -dx / len : 0;
			float dz = joints[(a * (k + 1) + k) * 3 + 2] - joints[a * (k + 1) * 3 + 2];
			float len3 = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len3 > 1e-6f) {
				direction[a * 3] = dx / len3;
				direction[a * 3 + 1] = dy / len3;
				direction[a * 3 + 2] = dz / len3;
			}
			heading[a] = (float) Math.atan2(dy, side * dx);
		}
		float[] rank = new float[arms];
		for (int a = 0; a < arms; a++) {
			int below = 0;
			for (int b = 0; b < arms; b++) {
				if (heading[b] < heading[a] || heading[b] == heading[a] && b < a) {
					below++;
				}
			}
			rank[a] = arms > 1 ? below / (float) (arms - 1) : 0;
		}
		int[] arm = new int[count];
		float[] along = new float[count];
		for (int v = 0; v < count; v++) {
			arm[v] = nodeArm[node[v]];
			along[v] = nodeAlong[node[v]];
		}
		return new Arms(motion, arm, along, arms, joints, swing, rank, direction);
	}

	private static int find(int[] parent, int x) {
		while (parent[x] != x) {
			parent[x] = parent[parent[x]];
			x = parent[x];
		}
		return x;
	}

	/** The real arm a branch counts as: itself, or the one it was swallowed into. */
	private static int resolve(int root, boolean[] significant, int[] absorbedBy) {
		int x = root;
		while (!significant[x] && absorbedBy[x] >= 0) {
			x = absorbedBy[x];
		}
		return significant[x] ? x : -1;
	}

	private static float length(float[] p) {
		return (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
	}

	private static double sq(double v) {
		return v * v;
	}

	/**
	 * Bends every arm of {@code rig}: writes {@code src} (x, y, z and, with stride 6, the normal) posed into {@code dst}.
	 * Body vertices are copied as they are. {@code amount}: how wild (about the tip's turn, rad), {@code time} in time units.
	 */
	public static void pose(Arms rig, float amount, float time, float[] src, int stride, float[] dst, int side) {
		int k = Arms.SEGMENTS;
		// Per arm and joint: the turn so far (curl in the wing's plane, swing out of it) and where the joint went.
		float[] curl = new float[rig.arms * (k + 1)];
		float[] swing = new float[rig.arms * (k + 1)];
		float[] moved = new float[rig.arms * (k + 1) * 3];
		float[] tmp = new float[3];
		chain(rig, amount, time, side, curl, swing, moved);
		int count = src.length / stride;
		for (int v = 0; v < count; v++) {
			int i = v * stride;
			int a = rig.arm[v];
			if (a < 0) {
				System.arraycopy(src, i, dst, i, stride);
				continue;
			}
			float t = rig.along[v] * k;
			int j = Math.min(k - 1, (int) t);
			float f = t - j;
			int at = a * (k + 1) + j;
			// The point on the centre line at rest, and where it is now.
			float cx = rig.joints[at * 3] + (rig.joints[at * 3 + 3] - rig.joints[at * 3]) * f;
			float cy = rig.joints[at * 3 + 1] + (rig.joints[at * 3 + 4] - rig.joints[at * 3 + 1]) * f;
			float cz = rig.joints[at * 3 + 2] + (rig.joints[at * 3 + 5] - rig.joints[at * 3 + 2]) * f;
			float mx = moved[at * 3] + (moved[at * 3 + 3] - moved[at * 3]) * f;
			float my = moved[at * 3 + 1] + (moved[at * 3 + 4] - moved[at * 3 + 1]) * f;
			float mz = moved[at * 3 + 2] + (moved[at * 3 + 5] - moved[at * 3 + 2]) * f;
			float c = curl[at] + (curl[at + 1] - curl[at]) * f;
			float s = swing[at] + (swing[at + 1] - swing[at]) * f;
			tmp[0] = src[i] - cx;
			tmp[1] = src[i + 1] - cy;
			tmp[2] = src[i + 2] - cz;
			turn(tmp, c, s, rig.swingAxis, a);
			dst[i] = mx + tmp[0];
			dst[i + 1] = my + tmp[1];
			dst[i + 2] = mz + tmp[2];
			if (stride >= 6) {
				tmp[0] = src[i + 3];
				tmp[1] = src[i + 4];
				tmp[2] = src[i + 5];
				turn(tmp, c, s, rig.swingAxis, a);
				dst[i + 3] = tmp[0];
				dst[i + 4] = tmp[1];
				dst[i + 5] = tmp[2];
			}
			for (int e = 6; e < stride; e++) {
				dst[i + e] = src[i + e];
			}
		}
	}

	/** Which bone a vertex moves with ({@code arm * SEGMENTS + segment}), or -1 on the body. */
	public static int bone(Arms rig, int vertex) {
		int a = rig.arm[vertex];
		return a < 0 ? -1 : a * Arms.SEGMENTS + Math.min(Arms.SEGMENTS - 1, (int) (rig.along[vertex] * Arms.SEGMENTS));
	}

	/**
	 * Every bone as a rigid transform for {@code time} (the launcher moves bone-sized pieces with these instead of
	 * rewriting points): per bone 12 floats, the 3 x 3 turn row by row then the shift - {@code p' = R p + t}.
	 */
	public static float[] bones(Arms rig, float amount, float time, int side, float[] out) {
		int k = Arms.SEGMENTS;
		if (out == null || out.length != rig.arms * k * 12) {
			out = new float[rig.arms * k * 12];
		}
		float[] curl = new float[rig.arms * (k + 1)];
		float[] swing = new float[rig.arms * (k + 1)];
		float[] moved = new float[rig.arms * (k + 1) * 3];
		chain(rig, amount, time, side, curl, swing, moved);
		float[] col = new float[3];
		for (int a = 0; a < rig.arms; a++) {
			for (int j = 0; j < k; j++) {
				int at = a * (k + 1) + j;
				float c = (curl[at] + curl[at + 1]) / 2;
				float s = (swing[at] + swing[at + 1]) / 2;
				int o = (a * k + j) * 12;
				for (int axis = 0; axis < 3; axis++) {
					col[0] = axis == 0 ? 1 : 0;
					col[1] = axis == 1 ? 1 : 0;
					col[2] = axis == 2 ? 1 : 0;
					turn(col, c, s, rig.swingAxis, a);
					out[o + axis] = col[0];
					out[o + 3 + axis] = col[1];
					out[o + 6 + axis] = col[2];
				}
				// The bone's middle goes where its moved middle is.
				for (int r = 0; r < 3; r++) {
					float now = (moved[at * 3 + r] + moved[at * 3 + 3 + r]) / 2;
					float rx = (rig.joints[at * 3] + rig.joints[at * 3 + 3]) / 2;
					float ry = (rig.joints[at * 3 + 1] + rig.joints[at * 3 + 4]) / 2;
					float rz = (rig.joints[at * 3 + 2] + rig.joints[at * 3 + 5]) / 2;
					out[o + 9 + r] = now - (out[o + r * 3] * rx + out[o + r * 3 + 1] * ry + out[o + r * 3 + 2] * rz);
				}
			}
		}
		return out;
	}

	/** The bone chains for {@code time}: per arm and joint the turn so far ({@code curl}, {@code swing}) and where it went ({@code moved}). */
	private static void chain(Arms rig, float amount, float time, int side, float[] curl, float[] swing, float[] moved) {
		int k = Arms.SEGMENTS;
		float[] tmp = new float[3];
		// Fans and claws share one slow beat, so a ripple can run across them.
		double shared = Math.PI * 2 * (2 / Arms.LOOP) * time;
		for (int a = 0; a < rig.arms; a++) {
			double w = Math.PI * 2 * rig.frequency[a] * time + rig.phase[a];
			double slow = Math.PI * 2 * (0.5 / Arms.LOOP) * time + rig.phase[a] * 1.7;
			float s = amount * rig.strength[a];
			float rank = rig.rank[a];
			float stretch = 0;
			int at0 = a * (k + 1);
			float c = 0;
			float sw = 0;
			for (int j = 0; j <= k; j++) {
				if (j > 0) {
					float along = (j - 0.5f) / k;
					// A wave running down the arm (the tip whips after the root) plus a slow sway of the whole arm.
					float dc;
					float ds;
					switch (rig.motion) {
						case FLICKER -> {
							double f = Math.PI * 2 * rig.frequency[a] * 3 * time + rig.phase[a];
							dc = s * (1.6f / k) * (0.2f + along) * (float) Math.sin(f - 4 * along);
							ds = s * (0.7f / k) * (0.2f + along) * (float) Math.cos(f - 3 * along + 1);
							stretch = 0.22f * s * (float) Math.sin(f + 0.7);
						}
						case FLUTTER -> {
							double f = Math.PI * 2 * rig.frequency[a] * 2 * time + rig.phase[a];
							dc = s * (0.9f / k) * (0.3f + along) * (float) Math.sin(f - 2 * along);
							ds = s * (0.6f / k) * (0.3f + along) * (float) Math.sin(f + 1.3 - 2 * along);
						}
						case FAN -> {
							// The fan spreads open and closes back to rest: each blade turns at its root by its place in
							// the fan, so neighbours only ever move apart and never cross. A small lag across the fan
							// makes it ripple open.
							float open = (float) (0.5 - 0.5 * Math.cos(shared - rank * 0.6));
							dc = j == 1 ? s * 0.55f * (rank - 0.5f) * open : 0;
							ds = j == 1 ? s * 0.06f * open : 0;
						}
						case FLOAT -> {
							dc = j == 1 ? s * 0.18f * (float) Math.sin(w + 1) : 0;
							ds = j == 1 ? s * 0.12f * (float) Math.cos(w * 1.0 + 2) : 0;
						}
						case FLEX -> {
							float grip = (float) (0.5 + 0.5 * Math.sin(shared - rank * 2.0));
							dc = s * (2.4f / k) * along * grip;
							ds = s * (0.3f / k) * (float) Math.sin(w - along);
						}
						case WAVE -> {
							dc = s * (1.0f / k) * (0.3f + along) * (float) Math.sin(w - 3 * along);
							ds = s * (1.8f / k) * (0.3f + along) * (float) Math.sin(w - 3.5 * along + 1);
						}
						default -> {
							dc = s * (2.2f / k) * (0.3f + along) * (float) Math.sin(w - 2.8 * along)
									+ s * (0.8f / k) * (float) Math.sin(slow);
							ds = s * (1.4f / k) * (0.3f + along) * (float) Math.sin(w * 1.0 + 1.9 - 2.2 * along);
						}
					}
					float pc = c;
					float ps = sw;
					c += side * dc;
					sw += ds;
					// The bone from the last joint, turned by the average of its two ends.
					int prev = (at0 + j - 1) * 3;
					int here = (at0 + j) * 3;
					tmp[0] = rig.joints[here] - rig.joints[prev];
					tmp[1] = rig.joints[here + 1] - rig.joints[prev + 1];
					tmp[2] = rig.joints[here + 2] - rig.joints[prev + 2];
					turn(tmp, (pc + c) / 2, (ps + sw) / 2, rig.swingAxis, a);
					float grow = 1 + stretch * along;
					tmp[0] *= grow;
					tmp[1] *= grow;
					tmp[2] *= grow;
					moved[here] = moved[prev] + tmp[0];
					moved[here + 1] = moved[prev + 1] + tmp[1];
					moved[here + 2] = moved[prev + 2] + tmp[2];
				} else {
					moved[at0 * 3] = rig.joints[at0 * 3];
					moved[at0 * 3 + 1] = rig.joints[at0 * 3 + 1];
					moved[at0 * 3 + 2] = rig.joints[at0 * 3 + 2];
				}
				curl[at0 + j] = c;
				swing[at0 + j] = sw;
			}
			if (rig.motion == Motion.FLOAT) {
				// Shards drift off along the way they point, and back.
				float off = s * 0.18f * (float) (0.5 + 0.5 * Math.sin(w));
				for (int j = 0; j <= k; j++) {
					moved[(at0 + j) * 3] += rig.direction[a * 3] * off;
					moved[(at0 + j) * 3 + 1] += rig.direction[a * 3 + 1] * off;
					moved[(at0 + j) * 3 + 2] += rig.direction[a * 3 + 2] * off;
				}
			}
		}
	}

	/** Turns {@code v} around arm {@code a}'s in-plane axis by {@code swing}, then around z (backwards) by {@code curl}. */
	private static void turn(float[] v, float curl, float swing, float[] axes, int a) {
		float ax = axes[a * 3];
		float ay = axes[a * 3 + 1];
		float cs = (float) Math.cos(swing);
		float ss = (float) Math.sin(swing);
		// Rodrigues around (ax, ay, 0).
		float dot = ax * v[0] + ay * v[1];
		float crossX = ay * v[2];
		float crossY = -ax * v[2];
		float crossZ = ax * v[1] - ay * v[0];
		float x = v[0] * cs + crossX * ss + ax * dot * (1 - cs);
		float y = v[1] * cs + crossY * ss + ay * dot * (1 - cs);
		float z = v[2] * cs + crossZ * ss;
		float cc = (float) Math.cos(curl);
		float sc = (float) Math.sin(curl);
		v[0] = x * cc - y * sc;
		v[1] = x * sc + y * cc;
		v[2] = z;
	}

	/**
	 * Builds one gear: a toothed rim, spokes and a raised hub, {@code thickness} deep along z with its front face at
	 * +z (away from the wearer). Mirrored ({@code side} -1) for the wearer's right wing. Flat shaded.
	 */
	public static GearMesh gearMesh(Gear gear, Rig rig, int side, float scale) {
		float r = gear.radius() * scale;
		float t = 0.018f * scale;
		int n = gear.teeth();
		float root = r * 0.8f;
		float inner = r * 0.6f;
		Builder b = new Builder(rig);
		double step = Math.PI / n;
		for (int k = 0; k < 2 * n; k++) {
			double a0 = k * step;
			double a1 = (k + 1) * step;
			b.prism(new float[] {polar(inner, a0)[0], polar(inner, a0)[1], polar(root, a0)[0], polar(root, a0)[1],
					polar(root, a1)[0], polar(root, a1)[1], polar(inner, a1)[0], polar(inner, a1)[1]}, -t / 2, t / 2, false);
		}
		for (int i = 0; i < n; i++) {
			double c = 2 * Math.PI * i / n;
			double rootHalf = step * 0.55;
			double tipHalf = step * 0.3;
			float in = root * 0.97f;
			b.prism(new float[] {polar(in, c - rootHalf)[0], polar(in, c - rootHalf)[1], polar(r, c - tipHalf)[0], polar(r, c - tipHalf)[1],
					polar(r, c + tipHalf)[0], polar(r, c + tipHalf)[1], polar(in, c + rootHalf)[0], polar(in, c + rootHalf)[1]}, -t / 2, t / 2, false);
		}
		float hub = r * 0.28f;
		float w = r * 0.07f;
		for (int i = 0; i < 4; i++) {
			double c = Math.PI / 2 * i + Math.PI / 4;
			float cx = (float) Math.cos(c);
			float cy = (float) Math.sin(c);
			float px = -cy * w;
			float py = cx * w;
			float h0 = hub * 0.8f;
			float h1 = inner * 1.03f;
			b.prism(new float[] {cx * h0 - px, cy * h0 - py, cx * h1 - px, cy * h1 - py, cx * h1 + px, cy * h1 + py, cx * h0 + px, cy * h0 + py},
					-t * 0.4f, t * 0.4f, false);
		}
		float[] ring = new float[20];
		for (int i = 0; i < 10; i++) {
			float[] p = polar(hub, 2 * Math.PI * i / 10);
			ring[i * 2] = p[0];
			ring[i * 2 + 1] = p[1];
		}
		b.prism(ring, -t * 0.7f, t * 1.2f, true);
		return b.build(side);
	}

	private static float[] polar(float radius, double angle) {
		return new float[] {radius * (float) Math.cos(angle), radius * (float) Math.sin(angle)};
	}

	/** Collects flat-shaded triangles, each wound so its corners turn counter-clockwise around its normal. */
	private static final class Builder {
		private final Rig rig;
		private final List<float[]> vertices = new ArrayList<>();

		Builder(Rig rig) {
			this.rig = rig;
		}

		/** A convex polygon {x0, y0, x1, y1, ...} extruded from z0 to z1: front, back and side walls. */
		void prism(float[] poly, float z0, float z1, boolean hub) {
			int m = poly.length / 2;
			float mx = 0;
			float my = 0;
			for (int i = 0; i < m; i++) {
				mx += poly[i * 2] / m;
				my += poly[i * 2 + 1] / m;
			}
			float[] face = hub ? rig.hubUv() : rig.faceUv();
			float[] edge = hub ? rig.hubUv() : rig.edgeUv();
			for (int i = 1; i < m - 1; i++) {
				triangle(new float[] {poly[0], poly[1], z1, poly[i * 2], poly[i * 2 + 1], z1, poly[i * 2 + 2], poly[i * 2 + 3], z1}, 0, 0, 1, face);
				triangle(new float[] {poly[0], poly[1], z0, poly[i * 2], poly[i * 2 + 1], z0, poly[i * 2 + 2], poly[i * 2 + 3], z0}, 0, 0, -1, face);
			}
			for (int i = 0; i < m; i++) {
				int j = (i + 1) % m;
				float ax = poly[i * 2];
				float ay = poly[i * 2 + 1];
				float bx = poly[j * 2];
				float by = poly[j * 2 + 1];
				float nx = by - ay;
				float ny = ax - bx;
				if (nx * ((ax + bx) / 2 - mx) + ny * ((ay + by) / 2 - my) < 0) {
					nx = -nx;
					ny = -ny;
				}
				float len = (float) Math.sqrt(nx * nx + ny * ny);
				if (len < 1e-7f) {
					continue;
				}
				nx /= len;
				ny /= len;
				triangle(new float[] {ax, ay, z0, bx, by, z0, bx, by, z1}, nx, ny, 0, edge);
				triangle(new float[] {ax, ay, z0, bx, by, z1, ax, ay, z1}, nx, ny, 0, edge);
			}
		}

		private void triangle(float[] p, float nx, float ny, float nz, float[] uv) {
			float ux = p[3] - p[0];
			float uy = p[4] - p[1];
			float uz = p[5] - p[2];
			float vx = p[6] - p[0];
			float vy = p[7] - p[1];
			float vz = p[8] - p[2];
			float dot = (uy * vz - uz * vy) * nx + (uz * vx - ux * vz) * ny + (ux * vy - uy * vx) * nz;
			int[] order = dot >= 0 ? new int[] {0, 1, 2} : new int[] {0, 2, 1};
			for (int k : order) {
				vertices.add(new float[] {p[k * 3], p[k * 3 + 1], p[k * 3 + 2], nx, ny, nz, uv[0], uv[1]});
			}
		}

		GearMesh build(int side) {
			int count = vertices.size();
			float[] geometry = new float[count * 6];
			float[] uv = new float[count * 2];
			int[] triangles = new int[count];
			for (int v = 0; v < count; v++) {
				float[] s = vertices.get(v);
				geometry[v * 6] = s[0] * side;
				geometry[v * 6 + 1] = s[1];
				geometry[v * 6 + 2] = s[2];
				geometry[v * 6 + 3] = s[3] * side;
				geometry[v * 6 + 4] = s[4];
				geometry[v * 6 + 5] = s[5];
				uv[v * 2] = s[6];
				uv[v * 2 + 1] = s[7];
			}
			for (int i = 0; i < count; i += 3) {
				// Mirroring flips the winding: swap two corners back.
				triangles[i] = i;
				triangles[i + 1] = side < 0 ? i + 2 : i + 1;
				triangles[i + 2] = side < 0 ? i + 1 : i + 2;
			}
			return new GearMesh(geometry, uv, triangles);
		}
	}
}
