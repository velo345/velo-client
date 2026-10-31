package net.veloclient.velo.client.cosmetics.killfx;

import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects.Out;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static net.veloclient.velo.client.cosmetics.aura.AuraEffects.*;

/**
 * The Store's kill effects - one-shot animations played where someone died, plain Java like {@link AuraEffects} (the
 * launcher compiles this file too, for its previews). A {@link Burst} is one playing effect: it runs a short
 * timeline (flash, shockwaves, debris...) and draws itself with the auras' sprites, beams and quads, relative to the
 * victim's feet (y up, blocks). Recoloured the auras' way ({@link Settings#color()}); size, speed and density are
 * unused. Each kind has its own sound ({@link #sound}).
 */
public final class KillFxEffects {

	public enum Kind {
		/** The body bursts into glowing crystal shards that fly out, bounce and fade. */
		SHATTER,
		/** A target ring locks on, then a beam from the sky slams down and scorches the ground. */
		ORBITAL,
		/** A black hole opens, swallows everything around it and collapses with a bang. */
		BLACKHOLE,
		/** Flames burst and a phoenix of fire rises from the ashes, wings beating. */
		PHOENIX,
		/** A scythe slashes twice and the soul is torn out of its chains, rising away. */
		REAPER,
		/** The body breaks up into pixels that glitch and float away, with binary rain and scan lines. */
		GLITCH,
		/** A star collapses, then explodes - rays, shock shells and a glowing nebula. */
		SUPERNOVA,
		/** Ice spikes erupt round the body, freeze it in a crystal and shatter. */
		FROSTBITE,
		/** A burning meteor streaks down and smashes a crater, throwing rocks and smoke. */
		METEOR,
		/** A whirlwind spins up out of the ground, lifting debris, and blows apart. */
		TORNADO,
		/** A gravestone rises out of the ground among candles and flowers. */
		GRAVE,
		/** Three forked lightning bolts strike and arcs crawl over the ground. */
		THUNDER,
		/** Shadow tendrils burst out of the ground and drag the body down into dark smoke, red eyes watching. */
		SHADOW,
		/** Three katana slashes cross in a flash and the body bursts into cherry blossoms. */
		KATANA,
		/** 7-7-7 pops up and a fountain of coins rains down. */
		JACKPOT
	}

	private static final float TAU = (float) (Math.PI * 2);

	private KillFxEffects() {
	}

	public static Kind kind(String id) {
		return switch (id) {
			case "shatter" -> Kind.SHATTER;
			case "orbital" -> Kind.ORBITAL;
			case "blackhole" -> Kind.BLACKHOLE;
			case "phoenix" -> Kind.PHOENIX;
			case "reaper" -> Kind.REAPER;
			case "glitch" -> Kind.GLITCH;
			case "supernova" -> Kind.SUPERNOVA;
			case "frostbite" -> Kind.FROSTBITE;
			case "meteor" -> Kind.METEOR;
			case "tornado" -> Kind.TORNADO;
			case "grave" -> Kind.GRAVE;
			case "thunder" -> Kind.THUNDER;
			case "shadow" -> Kind.SHADOW;
			case "katana" -> Kind.KATANA;
			case "jackpot" -> Kind.JACKPOT;
			default -> null;
		};
	}

	/** The sound event id (assets/velo-client/sounds.json, made by tools/cosmetics/killfx/make_sounds.py). */
	public static String sound(Kind kind) {
		return "velo-client:killfx." + kind.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The effect's own two colours. */
	public static int[] defaults(Kind kind) {
		return switch (kind) {
			case SHATTER -> new int[] {0xE6F7FF, 0x5CC8FF};
			case ORBITAL -> new int[] {0xF0FBFF, 0xFF4D3D};
			case BLACKHOLE -> new int[] {0xE2B8FF, 0x7A2BFF};
			case PHOENIX -> new int[] {0xFFE17A, 0xFF4A1C};
			case REAPER -> new int[] {0xC8FFEA, 0x1FCF8A};
			case GLITCH -> new int[] {0x3FF0FF, 0xFF3FD8};
			case SUPERNOVA -> new int[] {0xFFF4D6, 0xFF7A3D};
			case FROSTBITE -> new int[] {0xF0FAFF, 0x5CB8FF};
			case METEOR -> new int[] {0xFFD27A, 0xFF4A1C};
			case TORNADO -> new int[] {0xE4F2FF, 0x8FA8C8};
			case GRAVE -> new int[] {0xD8DCE6, 0x8C7CFF};
			case THUNDER -> new int[] {0xF4F8FF, 0x6F8CFF};
			case SHADOW -> new int[] {0x2A1638, 0xFF2A4A};
			case KATANA -> new int[] {0xFFFFFF, 0xFF7FB3};
			case JACKPOT -> new int[] {0xFFF1A8, 0xFFB52E};
		};
	}

	/** The colour shown for an effect on cards (or the chosen one). */
	public static int accent(Kind kind, Settings s) {
		return AuraEffects.color(defaults(kind), s, 0.7f, 0, 0);
	}

	/** How long an effect runs (its last particles may linger a little longer). */
	public static float duration(Kind kind) {
		return switch (kind) {
			case PHOENIX, REAPER, SUPERNOVA, GRAVE, TORNADO -> 2.6f;
			case BLACKHOLE -> 2.4f;
			default -> 2.2f;
		};
	}

	/** Where a preview camera should look (blocks up) and how much further back than usual it stands - for tall effects. */
	public static float[] previewFraming(Kind kind) {
		return switch (kind) {
			case PHOENIX -> new float[] {2.7f, 1.55f};
			case SUPERNOVA, TORNADO -> new float[] {1.6f, 1.3f};
			case JACKPOT, GRAVE -> new float[] {1.4f, 1f};
			default -> new float[] {1.6f, 1f};
		};
	}

	/** The moment that shows an effect best (seconds in) - for still pictures of it. */
	public static float highlight(Kind kind) {
		return switch (kind) {
			case SHATTER, KATANA -> 0.25f;
			case THUNDER -> 0.12f;
			case ORBITAL -> 0.55f;
			case SUPERNOVA, METEOR -> 0.65f;
			case REAPER -> 0.6f;
			case PHOENIX -> 1.6f;
			case GRAVE -> 1.2f;
			default -> 1.0f;
		};
	}

	private static float sin(float a) {
		return (float) Math.sin(a);
	}

	private static float cos(float a) {
		return (float) Math.cos(a);
	}

	private static float clamp01(float x) {
		return Math.max(0, Math.min(1, x));
	}

	private static float ease(float x) {
		x = clamp01(x);
		return x * x * (3 - 2 * x);
	}

	private static float easeOut(float x) {
		x = clamp01(x);
		return 1 - (1 - x) * (1 - x) * (1 - x);
	}

	/** 0 before {@code from}, then 1 fading to 0 over {@code length}. */
	private static float fadeAfter(float t, float from, float length) {
		return t < from ? 0 : clamp01(1 - (t - from) / length);
	}

	/** A particle, relative to the victim's feet. */
	private static final class P {
		float x;
		float y;
		float z;
		float vx;
		float vy;
		float vz;
		float age;
		float life;
		float size;
		float rot;
		float spin;
		float seed;
		int role;
		float gravity;
		float drag;
		boolean bounce;
	}

	/** Particle roles. */
	private static final int SHARD_BIT = 1;
	private static final int SPARK = 2;
	private static final int SMOKE_PUFF = 3;
	private static final int EMBER = 4;
	private static final int INFALL = 5;
	private static final int FEATHER_BIT = 6;
	private static final int PIXEL_BIT = 7;
	private static final int BINARY = 8;
	private static final int SNOW = 9;
	private static final int WISP = 10;
	private static final int CHAIN = 11;
	private static final int ROCK = 12;
	private static final int SPIRAL = 13;
	private static final int DIRT = 14;
	private static final int FOG = 15;
	private static final int DARK = 16;
	private static final int PETAL_BIT = 17;
	private static final int COIN_BIT = 18;

	/** One playing kill effect. Not thread-safe - one thread updates and draws it. */
	public static final class Burst {
		private final Kind kind;
		private final Random random;
		private final long seed;
		private final List<P> particles = new ArrayList<>();
		private float time;
		private float before;
		/** Towards whoever watches (x, z), for things that face you (gravestone, slashes, 7-7-7). */
		private float faceX = 0;
		private float faceZ = 1;

		public Burst(Kind kind, long seed) {
			this.kind = kind;
			this.seed = seed;
			this.random = new Random(seed);
		}

		public Kind kind() {
			return kind;
		}

		/** Turns the parts that face the viewer towards (dx, dz) from the victim. */
		public Burst facing(double dx, double dz) {
			double l = Math.sqrt(dx * dx + dz * dz);
			if (l > 1e-4) {
				faceX = (float) (dx / l);
				faceZ = (float) (dz / l);
			}
			return this;
		}

		/**
		 * A card facing the viewer, centred at (x, y, z), {@code w} x {@code h}, turned by {@code rot} in its plane;
		 * {@code mirrored} for the atlas cells drawn mirrored (the code glyphs).
		 */
		private void card(Out out, float x, float y, float z, float w, float h, float rot, int cell, int rgb, float alpha) {
			card(out, x, y, z, w, h, rot, cell, rgb, alpha, false);
		}

		private void card(Out out, float x, float y, float z, float w, float h, float rot, int cell, int rgb, float alpha, boolean mirrored) {
			// The viewer's right and up.
			float rx = faceZ * (mirrored ? -1 : 1);
			float rz = -faceX * (mirrored ? -1 : 1);
			float c = cos(rot);
			float sn = sin(rot);
			// Half-axes across (u) and up (v), turned in the card's plane.
			float ux = rx * c * w / 2;
			float uy = sn * w / 2;
			float uz = rz * c * w / 2;
			float vx = -rx * sn * h / 2;
			float vy = c * h / 2;
			float vz = -rz * sn * h / 2;
			out.quad(new float[] {x - ux + vx, y - uy + vy, z - uz + vz, x - ux - vx, y - uy - vy, z - uz - vz, x + ux - vx, y + uy - vy, z + uz - vz,
					x + ux + vx, y + uy + vy, z + uz + vz}, cell, rgb, alpha);
		}

		public float time() {
			return time;
		}

		public boolean done() {
			return time > duration(kind) + 0.6f || time > duration(kind) && particles.isEmpty();
		}

		private float rnd(float min, float max) {
			return min + random.nextFloat() * (max - min);
		}

		/** True once, on the step that passes {@code mark}. */
		private boolean at(float mark) {
			return before < mark && time >= mark || mark == 0 && before == 0 && time > 0 && !started;
		}

		private boolean started;

		private P add(float x, float y, float z, float life, float size, int role) {
			P p = new P();
			p.x = x;
			p.y = y;
			p.z = z;
			p.life = life;
			p.size = size;
			p.role = role;
			p.seed = random.nextFloat();
			if (particles.size() < 600) {
				particles.add(p);
			}
			return p;
		}

		private int color(Settings s, float f, float hue) {
			return AuraEffects.color(defaults(kind), s, f, hue, time);
		}

		/** Advances by {@code dt} seconds. */
		public void update(float dt, Settings s) {
			if (dt <= 0) {
				return;
			}
			before = time;
			time += dt;
			timeline(s);
			started = true;
			for (int i = particles.size() - 1; i >= 0; i--) {
				P p = particles.get(i);
				p.age += dt;
				if (p.age >= p.life) {
					particles.remove(i);
					continue;
				}
				move(p, dt);
			}
		}

		private void move(P p, float t) {
			if (p.role == INFALL) {
				// Pulled into the middle (1 block up), faster as it gets closer, swirling round.
				float dx = -p.x;
				float dy = 1.0f - p.y;
				float dz = -p.z;
				float d = Math.max(0.05f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
				if (d < 0.12f) {
					p.age = p.life;
					return;
				}
				float pull = (kind == Kind.SUPERNOVA ? 9 : 5) / (0.3f + d);
				p.vx += (dx / d * pull - dz / d * 2.5f) * t;
				p.vy += dy / d * pull * t;
				p.vz += (dz / d * pull + dx / d * 2.5f) * t;
				p.vx *= 1 - 1.2f * t;
				p.vy *= 1 - 1.2f * t;
				p.vz *= 1 - 1.2f * t;
			}
			if (p.role == WISP) {
				// Spiralling up round the middle.
				float w = 2.2f * t;
				float x = p.x * cos(w) - p.z * sin(w);
				p.z = p.x * sin(w) + p.z * cos(w);
				p.x = x;
			}
			if (p.role == SPIRAL) {
				// Whirled round and up the funnel, held at its width for that height.
				float r = (float) Math.sqrt(p.x * p.x + p.z * p.z);
				float a = (float) Math.atan2(p.z, p.x) + 7 * t;
				float want = 0.25f + 0.4f * p.y;
				if (time > 1.9f) {
					want += (time - 1.9f) * 4;
				}
				r += (want - r) * Math.min(1, 3 * t);
				p.x = r * cos(a);
				p.z = r * sin(a);
				p.y += p.vy * t;
				p.rot += p.spin * t;
				return;
			}
			if (p.role == PETAL_BIT && p.age > 0.6f) {
				p.vx += sin(p.age * 2.3f + p.seed * 20) * 0.6f * t;
				p.vz += cos(p.age * 1.9f + p.seed * 13) * 0.6f * t;
			}
			if (p.role == SNOW || p.role == FEATHER_BIT) {
				p.vx = sin(p.age * 2.1f + p.seed * 20) * 0.25f;
				p.vz = cos(p.age * 1.7f + p.seed * 13) * 0.25f;
			}
			if (p.role == PIXEL_BIT && p.age < p.spin) {
				// Glitch pixels hold still until their delay runs out.
				return;
			}
			p.vy -= p.gravity * t;
			if (p.drag > 0) {
				float k = Math.max(0, 1 - p.drag * t);
				p.vx *= k;
				p.vy *= k;
				p.vz *= k;
			}
			p.x += p.vx * t;
			p.y += p.vy * t;
			p.z += p.vz * t;
			if (p.bounce && p.y < 0.02f && p.vy < 0) {
				p.y = 0.02f;
				p.vy = -p.vy * 0.35f;
				p.vx *= 0.6f;
				p.vz *= 0.6f;
				p.spin *= 0.5f;
			}
			if (p.role == SHARD_BIT || p.role == FEATHER_BIT || p.role == ROCK || p.role == PETAL_BIT) {
				p.rot += p.spin * t + (p.role == ROCK && p.y > 0.05f ? 6 * t : 0);
			}
		}

		// ---- Timelines: what spawns when ----

		private void timeline(Settings s) {
			switch (kind) {
				case SHATTER -> {
					if (at(0)) {
						for (int k = 0; k < 60; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0, 0.3f);
							P p = add(r * cos(a), rnd(0.1f, 1.8f), r * sin(a), rnd(1.6f, 2.3f), rnd(0.1f, 0.22f), SHARD_BIT);
							float v = rnd(1.6f, 3.8f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(1f, 3.8f);
							p.gravity = 9.8f;
							p.bounce = true;
							p.drag = 0.4f;
							p.spin = rnd(-14, 14);
							p.rot = rnd(0, TAU);
						}
						sparks(30, 0.9f, 3.5f, 0.9f);
					}
				}
				case ORBITAL -> {
					if (at(0.38f)) {
						sparks(50, 0.1f, 6f, 1.3f);
						for (int k = 0; k < 18; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0.2f, 1.4f);
							P p = add(r * cos(a), 0.15f, r * sin(a), rnd(1.4f, 2.2f), rnd(0.35f, 0.6f), SMOKE_PUFF);
							p.vy = rnd(0.3f, 0.8f);
							p.vx = cos(a) * rnd(0.3f, 0.8f);
							p.vz = sin(a) * rnd(0.3f, 0.8f);
							p.drag = 1.2f;
							p.rot = rnd(0, TAU);
						}
					}
					if (time > 0.38f && time < 1.4f) {
						embers(s, 30, 1.2f);
					}
				}
				case BLACKHOLE -> {
					if (time < 1.6f) {
						for (int k = 0; k < (int) (time * 120) - (int) (before * 120); k++) {
							float u = rnd(-1, 1);
							float a = rnd(0, TAU);
							float rr = (float) Math.sqrt(1 - u * u);
							float d = rnd(2.2f, 3.4f);
							P p = add(rr * cos(a) * d, 1.0f + u * d * 0.6f, rr * sin(a) * d, 2f, rnd(0.04f, 0.08f), INFALL);
							p.vx = -rr * sin(a) * 1.2f;
							p.vz = rr * cos(a) * 1.2f;
						}
					}
					if (at(1.75f)) {
						sparks(40, 1.0f, 5f, 1.2f);
					}
				}
				case PHOENIX -> {
					if (at(0)) {
						for (int k = 0; k < 30; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.2f, 0.2f), rnd(0.1f, 0.6f), rnd(-0.2f, 0.2f), rnd(0.6f, 1.1f), rnd(0.1f, 0.2f), EMBER);
							float v = rnd(1f, 2.5f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(0.5f, 2f);
							p.drag = 2f;
						}
					}
					if (at(PHOENIX_RISE)) {
						sparks(60, 1.3f, 6f, 1.1f);
					}
					if (time > PHOENIX_RISE && time < PHOENIX_GONE) {
						// Embers and now and then a burning feather falling from the bird.
						float[] b = phoenixAt(time);
						int n = (int) (time * 70) - (int) (before * 70);
						for (int k = 0; k < n; k++) {
							P p = add(b[0] + rnd(-0.9f, 0.9f), b[1] + rnd(-0.6f, 0.3f), b[2] + rnd(-0.9f, 0.9f), rnd(0.6f, 1.2f), rnd(0.05f, 0.11f), EMBER);
							p.vy = -rnd(0.2f, 0.9f);
							p.drag = 1f;
						}
						if ((int) (time * 7) != (int) (before * 7)) {
							P p = add(b[0] + rnd(-0.8f, 0.8f), b[1], b[2] + rnd(-0.8f, 0.8f), rnd(1.6f, 2.2f), rnd(0.14f, 0.2f), FEATHER_BIT);
							p.vy = -0.7f;
							p.spin = rnd(-3, 3);
						}
					}
					if (at(PHOENIX_GONE)) {
						// It bursts into sparks high above.
						float[] b = phoenixAt(PHOENIX_GONE);
						for (int k = 0; k < 70; k++) {
							float u = rnd(-1, 1);
							float a = rnd(0, TAU);
							float rr = (float) Math.sqrt(1 - u * u);
							P p = add(b[0], b[1], b[2], rnd(0.7f, 1.3f), rnd(0.05f, 0.09f), SPARK);
							float v = rnd(2f, 5f);
							p.vx = rr * cos(a) * v;
							p.vy = u * v;
							p.vz = rr * sin(a) * v;
							p.gravity = 3f;
							p.drag = 1.4f;
						}
					}
				}
				case REAPER -> {
					if (time > 0.3f && time < 2.2f) {
						int n = (int) (time * 18) - (int) (before * 18);
						for (int k = 0; k < n; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0.5f, 1.1f);
							P p = add(r * cos(a), rnd(0, 0.4f), r * sin(a), rnd(1.4f, 2f), rnd(0.05f, 0.09f), WISP);
							p.vy = rnd(0.8f, 1.4f);
						}
					}
					if (at(0.95f)) {
						// The chains snap: their links fall.
						for (int c = 0; c < 2; c++) {
							for (int k = 0; k < 7; k++) {
								float[] q = chainPoint(c, k / 7f, 0.95f);
								P p = add(q[0], q[1], q[2], rnd(0.8f, 1.2f), 0.06f, CHAIN);
								p.vx = rnd(-0.8f, 0.8f);
								p.vy = rnd(0.5f, 1.5f);
								p.vz = rnd(-0.8f, 0.8f);
								p.gravity = 9f;
								p.bounce = true;
								p.rot = c * 0.5f + k * 0.4f;
							}
						}
					}
				}
				case GLITCH -> {
					if (at(0)) {
						// The body as pixels: legs, body, arms, head.
						float[][] boxes = {{-0.25f, 0, -0.12f, 0.25f, 0.75f, 0.12f}, {-0.25f, 0.75f, -0.12f, 0.25f, 1.5f, 0.12f},
								{-0.5f, 0.75f, -0.12f, -0.25f, 1.5f, 0.12f}, {0.25f, 0.75f, -0.12f, 0.5f, 1.5f, 0.12f}, {-0.25f, 1.5f, -0.25f, 0.25f, 2f, 0.25f}};
						for (float[] b : boxes) {
							for (float x = b[0] + 0.0625f; x < b[3]; x += 0.125f) {
								for (float y = b[1] + 0.0625f; y < b[4]; y += 0.125f) {
									for (float z = b[2] + 0.0625f; z < b[5]; z += 0.125f) {
										if (random.nextFloat() > 0.55f) {
											continue;
										}
										P p = add(x, y, z, rnd(1.4f, 2.1f), 0.06f, PIXEL_BIT);
										// Top first: the body dissolves from the head down.
										p.spin = (2.1f - y) * 0.35f + rnd(0, 0.15f);
										p.vx = rnd(-0.5f, 0.5f);
										p.vy = rnd(0.6f, 1.6f);
										p.vz = rnd(-0.5f, 0.5f);
										p.life += p.spin;
									}
								}
							}
						}
					}
					if (time < 1.6f) {
						int n = (int) (time * 26) - (int) (before * 26);
						for (int k = 0; k < n; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0.5f, 1.1f);
							P p = add(r * cos(a), rnd(1.8f, 2.6f), r * sin(a), rnd(0.8f, 1.2f), 0.1f, BINARY);
							p.vy = -rnd(1.5f, 2.5f);
							p.rot = a;
						}
					}
				}
				case SUPERNOVA -> {
					if (time < 0.45f) {
						int n = (int) (time * 220) - (int) (before * 220);
						for (int k = 0; k < n; k++) {
							float u = rnd(-1, 1);
							float a = rnd(0, TAU);
							float rr = (float) Math.sqrt(1 - u * u);
							float d = rnd(1.4f, 2.2f);
							add(rr * cos(a) * d, 1.0f + u * d * 0.7f, rr * sin(a) * d, 0.5f, rnd(0.04f, 0.07f), INFALL);
						}
					}
					if (at(0.45f)) {
						sparks(70, 1.0f, 7f, 1.6f);
						for (int k = 0; k < 14; k++) {
							float u = rnd(-0.6f, 0.6f);
							float a = rnd(0, TAU);
							P p = add(0, 1, 0, rnd(1.6f, 2.2f), rnd(0.5f, 0.9f), SMOKE_PUFF);
							float v = rnd(1.2f, 2.4f);
							p.vx = cos(a) * v;
							p.vy = u * v;
							p.vz = sin(a) * v;
							p.drag = 2f;
							p.rot = rnd(0, TAU);
						}
					}
				}
				case FROSTBITE -> {
					if (at(1.3f)) {
						for (int k = 0; k < 34; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0.1f, 1.2f);
							P p = add(r * cos(a), rnd(0.1f, 1.8f), r * sin(a), rnd(1.2f, 1.8f), rnd(0.06f, 0.14f), SHARD_BIT);
							float v = rnd(1.5f, 4f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(1f, 3f);
							p.gravity = 9.8f;
							p.bounce = true;
							p.drag = 0.5f;
							p.spin = rnd(-12, 12);
							p.rot = rnd(0, TAU);
						}
						for (int k = 0; k < 10; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.4f, 0.4f), rnd(0.2f, 1.6f), rnd(-0.4f, 0.4f), rnd(1.4f, 2f), rnd(0.4f, 0.7f), SMOKE_PUFF);
							p.vx = cos(a) * rnd(0.4f, 1f);
							p.vz = sin(a) * rnd(0.4f, 1f);
							p.vy = 0.2f;
							p.drag = 1.5f;
							p.rot = rnd(0, TAU);
						}
						sparks(30, 1.0f, 3f, 0.8f);
					}
					int n = (int) (time * 20) - (int) (before * 20);
					for (int k = 0; k < n && time < 2.4f; k++) {
						float a = rnd(0, TAU);
						float r = rnd(0, 1.4f);
						P p = add(r * cos(a), rnd(2f, 2.6f), r * sin(a), rnd(1.8f, 2.6f), rnd(0.03f, 0.05f), SNOW);
						p.vy = -rnd(0.4f, 0.7f);
					}
				}
				case METEOR -> {
					if (time < METEOR_HIT) {
						float[] m = meteorAt(time);
						int n = (int) (time * 90) - (int) (before * 90);
						for (int k = 0; k < n; k++) {
							P p = add(m[0] + rnd(-0.15f, 0.15f), m[1] + rnd(-0.15f, 0.15f), m[2] + rnd(-0.15f, 0.15f), rnd(0.4f, 0.9f), rnd(0.08f, 0.16f), EMBER);
							p.vy = rnd(-0.3f, 0.3f);
							p.drag = 1f;
						}
						if ((int) (time * 30) != (int) (before * 30)) {
							P p = add(m[0], m[1], m[2], rnd(0.9f, 1.4f), rnd(0.3f, 0.5f), DARK);
							p.vy = 0.2f;
							p.rot = rnd(0, TAU);
						}
					}
					if (at(METEOR_HIT)) {
						for (int k = 0; k < 34; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.3f, 0.3f), 0.3f, rnd(-0.3f, 0.3f), rnd(1.4f, 2.1f), rnd(0.06f, 0.13f), ROCK);
							float v = rnd(2f, 5f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(2.5f, 6f);
							p.gravity = 11f;
							p.bounce = true;
							p.rot = rnd(0, TAU);
						}
						sparks(50, 0.3f, 6f, 1.2f);
						for (int k = 0; k < 18; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.4f, 0.4f), rnd(0.2f, 0.8f), rnd(-0.4f, 0.4f), rnd(1.5f, 2.2f), rnd(0.45f, 0.8f), DARK);
							p.vx = cos(a) * rnd(0.6f, 1.6f);
							p.vz = sin(a) * rnd(0.6f, 1.6f);
							p.vy = rnd(0.5f, 1.4f);
							p.drag = 1.6f;
							p.rot = rnd(0, TAU);
						}
					}
				}
				case TORNADO -> {
					if (time < 2.0f) {
						int n = (int) (time * 45) - (int) (before * 45);
						for (int k = 0; k < n; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0.3f, 1.1f);
							P p = add(r * cos(a), 0.05f, r * sin(a), rnd(1.3f, 2f), rnd(0.04f, 0.09f), SPIRAL);
							p.vy = rnd(1.2f, 2.2f);
							p.rot = rnd(0, TAU);
							p.spin = rnd(-8, 8);
						}
						if ((int) (time * 14) != (int) (before * 14)) {
							float a = rnd(0, TAU);
							P p = add(0.6f * cos(a), 0.1f, 0.6f * sin(a), rnd(1f, 1.5f), rnd(0.35f, 0.55f), FOG);
							p.vx = cos(a) * 0.8f;
							p.vz = sin(a) * 0.8f;
							p.drag = 1.2f;
							p.rot = rnd(0, TAU);
						}
					}
				}
				case GRAVE -> {
					if (at(0)) {
						for (int k = 0; k < 30; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.3f, 0.3f), 0.05f, rnd(-0.3f, 0.3f), rnd(0.8f, 1.3f), rnd(0.04f, 0.08f), DIRT);
							float v = rnd(0.5f, 1.8f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(1.5f, 3.5f);
							p.gravity = 9.8f;
							p.bounce = true;
						}
					}
					if (time < 2.2f && (int) (time * 8) != (int) (before * 8)) {
						float a = rnd(0, TAU);
						float r = rnd(0.3f, 1.2f);
						P p = add(r * cos(a), 0.1f, r * sin(a), rnd(1.6f, 2.2f), rnd(0.4f, 0.7f), FOG);
						p.vx = rnd(-0.2f, 0.2f);
						p.vz = rnd(-0.2f, 0.2f);
						p.rot = rnd(0, TAU);
					}
				}
				case THUNDER -> {
					for (int i = 0; i < 3; i++) {
						if (at(STRIKES[i])) {
							float[] g = strikeAt(i);
							sparksAt(g[0], g[1], 26, 4.5f, 0.9f);
						}
					}
				}
				case SHADOW -> {
					if (time < 1.7f) {
						int n = (int) (time * 16) - (int) (before * 16);
						for (int k = 0; k < n; k++) {
							float a = rnd(0, TAU);
							float r = rnd(0, 0.8f);
							P p = add(r * cos(a), rnd(0, 0.5f), r * sin(a), rnd(1.2f, 1.8f), rnd(0.45f, 0.8f), DARK);
							p.vy = rnd(0.3f, 0.8f);
							p.vx = cos(a) * 0.3f;
							p.vz = sin(a) * 0.3f;
							p.drag = 0.8f;
							p.rot = rnd(0, TAU);
						}
					}
				}
				case KATANA -> {
					if (at(0.34f)) {
						for (int k = 0; k < 75; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.25f, 0.25f), rnd(0.2f, 1.8f), rnd(-0.25f, 0.25f), rnd(1.9f, 2.5f), rnd(0.06f, 0.1f), PETAL_BIT);
							float v = rnd(1f, 3.2f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(0.5f, 2.5f);
							p.gravity = 1.2f;
							p.drag = 1.8f;
							p.rot = rnd(0, TAU);
							p.spin = rnd(-6, 6);
						}
					}
				}
				case JACKPOT -> {
					if (time > 0.3f && time < 1.3f) {
						int n = (int) (time * 60) - (int) (before * 60);
						for (int k = 0; k < n; k++) {
							float a = rnd(0, TAU);
							P p = add(rnd(-0.15f, 0.15f), 1.2f, rnd(-0.15f, 0.15f), rnd(1.4f, 2f), rnd(0.09f, 0.13f), COIN_BIT);
							float v = rnd(0.4f, 1.8f);
							p.vx = cos(a) * v;
							p.vz = sin(a) * v;
							p.vy = rnd(3.5f, 5.5f);
							p.gravity = 9.8f;
							p.bounce = true;
							p.spin = rnd(8, 16);
						}
					}
					if (at(0.3f)) {
						sparks(40, 1.2f, 4f, 1f);
					}
				}
			}
		}

		private void sparks(int n, float y, float speed, float life) {
			for (int k = 0; k < n; k++) {
				float u = rnd(-0.4f, 1f);
				float a = rnd(0, TAU);
				float rr = (float) Math.sqrt(1 - u * u);
				P p = add(0, y, 0, rnd(life * 0.6f, life), rnd(0.04f, 0.08f), SPARK);
				float v = rnd(speed * 0.4f, speed);
				p.vx = rr * cos(a) * v;
				p.vy = u * v;
				p.vz = rr * sin(a) * v;
				p.gravity = 4f;
				p.drag = 1.4f;
			}
		}

		private void embers(Settings s, int perSecond, float radius) {
			int n = (int) (time * perSecond) - (int) (before * perSecond);
			for (int k = 0; k < n; k++) {
				float a = rnd(0, TAU);
				float r = rnd(0, radius);
				P p = add(r * cos(a), rnd(0, 0.3f), r * sin(a), rnd(0.8f, 1.4f), rnd(0.03f, 0.06f), EMBER);
				p.vy = rnd(0.8f, 1.8f);
			}
		}

		// ---- Drawing ----

		/** Draws the effect around the victim's feet. */
		public void draw(Settings s, Out out) {
			switch (kind) {
				case SHATTER -> drawShatter(s, out);
				case ORBITAL -> drawOrbital(s, out);
				case BLACKHOLE -> drawBlackHole(s, out);
				case PHOENIX -> drawPhoenix(s, out);
				case REAPER -> drawReaper(s, out);
				case GLITCH -> drawGlitch(s, out);
				case SUPERNOVA -> drawSupernova(s, out);
				case FROSTBITE -> drawFrostbite(s, out);
				case METEOR -> drawMeteor(s, out);
				case TORNADO -> drawTornado(s, out);
				case GRAVE -> drawGrave(s, out);
				case THUNDER -> drawThunder(s, out);
				case SHADOW -> drawShadow(s, out);
				case KATANA -> drawKatana(s, out);
				case JACKPOT -> drawJackpot(s, out);
			}
			for (P p : particles) {
				drawParticle(p, s, out);
			}
		}

		private void drawParticle(P p, Settings s, Out out) {
			float f = p.age / p.life;
			float fade = clamp01(Math.min(p.age * 8, (p.life - p.age) * 2.5f));
			switch (p.role) {
				case SHARD_BIT -> {
					int c = color(s, p.seed * 0.8f, p.seed);
					out.sprite(p.x, p.y, p.z, p.size * 1.8f, c, fade * 0.18f, GLOW);
					out.sprite(p.x, p.y, p.z, p.size, mix(c, 0xFFFFFF, 0.2f), fade, SHARD | UPRIGHT, p.rot);
				}
				case SPARK -> {
					int c = mix(0xFFFFFF, color(s, 0.4f + 0.6f * f, p.seed), Math.min(1, p.age * 4));
					out.beam(p.x, p.y, p.z, p.x - p.vx * 0.05f, p.y - p.vy * 0.05f, p.z - p.vz * 0.05f, p.size * 0.7f, c, (1 - f) * 0.6f);
					out.sprite(p.x, p.y, p.z, p.size * (1 - 0.5f * f), c, 1 - f, SPARKLE);
				}
				case SMOKE_PUFF -> {
					int c = kind == Kind.ORBITAL ? mix(color(s, 0.6f, p.seed), 0x1A1A1E, 0.8f)
							: kind == Kind.FROSTBITE ? mix(color(s, 0f, p.seed), 0xFFFFFF, 0.5f) : color(s, 0.4f + 0.6f * f, p.seed);
					float alpha = kind == Kind.ORBITAL ? 0.55f : 0.3f;
					out.sprite(p.x, p.y, p.z, p.size * (0.6f + 0.8f * f), c, fade * alpha * (1 - f), SMOKE, p.rot + p.age * 0.5f);
				}
				case EMBER -> {
					int c = color(s, Math.min(1, f * 1.3f), p.seed);
					out.sprite(p.x, p.y, p.z, p.size * (1 - 0.5f * f), c, (float) Math.pow(1 - f, 1.2) * (0.7f + 0.3f * sin(p.age * 30 + p.seed * 40)), GLOW);
				}
				case INFALL -> {
					int c = color(s, 0.3f + 0.5f * p.seed, p.seed);
					float a = clamp01(p.age * 5);
					out.beam(p.x, p.y, p.z, p.x - p.vx * 0.06f, p.y - p.vy * 0.06f, p.z - p.vz * 0.06f, p.size * 0.8f, c, a * 0.55f);
					out.sprite(p.x, p.y, p.z, p.size, mix(c, 0xFFFFFF, 0.3f), a, GLOW);
				}
				case FEATHER_BIT -> card(out, p.x, p.y, p.z, p.size, p.size * 2, p.rot, AuraEffects.cell(FEATHER), color(s, 0.3f, p.seed), fade);
				case PIXEL_BIT -> {
					boolean going = p.age >= p.spin;
					float g = going ? (p.age - p.spin) / (p.life - p.spin) : 0;
					// Snapped to a coarse grid, jittering - and split into its colours.
					float jx = going && ((int) (p.age * 20 + p.seed * 7)) % 3 == 0 ? 0.04f : 0;
					float a = going ? 1 - g : 1;
					float split = 0.025f + 0.04f * g;
					int c0 = color(s, 0f, p.seed);
					int c1 = color(s, 1f, p.seed);
					out.sprite(p.x + jx - split, p.y, p.z, p.size, c0, a * 0.7f, PIXEL);
					out.sprite(p.x + jx + split, p.y, p.z, p.size, c1, a * 0.7f, PIXEL);
					out.sprite(p.x + jx, p.y, p.z, p.size * 0.8f, 0xFFFFFF, a * (going ? 0.5f : 0.85f), PIXEL);
				}
				case BINARY -> {
					int g = ((int) (p.seed * 2 + p.age * 8)) % 2;
					float nx = cos(p.rot);
					float nz = sin(p.rot);
					out.panel(p.x, p.y, p.z, 0.14f, 0.14f, nx, nz, CELL_GLYPH + g, color(s, p.seed, p.seed), fade * 0.9f);
					out.panel(p.x, p.y, p.z, 0.14f, 0.14f, -nz, nx, CELL_GLYPH + g, color(s, p.seed, p.seed), fade * 0.7f);
				}
				case SNOW -> out.sprite(p.x, p.y, p.z, p.size, color(s, 0.1f, p.seed), fade * 0.9f, p.seed < 0.4f ? STAR : GLOW);
				case WISP -> out.sprite(p.x, p.y, p.z, p.size * (1 - 0.4f * f), color(s, 0.3f + 0.6f * f, p.seed), fade * (0.6f + 0.4f * sin(p.age * 12 + p.seed * 30)), GLOW);
				case CHAIN -> out.sprite(p.x, p.y, p.z, p.size, color(s, 0.6f, 0), fade * 0.9f, HALO | UPRIGHT, p.rot);
				case ROCK -> {
					// Glowing hot as it flies, cooling to dark rock.
					int c = mix(mix(color(s, 0.5f, p.seed), 0xFFFFFF, 0.2f), 0x2A2420, clamp01(p.age * 1.4f));
					out.sprite(p.x, p.y, p.z, p.size, c, fade, PIXEL, p.rot);
					if (p.age < 0.5f) {
						out.sprite(p.x, p.y, p.z, p.size * 2.5f, color(s, 0.8f, p.seed), 0.4f * (1 - p.age * 2), GLOW);
					}
				}
				case SPIRAL -> out.sprite(p.x, p.y, p.z, p.size, mix(color(s, 0.6f, p.seed), 0x5A5048, 0.4f), fade, PIXEL, p.rot);
				case DIRT -> out.sprite(p.x, p.y, p.z, p.size, 0x4A3826, fade, PIXEL, p.rot);
				case FOG -> out.sprite(p.x, p.y, p.z, p.size * (0.7f + 0.6f * f), mix(color(s, 0f, p.seed), 0xFFFFFF, 0.3f), fade * 0.22f * (1 - f),
						SMOKE, p.rot + p.age * 0.3f);
				case DARK -> out.sprite(p.x, p.y, p.z, p.size * (0.6f + 0.7f * f), mix(color(s, 0f, p.seed), 0x000000, kind == Kind.METEOR ? 0.75f : 0.3f),
						fade * 0.65f * (1 - f * 0.7f), SMOKE, p.rot + p.age * 0.4f);
				case PETAL_BIT -> out.sprite(p.x, p.y, p.z, p.size, color(s, 0.3f + 0.7f * p.seed, p.seed), fade, PETAL | UPRIGHT, p.rot);
				case COIN_BIT -> {
					// Spinning: seen edge-on now and then.
					float flip = Math.abs(cos(p.age * p.spin));
					int c = mix(color(s, 1f, p.seed), color(s, 0f, p.seed), flip * 0.5f);
					card(out, p.x, p.y, p.z, p.size * 2 * Math.max(0.12f, flip), p.size * 2, 0, AuraEffects.cell(COIN), c, fade);
				}
				default -> {
				}
			}
		}

		/** A shockwave: a flat ring growing from r0 to r1 between t0 and t0 + length. */
		private void ring(Out out, float y, float t0, float length, float r0, float r1, int rgb, float alpha) {
			if (time < t0 || time > t0 + length) {
				return;
			}
			float f = (time - t0) / length;
			float r = r0 + (r1 - r0) * easeOut(f);
			out.sprite(0, y, 0, r / 0.74f, rgb, alpha * (float) Math.pow(1 - f, 1.3), HALO | FLAT);
		}

		/** A flash: a big glow fading out. */
		private void flash(Out out, float y, float t0, float length, float size, int rgb, float alpha) {
			float a = fadeAfter(time, t0, length);
			if (a > 0) {
				out.sprite(0, y, 0, size * (0.6f + 0.4f * (1 - a)), rgb, alpha * a, GLOW);
			}
		}

		private void drawShatter(Settings s, Out out) {
			flash(out, 0.9f, 0, 0.35f, 2.4f, mix(color(s, 0.2f, 0), 0xFFFFFF, 0.5f), 1f);
			float b = fadeAfter(time, 0, 0.45f);
			if (b > 0) {
				out.sprite(0, 0.9f, 0, 2.2f * (1.2f - b), mix(color(s, 0f, 0), 0xFFFFFF, 0.5f), b, BURST, time * 2);
			}
			ring(out, 0.05f, 0, 0.5f, 0.2f, 3.2f, color(s, 0.4f, 0), 0.95f);
			ring(out, 0.9f, 0.02f, 0.4f, 0.2f, 2.4f, color(s, 0f, 0), 0.7f);
			float g = fadeAfter(time, 0, 1.4f);
			out.sprite(0, 0.03f, 0, 1.6f, color(s, 0.8f, 0), 0.35f * g, DISC);
		}

		private void drawOrbital(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			float strike = 0.38f;
			if (time < strike) {
				// Locking on: a ring closing in, crosshair ticks blinking, a thin guide beam from the sky.
				float f = time / strike;
				float r = 1.6f - 1.1f * ease(f);
				float blink = (int) (time * 16) % 2 == 0 ? 1 : 0.5f;
				out.sprite(0, 0.05f, 0, r / 0.74f, c1, 0.9f * blink, HALO | FLAT);
				float spin = time * 4;
				for (int k = 0; k < 4; k++) {
					float a = spin + k * TAU / 4;
					out.beam((r + 0.1f) * cos(a), 0.05f, (r + 0.1f) * sin(a), (r + 0.45f) * cos(a), 0.05f, (r + 0.45f) * sin(a), 0.06f, c1, 0.9f * blink);
				}
				out.beam(0, 40, 0, 0, 0.05f, 0, 0.05f + 0.1f * f, c1, 0.25f + 0.5f * f);
				return;
			}
			float t = time - strike;
			// The beam: a wide glow round a white core, narrowing as it fades.
			float beam = fadeAfter(t, 0, 0.9f);
			if (beam > 0) {
				float wob = 1 + 0.08f * sin(t * 60);
				out.beam(0, 48, 0, 0, 0, 0, 2.2f * beam * wob, c1, 0.45f * beam);
				out.beam(0, 48, 0, 0, 0, 0, 0.9f * beam * wob, mix(c1, c0, 0.5f), 0.8f * beam);
				out.beam(0, 48, 0, 0, 0, 0, 0.35f * beam, 0xFFFFFF, beam);
			}
			flash(out, 0.3f, strike, 0.35f, 3.2f, mix(c1, 0xFFFFFF, 0.5f), 1f);
			ring(out, 0.05f, strike, 0.6f, 0.3f, 4.2f, mix(c1, 0xFFFFFF, 0.3f), 1f);
			ring(out, 0.06f, strike + 0.12f, 0.7f, 0.2f, 3f, c1, 0.7f);
			// The scorch: cracks glowing, then cooling.
			float scorch = t < 0.1f ? t / 0.1f : fadeAfter(t, 0.6f, 1.4f);
			out.sprite(0, 0.035f, 0, 1.7f, mix(c1, 0xFFB060, 0.3f), scorch, CRACKS | FLAT, 0.7f);
			out.sprite(0, 0.03f, 0, 1.5f, c1, 0.4f * scorch, DISC);
		}

		private void drawBlackHole(Settings s, Out out) {
			float grow = ease(time / 0.3f);
			float collapse = time < 1.6f ? 1 : 1 - ease((time - 1.6f) / 0.15f);
			float r = 0.55f * grow * collapse;
			int rim = color(s, 0.5f, 0);
			if (r > 0.01f) {
				// Glowing lensing halo, then the dark middle drawn over it.
				out.sprite(0, 1, 0, r * 3.2f, color(s, 1f, 0), 0.5f, GLOW);
				out.sprite(0, 1, 0, r * 1.4f / 0.74f, mix(rim, 0xFFFFFF, 0.3f), 0.9f, HALO | UPRIGHT, time * 0.5f);
				out.sprite(0, 1, 0, r * 1.25f, 0x050208, 0.97f, GLOW);
				out.sprite(0, 1, 0, r * 1.0f, 0x000000, 1f, GLOW);
				// The accretion disc: streaks racing round a tilted ring.
				int n = 48;
				for (int k = 0; k < n; k++) {
					float th = k * TAU / n + time * (6 + (k % 3));
					float rr = r * (1.8f + 0.5f * ((k * 7) % 5) / 4f);
					float x = rr * cos(th);
					float z = rr * sin(th);
					float y = 1 - z * 0.35f;
					float x2 = rr * cos(th - 0.25f);
					float z2 = rr * sin(th - 0.25f);
					int c = color(s, ((k * 3) % 7) / 6f, k / (float) n);
					out.beam(x, y, z * 0.94f, x2, 1 - z2 * 0.35f, z2 * 0.94f, 0.05f, c, 0.85f);
				}
			}
			out.sprite(0, 0.03f, 0, 2.2f, color(s, 1f, 0), 0.3f * grow * collapse, DISC);
			// The bang when it's gone.
			flash(out, 1, 1.75f, 0.35f, 2.6f, mix(rim, 0xFFFFFF, 0.6f), 1f);
			float b = fadeAfter(time, 1.75f, 0.5f);
			if (b > 0) {
				out.sprite(0, 1, 0, 1.8f * (1.3f - b), mix(rim, 0xFFFFFF, 0.4f), b, BURST, time);
			}
			ring(out, 1, 1.75f, 0.5f, 0.2f, 3f, rim, 0.9f);
			ring(out, 0.05f, 1.78f, 0.6f, 0.2f, 3.5f, rim, 0.7f);
		}

		private static final float PHOENIX_RISE = 0.45f;
		private static final float PHOENIX_GONE = 2.25f;

		/** Where the phoenix is at {@code t}: bursting out of the fire column, then soaring up. */
		private static float[] phoenixAt(float t) {
			float rise = easeOut((t - PHOENIX_RISE) / (PHOENIX_GONE - PHOENIX_RISE));
			return new float[] {0.12f * sin(t * 2.5f), 1.3f + rise * 3.4f, 0.12f * cos(t * 2.1f)};
		}

		/** One great wing: its root, which way it reaches ({@code w}, unit) and the wing's up ({@code h}). */
		private void phoenixWing(Out out, float[] root, float[] w, float[] h, float span, float len, int rgb, float alpha) {
			float tx = w[0] * span * 2;
			float ty = w[1] * span * 2;
			float tz = w[2] * span * 2;
			float ax = root[0] + h[0] * 0.65f * len;
			float ay = root[1] + h[1] * 0.65f * len;
			float az = root[2] + h[2] * 0.65f * len;
			float bx = root[0] - h[0] * 1.35f * len;
			float by = root[1] - h[1] * 1.35f * len;
			float bz = root[2] - h[2] * 1.35f * len;
			out.quad(new float[] {ax, ay, az, bx, by, bz, bx + tx, by + ty, bz + tz, ax + tx, ay + ty, az + tz}, AuraEffects.cell(BIRD_WING), rgb, alpha);
		}

		private void drawPhoenix(Settings s, Out out) {
			int hot = mix(color(s, 0f, 0), 0xFFFFFF, 0.35f);
			// Rebirth: a ring of flames and a spiral column of fire roaring up out of the body.
			ring(out, 0.05f, 0, 0.6f, 0.2f, 2.6f, color(s, 0.6f, 0), 0.9f);
			float ground = time < 0.15f ? time / 0.15f : fadeAfter(time, 0.7f, 1.2f);
			for (int k = 0; k < 16; k++) {
				float a = k * TAU / 16;
				float h = (0.2f + 0.09f * sin(time * 14 + k * 2)) * ground;
				out.sprite(0.75f * cos(a), h * 0.8f, 0.75f * sin(a), h, color(s, 0.3f + 0.4f * ((k % 3) / 2f), k / 16f), 0.9f * ground, FLAME | UPRIGHT);
			}
			out.sprite(0, 0.03f, 0, 1.8f, color(s, 0.8f, 0), 0.45f * ground, DISC);
			float column = time < PHOENIX_RISE ? easeOut(time / PHOENIX_RISE) : fadeAfter(time, PHOENIX_RISE, 0.35f);
			if (column > 0) {
				float height = 2.8f * (time < PHOENIX_RISE ? column : 1);
				for (int i = 0; i < 34; i++) {
					float f = i / 34f;
					float a = f * 11 + time * 9;
					float r = 0.5f * (1 - f * 0.45f);
					float flick = 0.85f + 0.15f * sin(time * 25 + i * 3);
					out.sprite(r * cos(a), f * height, r * sin(a), 0.32f * (1 - f * 0.4f) * flick, color(s, 0.2f + 0.6f * f, f), 0.9f * column, FLAME | UPRIGHT);
				}
				out.beam(0, 0, 0, 0, height, 0, 1.4f, color(s, 0.5f, 0), 0.35f * column);
				out.beam(0, 0, 0, 0, height * 0.9f, 0, 0.45f, hot, 0.8f * column);
			}
			flash(out, 1.4f, PHOENIX_RISE, 0.35f, 3f, hot, 1f);
			if (time < PHOENIX_RISE) {
				return;
			}
			float alpha = time < PHOENIX_GONE ? clamp01((time - PHOENIX_RISE) * 8) : fadeAfter(time, PHOENIX_GONE, 0.12f);
			if (alpha <= 0) {
				return;
			}
			float[] b = phoenixAt(time);
			float yaw = time * 0.9f;
			float sx = cos(yaw);
			float sz = sin(yaw);
			// Its fiery glow.
			out.sprite(b[0], b[1], b[2], 2.4f, color(s, 0.7f, 0), 0.3f * alpha, GLOW);
			// Wings beating: a quick down-stroke, a slower lift, mostly spread wide.
			float beat = time * 8.5f;
			float flap = 0.25f + 0.6f * sin(beat) * (sin(beat) > 0 ? 0.8f : 1.1f);
			float span = 1.25f;
			float len = 0.75f;
			float[] root = {b[0], b[1] + 0.15f, b[2]};
			for (int side = -1; side <= 1; side += 2) {
				float[] w = {sx * side * cos(flap), sin(flap), sz * side * cos(flap)};
				// The wing's up: straight up, kept square to where it reaches.
				float dot = w[1];
				float[] h = {-w[0] * dot, 1 - w[1] * dot, -w[2] * dot};
				float hl = (float) Math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]);
				h[0] /= hl;
				h[1] /= hl;
				h[2] /= hl;
				phoenixWing(out, root, w, h, span * 1.08f, len * 1.12f, color(s, 0.85f, 0), 0.35f * alpha);
				phoenixWing(out, root, w, h, span, len, color(s, 0.4f, 0), 0.95f * alpha);
				phoenixWing(out, root, w, h, span * 0.9f, len * 0.85f, hot, 0.35f * alpha);
				// Flames licking off the leading edge.
				for (int k = 2; k <= 7; k++) {
					float f = k / 7f;
					float px = root[0] + w[0] * span * 2 * f * 0.92f + h[0] * 0.18f * len;
					float py = root[1] + w[1] * span * 2 * f * 0.92f + h[1] * 0.18f * len;
					float pz = root[2] + w[2] * span * 2 * f * 0.92f + h[2] * 0.18f * len;
					float flick = 0.8f + 0.2f * sin(time * 22 + k * 3 + side);
					out.sprite(px, py + 0.06f, pz, (0.14f - 0.05f * f) * flick, color(s, 0.15f + 0.4f * f, 0), 0.75f * alpha, FLAME | UPRIGHT);
				}
			}
			// The tail: three broad streams of fire flowing down and swaying, a long feather at the end of each.
			for (int k = -1; k <= 1; k++) {
				float[] prev = {b[0], b[1] - 0.35f, b[2]};
				for (int j = 1; j <= 10; j++) {
					float f = j / 10f;
					float spread = k * 0.07f * j;
					float sway = 0.05f * j * sin(time * 4 + j * 0.45f + k * 1.3f);
					float[] q = {b[0] + sx * (spread + sway), b[1] - 0.35f - j * 0.16f, b[2] + sz * (spread + sway)};
					out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.55f * (1 - f * 0.5f), color(s, 0.55f + 0.35f * f, 0), 0.22f * alpha);
					out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.2f * (1 - f * 0.55f), mix(color(s, 0.25f + 0.5f * f, 0), 0xFFFFFF, 0.25f), 0.8f * alpha);
					if (j % 3 == 0) {
						out.sprite(q[0], q[1], q[2], 0.14f, color(s, 0.3f + 0.4f * f, 0), 0.7f * alpha, FLAME | UPRIGHT);
					}
					prev = q;
				}
				card(out, prev[0], prev[1] - 0.22f, prev[2], 0.26f, 0.5f, (float) Math.PI + k * 0.25f, AuraEffects.cell(FEATHER), color(s, 0.5f, 0), 0.95f * alpha);
				out.sprite(prev[0], prev[1] - 0.32f, prev[2], 0.1f, hot, 0.9f * alpha, GLOW);
			}
			// The body: a full chest tapering down, white-hot inside.
			for (int k = 0; k < 5; k++) {
				float f = k / 4f;
				out.sprite(b[0], b[1] + 0.3f - f * 0.75f, b[2], 0.5f - 0.2f * f, color(s, 0.2f + 0.3f * f, 0), 0.75f * alpha, GLOW);
			}
			out.beam(b[0], b[1] - 0.45f, b[2], b[0], b[1] + 0.35f, b[2], 0.3f, color(s, 0.25f, 0), 0.8f * alpha);
			out.beam(b[0], b[1] - 0.4f, b[2], b[0], b[1] + 0.32f, b[2], 0.14f, hot, alpha);
			// The head: a flame-shaped head with burning eyes, a crest of fire streaming back.
			float hy = b[1] + 0.62f;
			out.sprite(b[0], hy, b[2], 0.45f, color(s, 0.3f, 0), 0.45f * alpha, GLOW);
			card(out, b[0], hy + 0.05f, b[2], 0.32f, 0.42f, 0, AuraEffects.cell(FLAME), mix(color(s, 0.1f, 0), 0xFFFFFF, 0.3f), alpha);
			for (int side = -1; side <= 1; side += 2) {
				out.sprite(b[0] + faceZ * side * 0.06f + faceX * 0.12f, hy, b[2] - faceX * side * 0.06f + faceZ * 0.12f, 0.035f, 0xFFFFFF, alpha, SPARKLE);
			}
			for (int k = 0; k < 3; k++) {
				float lift = 0.18f + 0.1f * k;
				float wave = 0.05f * sin(time * 9 + k);
				float[] tip = {b[0] - faceX * (0.3f + 0.08f * k) + wave * faceZ, hy + lift, b[2] - faceZ * (0.3f + 0.08f * k) - wave * faceX};
				out.beam(b[0], hy + 0.1f, b[2], tip[0], tip[1], tip[2], 0.07f - 0.015f * k, color(s, 0.3f + 0.2f * k, 0), 0.85f * alpha);
				out.sprite(tip[0], tip[1], tip[2], 0.08f, color(s, 0.4f + 0.2f * k, 0), 0.8f * alpha, FLAME | UPRIGHT);
			}
		}

		/** A point on chain {@code c} at {@code f} (0 at the ground, 1 at the soul) at {@code t}. */
		private static float[] chainPoint(int c, float f, float t) {
			float a = c == 0 ? 0.6f : 0.6f + (float) Math.PI;
			float[] soul = soulAt(t);
			float gx = 1.1f * cos(a);
			float gz = 1.1f * sin(a);
			float sag = 0.25f * sin((float) Math.PI * f);
			return new float[] {gx + (soul[0] - gx) * f, 0.02f + (soul[1] - 0.02f) * f - sag, gz + (soul[2] - gz) * f};
		}

		private static float[] soulAt(float t) {
			float rise = t < 0.95f ? ease((t - 0.25f) / 0.7f) * 0.5f : 0.5f + easeOut((t - 0.95f) / 1.5f) * 2.6f;
			return new float[] {0.12f * sin(t * 5), 1.0f + Math.max(0, rise), 0};
		}

		private void drawReaper(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			// Two scythe slashes crossing.
			for (int k = 0; k < 2; k++) {
				float t0 = k * 0.13f;
				float f = (time - t0) / 0.3f;
				if (f >= 0 && f <= 1) {
					float rot = (k == 0 ? -1.4f : 1.4f) + (k == 0 ? 2.6f : -2.6f) * easeOut(f);
					out.sprite(0, 1.0f, 0, 1.75f, mix(c0, 0xFFFFFF, 0.4f), (1 - f) * 1.0f, SLASH | UPRIGHT, rot);
					out.sprite(0, 1.0f, 0, 1.95f, c1, (1 - f) * 0.6f, SLASH | UPRIGHT, rot - 0.1f);
				}
			}
			flash(out, 1, 0.05f, 0.3f, 1.6f, c1, 0.8f);
			// The rune circle on the ground.
			float g = clamp01(time * 4) * fadeAfter(time, 1.6f, 1f);
			out.sprite(0, 0.04f, 0, 1.2f / 0.74f, c1, 0.8f * g, HALO | FLAT);
			out.sprite(0, 0.045f, 0, 0.75f / 0.74f, c0, 0.6f * g, HALO | FLAT);
			out.sprite(0, 0.035f, 0, 1.4f, mix(c1, 0x0A1A12, 0.4f), 0.7f * g, CRACKS | FLAT, time * 0.3f);
			out.sprite(0, 0.03f, 0, 1.6f, c1, 0.3f * g, DISC);
			if (time < 0.25f) {
				return;
			}
			// The soul, held by two chains until they snap, then rising away.
			float[] soul = soulAt(time);
			float a = time < 2.1f ? clamp01((time - 0.25f) * 4) : fadeAfter(time, 2.1f, 0.5f);
			if (time < 0.95f) {
				for (int c = 0; c < 2; c++) {
					for (int k = 0; k < 7; k++) {
						float[] q = chainPoint(c, (k + 0.5f) / 7f, time);
						out.sprite(q[0], q[1], q[2], 0.06f, mix(c1, 0xFFFFFF, 0.2f), 0.9f, HALO | UPRIGHT, c * 0.5f + k * 0.4f);
					}
				}
			}
			for (int k = 1; k <= 6; k++) {
				float[] q = soulAt(time - k * 0.05f);
				out.sprite(q[0], q[1] - 0.15f, q[2], 0.25f + k * 0.04f, c1, 0.25f * a * (1 - k / 7f), SMOKE, k + time);
			}
			out.sprite(soul[0], soul[1], soul[2], 0.85f, c1, 0.35f * a, GLOW);
			out.sprite(soul[0], soul[1], soul[2], 0.55f, mix(c0, 0xFFFFFF, 0.3f), 0.95f * a, GHOST | UPRIGHT, 0.15f * sin(time * 4));
		}

		private void drawGlitch(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			flash(out, 1, 0, 0.12f, 1.4f, 0xFFFFFF, 0.8f);
			// Glitch bars flickering at random heights, scan rings sweeping up.
			Random bars = new Random(seed * 31 + (long) (time * 16));
			float on = time < 1.2f ? 1 : fadeAfter(time, 1.2f, 0.6f);
			for (int k = 0; k < 6; k++) {
				float y = bars.nextFloat() * 2.2f;
				float a = bars.nextFloat() * TAU;
				float len = 0.4f + bars.nextFloat() * 0.9f;
				float x0 = cos(a) * 0.6f;
				float z0 = sin(a) * 0.6f;
				out.beam(x0 - sin(a) * len, y, z0 + cos(a) * len, x0 + sin(a) * len, y, z0 - cos(a) * len, 0.05f, k % 2 == 0 ? c0 : c1, 0.8f * on);
			}
			for (int k = 0; k < 2; k++) {
				float f = (time * 1.4f + k * 0.5f) % 1f;
				out.sprite(0, f * 2.2f, 0, 0.7f / 0.74f, k == 0 ? c0 : c1, (float) Math.sin(Math.PI * f) * 0.8f * on, HALO | FLAT);
			}
			out.sprite(0, 0.03f, 0, 1.4f, c0, 0.25f * on, DISC);
		}

		private void drawSupernova(Settings s, Out out) {
			float boom = 0.45f;
			if (time < boom) {
				// The star: shrinking, pulsing hotter.
				float f = time / boom;
				float pulse = 0.85f + 0.15f * sin(time * 50);
				out.sprite(0, 1, 0, (0.7f - 0.45f * f) * pulse, mix(color(s, 0.2f, 0), 0xFFFFFF, 0.3f + 0.6f * f), 1f, GLOW);
				out.sprite(0, 1, 0, 1.4f * (1 - 0.5f * f), color(s, 0.6f, 0), 0.4f, GLOW);
				out.sprite(0, 1, 0, 0.5f + 0.3f * f, 0xFFFFFF, 0.6f * f, SPARKLE, time * 3);
				return;
			}
			float t = time - boom;
			flash(out, 1, boom, 0.5f, 4.5f, 0xFFFFFF, 1f);
			float b = fadeAfter(t, 0, 1f);
			if (b > 0) {
				out.sprite(0, 1, 0, 3.6f * easeOut(t / 0.4f), mix(color(s, 0.1f, 0), 0xFFFFFF, 0.4f), b, BURST, t * 0.6f);
			}
			// Rays shooting out in every direction.
			Random rays = new Random(seed);
			float reach = easeOut(t / 0.7f) * 4.5f;
			float ra = t < 0.15f ? 1 : fadeAfter(t, 0.15f, 0.9f);
			for (int k = 0; k < 36 && ra > 0; k++) {
				float u = rays.nextFloat() * 2 - 1;
				float a = rays.nextFloat() * TAU;
				float rr = (float) Math.sqrt(1 - u * u);
				float len = reach * (0.6f + 0.4f * rays.nextFloat());
				float dx = rr * cos(a);
				float dz = rr * sin(a);
				out.beam(dx * len * 0.3f, 1 + u * len * 0.3f, dz * len * 0.3f, dx * len, 1 + u * len, dz * len, 0.08f, color(s, rays.nextFloat(), k), 0.8f * ra);
			}
			// Shock shells: a flat ring and two crossed upright ones.
			ring(out, 1, boom, 0.8f, 0.3f, 4.5f, color(s, 0.3f, 0), 1f);
			ring(out, 0.05f, boom + 0.1f, 0.9f, 0.3f, 5f, color(s, 0.7f, 0), 0.8f);
			if (t < 0.7f) {
				float f = t / 0.7f;
				out.sprite(0, 1, 0, (0.3f + 3.5f * easeOut(f)) / 0.74f, color(s, 0.5f, 0), 0.7f * (1 - f), HALO | UPRIGHT, t);
			}
			// The glowing remnant.
			float rem = t < 0.3f ? 1 : fadeAfter(t, 0.3f, 1.8f);
			out.sprite(0, 1, 0, 0.8f, color(s, 0.9f, 0), 0.6f * rem, GLOW);
			out.sprite(0, 0.03f, 0, 3f, color(s, 0.8f, 0), 0.35f * rem, DISC);
		}

		private void drawFrostbite(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			float shatter = 1.3f;
			// Frost spreading over the ground.
			float frost = time < 1.6f ? easeOut(time / 0.4f) : fadeAfter(time, 1.6f, 0.8f);
			out.sprite(0, 0.035f, 0, 1.9f * frost, mix(c0, c1, 0.3f), 0.85f * frost, CRACKS | FLAT, 1.3f);
			out.sprite(0, 0.03f, 0, 2.2f * frost, c1, 0.35f * frost, DISC);
			if (time < shatter) {
				// Ice spikes erupting round the body, overshooting a little.
				Random spikes = new Random(seed);
				for (int k = 0; k < 14; k++) {
					float a = spikes.nextFloat() * TAU;
					float r = 0.45f + spikes.nextFloat() * 0.85f;
					float delay = spikes.nextFloat() * 0.15f;
					float full = 0.45f + spikes.nextFloat() * 0.45f;
					float g = clamp01((time - delay) / 0.18f);
					float h = full * (g < 1 ? easeOut(g) * 1.15f : 1 + 0.15f * (float) Math.exp(-(time - delay - 0.18f) * 12));
					if (h > 0.01f) {
						out.sprite(r * cos(a), h * 0.95f, r * sin(a), h, mix(c0, c1, spikes.nextFloat()), 0.95f, SHARD | UPRIGHT, (spikes.nextFloat() - 0.5f) * 0.5f);
					}
				}
				// The body frozen in a crystal.
				float tomb = clamp01((time - 0.15f) * 5);
				if (tomb > 0) {
					out.sprite(0, 1.0f, 0, 1.3f * (0.8f + 0.2f * tomb), mix(c0, c1, 0.4f), 0.5f * tomb, SHARD | UPRIGHT);
					out.sprite(0, 1.0f, 0, 1.6f, c1, 0.2f * tomb, GLOW);
				}
			}
			flash(out, 1, shatter, 0.3f, 2.4f, mix(c0, 0xFFFFFF, 0.5f), 1f);
			ring(out, 0.05f, shatter, 0.5f, 0.3f, 3f, c1, 0.9f);
			ring(out, 1.0f, shatter, 0.45f, 0.2f, 2.2f, c0, 0.7f);
		}

		// ---- Meteor, Tornado, Grave, Thunder, Shadow, Katana, Jackpot ----

		private static final float METEOR_HIT = 0.5f;
		private static final float[] STRIKES = {0f, 0.22f, 0.5f};

		/** The meteor coming down at a slant, faster and faster, hitting the feet at {@link #METEOR_HIT}. */
		private static float[] meteorAt(float t) {
			float f = (float) Math.pow(clamp01(t / METEOR_HIT), 1.4);
			return new float[] {-5f + 5f * f, 10f - 9.8f * f, -2.5f + 2.5f * f};
		}

		/** Where lightning strike {@code i} hits (x, z). */
		private float[] strikeAt(int i) {
			if (i == 0) {
				return new float[] {0, 0};
			}
			Random r = new Random(seed * 13 + i);
			float a = r.nextFloat() * TAU;
			float d = 0.9f + r.nextFloat() * 0.6f;
			return new float[] {d * cos(a), d * sin(a)};
		}

		private void sparksAt(float x, float z, int n, float speed, float life) {
			for (int k = 0; k < n; k++) {
				float a = rnd(0, TAU);
				P p = add(x, 0.1f, z, rnd(life * 0.6f, life), rnd(0.04f, 0.07f), SPARK);
				float v = rnd(speed * 0.4f, speed);
				p.vx = cos(a) * v * 0.7f;
				p.vy = rnd(0.4f, 1f) * v;
				p.vz = sin(a) * v * 0.7f;
				p.gravity = 6f;
				p.drag = 1.2f;
			}
		}

		private void drawMeteor(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			if (time < METEOR_HIT) {
				float[] m = meteorAt(time);
				float[] back = meteorAt(Math.max(0, time - 0.12f));
				float dx = m[0] - back[0];
				float dy = m[1] - back[1];
				float dz = m[2] - back[2];
				float l = Math.max(1e-4f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
				float tx = m[0] - dx / l * 2.6f;
				float ty = m[1] - dy / l * 2.6f;
				float tz = m[2] - dz / l * 2.6f;
				out.beam(m[0], m[1], m[2], tx, ty, tz, 0.9f, c1, 0.45f);
				out.beam(m[0], m[1], m[2], m[0] - dx / l * 1.4f, m[1] - dy / l * 1.4f, m[2] - dz / l * 1.4f, 0.4f, c0, 0.8f);
				out.sprite(m[0], m[1], m[2], 1.1f, c1, 0.55f, GLOW);
				out.sprite(m[0], m[1], m[2], 0.45f, mix(c0, 0xFFFFFF, 0.5f), 1f, GLOW);
				out.sprite(m[0], m[1], m[2], 0.28f, 0x2A1E18, 0.9f, PIXEL, time * 9);
				// Its glow on the ground, growing as it comes.
				float f = time / METEOR_HIT;
				out.sprite(0, 0.04f, 0, 0.6f + 1.4f * f, c1, 0.5f * f * f, DISC);
				return;
			}
			float t = time - METEOR_HIT;
			flash(out, 0.6f, METEOR_HIT, 0.4f, 4f, mix(c0, 0xFFFFFF, 0.5f), 1f);
			float fire = fadeAfter(t, 0, 0.8f);
			if (fire > 0) {
				for (int k = 0; k < 5; k++) {
					float a = k * TAU / 5 + 0.4f;
					float r = 0.5f * easeOut(t / 0.3f);
					out.sprite(r * cos(a), 0.4f + 0.5f * easeOut(t / 0.4f), r * sin(a), (0.7f + 0.9f * easeOut(t / 0.5f)), color(s, 0.3f + 0.15f * k, k),
							0.7f * fire, GLOW);
				}
				out.sprite(0, 0.6f, 0, 2.4f * (1.2f - fire), mix(c0, 0xFFFFFF, 0.3f), fire, BURST, t);
			}
			ring(out, 0.05f, METEOR_HIT, 0.55f, 0.3f, 4f, c1, 1f);
			ring(out, 0.06f, METEOR_HIT + 0.1f, 0.6f, 0.2f, 2.8f, c0, 0.7f);
			// The crater: cracks glowing like lava, cooling to dark.
			float cool = clamp01(t / 1.6f);
			float a = t < 0.05f ? t / 0.05f : fadeAfter(t, 1.1f, 0.9f);
			out.sprite(0, 0.035f, 0, 1.9f, mix(mix(c1, 0xFFE0A0, 0.3f), 0x3A1A10, cool), a, CRACKS | FLAT, 0.4f);
			out.sprite(0, 0.03f, 0, 1.4f, c1, 0.5f * a * (1 - cool), DISC);
		}

		private void drawTornado(Settings s, Out out) {
			float grow = easeOut(time / 0.35f);
			float end = 1.9f;
			float spread = time > end ? 1 + (time - end) * 3 : 1;
			float alpha = time > end ? fadeAfter(time, end, 0.7f) : 1;
			for (int k = 0; k < 11; k++) {
				float y = k * 0.3f * grow;
				float r = (0.22f + 0.13f * k) * grow * spread;
				float cx = 0.15f * sin(time * 3 + k * 0.5f);
				float cz = 0.15f * cos(time * 2.6f + k * 0.4f);
				float spin = time * (9 - k * 0.4f) + k * 1.3f;
				int c = color(s, k / 10f, k);
				// Three streaks per ring, racing round.
				for (int arc = 0; arc < 3; arc++) {
					float a0 = spin + arc * TAU / 3;
					for (int j = 0; j < 4; j++) {
						float a1 = a0 + j * 0.28f;
						float a2 = a1 + 0.28f;
						float w = 0.05f + 0.03f * (3 - j);
						out.beam(cx + r * cos(a1), y + 0.05f * j, cz + r * sin(a1), cx + r * cos(a2), y + 0.05f * (j + 1), cz + r * sin(a2), w, c,
								0.55f * alpha * (1 - j / 4f));
					}
				}
				if (k % 3 == 0) {
					out.sprite(cx, y, cz, r * 1.3f, c, 0.1f * alpha, SMOKE, spin * 0.3f);
				}
			}
			out.sprite(0, 0.03f, 0, 1.8f * grow * spread, color(s, 0.8f, 0), 0.3f * alpha, DISC);
			ring(out, 0.05f, 0, 0.6f, 0.2f, 2.4f, color(s, 0.3f, 0), 0.8f);
		}

		private void drawGrave(Settings s, Out out) {
			int stone = color(s, 0f, 0);
			int accent = color(s, 1f, 0);
			// Cracked earth where it comes up.
			float earth = clamp01(time * 6) * (time > 2.1f ? fadeAfter(time, 2.1f, 0.5f) : 1);
			out.sprite(0, 0.035f, 0, 1.3f, 0x2A2018, 0.85f * earth, CRACKS | FLAT, 0.9f);
			out.sprite(0, 0.03f, 0, 1.6f, accent, 0.25f * earth, DISC);
			// The stone rising, settling with a little wobble, and sinking back at the end.
			float rise = easeOut((time - 0.1f) / 0.5f);
			float sink = time > 2.1f ? ease((time - 2.1f) / 0.5f) : 0;
			float h = 1.25f;
			float y = -h / 2 + (h * 0.98f) * rise - h * sink;
			float wobble = 0.08f * (float) Math.exp(-Math.max(0, time - 0.6f) * 5) * sin(time * 30) * (time > 0.55f ? 1 : 0);
			if (rise > 0) {
				// Only the part above ground shows: clip by drawing it once it's up far enough.
				float a = clamp01((y + h / 2) / 0.15f) * (1 - sink);
				card(out, 0, y, 0, 1.0f, h, wobble, AuraEffects.cell(TOMB), stone, 0.95f * a, false);
				card(out, 0, y, 0, 1.4f, h * 1.2f, wobble, AuraEffects.cell(TOMB), accent, 0.18f * a, false);
			}
			// Candles at its sides and flowers at its foot.
			float lit = clamp01((time - 0.55f) * 4) * (1 - sink);
			if (lit > 0) {
				float rx = faceZ;
				float rz = -faceX;
				for (int side = -1; side <= 1; side += 2) {
					float x = rx * side * 0.6f + faceX * 0.15f;
					float z = rz * side * 0.6f + faceZ * 0.15f;
					float flick = 0.85f + 0.15f * sin(time * 17 + side * 4);
					out.beam(x, 0, z, x, 0.18f, z, 0.07f, 0xEDE4D0, 0.9f * lit);
					out.sprite(x, 0.25f, z, 0.07f * flick, 0xFFD27A, lit, FLAME | UPRIGHT);
					out.sprite(x, 0.25f, z, 0.3f, 0xFFB347, 0.35f * lit * flick, GLOW);
				}
				for (int f = 0; f < 3; f++) {
					float off = (f - 1) * 0.22f;
					float x = rx * off + faceX * 0.4f;
					float z = rz * off + faceZ * 0.4f;
					float bloom = clamp01((time - 0.65f - f * 0.08f) * 5) * (1 - sink);
					for (int k = 0; k < 5; k++) {
						float pa = k * TAU / 5 + f;
						out.sprite(x + 0.04f * cos(pa), 0.08f, z + 0.04f * sin(pa), 0.045f * bloom, accent, 0.9f * bloom, PETAL | FLAT, -pa - (float) Math.PI / 2);
					}
					out.sprite(x, 0.09f, z, 0.025f * bloom, 0xFFF4A0, bloom, SPARKLE | FLAT);
				}
			}
		}

		private void bolt(Out out, float x, float z, long boltSeed, float alpha, int glow) {
			Random r = new Random(boltSeed);
			float px = x + (r.nextFloat() - 0.5f) * 3;
			float py = 14;
			float pz = z + (r.nextFloat() - 0.5f) * 3;
			int segs = 14;
			for (int k = 1; k <= segs; k++) {
				float f = k / (float) segs;
				float nx = px + (x - px) / (segs - k + 1) + (k == segs ? 0 : (r.nextFloat() - 0.5f) * 0.6f);
				float ny = 14 * (1 - f);
				float nz = pz + (z - pz) / (segs - k + 1) + (k == segs ? 0 : (r.nextFloat() - 0.5f) * 0.6f);
				out.beam(px, py, pz, nx, ny, nz, 0.45f, glow, 0.5f * alpha);
				out.beam(px, py, pz, nx, ny, nz, 0.09f, 0xFFFFFF, alpha);
				if (k > 3 && k < 11 && r.nextFloat() < 0.3f) {
					// A fork.
					float bx = nx;
					float by = ny;
					float bz = nz;
					float dirx = (r.nextFloat() - 0.5f) * 1.2f;
					float dirz = (r.nextFloat() - 0.5f) * 1.2f;
					for (int j = 0; j < 4; j++) {
						float qx = bx + dirx * 0.5f + (r.nextFloat() - 0.5f) * 0.3f;
						float qy = by - 0.6f;
						float qz = bz + dirz * 0.5f + (r.nextFloat() - 0.5f) * 0.3f;
						out.beam(bx, by, bz, qx, qy, qz, 0.2f, glow, 0.35f * alpha * (1 - j / 4f));
						out.beam(bx, by, bz, qx, qy, qz, 0.04f, 0xFFFFFF, 0.8f * alpha * (1 - j / 4f));
						bx = qx;
						by = qy;
						bz = qz;
					}
				}
				px = nx;
				py = ny;
				pz = nz;
			}
		}

		private void drawThunder(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			for (int i = 0; i < 3; i++) {
				float t = time - STRIKES[i];
				if (t < 0) {
					continue;
				}
				float[] g = strikeAt(i);
				if (t < 0.2f) {
					// Flickering on and off while it lasts.
					float flick = ((int) (time * 40 + i)) % 3 == 0 ? 0.35f : 1f;
					bolt(out, g[0], g[1], seed * 7 + i * 101 + (long) (t * 12), flick * (1 - t / 0.25f), c1);
				}
				float flash = fadeAfter(t, 0, 0.3f);
				if (flash > 0) {
					out.sprite(g[0], 0.4f, g[1], 2.6f * (1.2f - flash), mix(c1, 0xFFFFFF, 0.5f), flash, GLOW);
				}
				if (t < 0.5f) {
					float f = t / 0.5f;
					out.sprite(g[0], 0.05f, g[1], (0.3f + 2.6f * easeOut(f)) / 0.74f, c1, (1 - f) * 0.9f, HALO | FLAT);
				}
				float scorch = t < 0.05f ? t / 0.05f : fadeAfter(t, 0.8f, 1.2f);
				out.sprite(g[0], 0.035f + i * 0.002f, g[1], 0.9f + 0.3f * (i == 0 ? 1 : 0), mix(c1, c0, 0.4f), scorch, CRACKS | FLAT, i * 2.1f);
			}
			// Arcs crawling over the ground between the strikes.
			float crawl = time < 1.3f ? 1 : fadeAfter(time, 1.3f, 0.5f);
			Random r = new Random(seed * 3 + (long) (time * 24));
			for (int k = 0; k < 5 && crawl > 0; k++) {
				float a = r.nextFloat() * TAU;
				float d0 = r.nextFloat() * 1.4f;
				float px = d0 * cos(a);
				float pz = d0 * sin(a);
				for (int j = 0; j < 4; j++) {
					float qx = px + (r.nextFloat() - 0.5f) * 0.5f;
					float qz = pz + (r.nextFloat() - 0.5f) * 0.5f;
					float y0 = 0.05f + r.nextFloat() * 0.15f;
					out.beam(px, y0, pz, qx, 0.05f + r.nextFloat() * 0.15f, qz, 0.12f, c1, 0.4f * crawl);
					out.beam(px, y0, pz, qx, 0.05f, qz, 0.03f, 0xFFFFFF, 0.9f * crawl);
					px = qx;
					pz = qz;
				}
			}
			out.sprite(0, 0.03f, 0, 2.4f, c1, 0.25f * crawl, DISC);
		}

		private void drawShadow(Settings s, Out out) {
			int dark = color(s, 0f, 0);
			int glow = color(s, 1f, 0);
			float rise = easeOut(time / 0.45f);
			float back = time > 1.5f ? ease((time - 1.5f) / 0.5f) : 0;
			float reach = rise * (1 - back);
			// The dark pool.
			float pool = clamp01(time * 5) * (time > 1.9f ? fadeAfter(time, 1.9f, 0.6f) : 1);
			out.sprite(0, 0.03f, 0, 1.9f, glow, 0.25f * pool, DISC);
			out.sprite(0, 0.035f, 0, 1.4f, mix(dark, 0x000000, 0.5f), 0.9f * pool, GLOW | FLAT);
			out.sprite(0, 0.04f, 0, 1.25f / 0.74f, glow, 0.6f * pool, HALO | FLAT);
			if (reach > 0.01f) {
				Random r = new Random(seed);
				for (int i = 0; i < 8; i++) {
					float a = i * TAU / 8 + r.nextFloat() * 0.5f;
					float length = (1.6f + r.nextFloat() * 0.7f) * reach;
					float bx = 0.95f * cos(a);
					float bz = 0.95f * sin(a);
					float[] prev = {bx, 0.02f, bz};
					for (int k = 1; k <= 10; k++) {
						float u = k / 10f;
						// Up and curling in over the body, waving.
						float inward = 1 - u * 0.85f;
						float wave = 0.12f * sin(time * 6 + i * 1.7f + u * 5) * u;
						float[] q = {bx * inward + -bz * wave, u * length * 0.9f, bz * inward + bx * wave};
						float w = 0.24f * (1 - u * 0.8f);
						out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], w * 2.4f, glow, 0.22f);
						out.beam(prev[0], prev[1], prev[2], q[0], q[1], q[2], w, dark, 0.95f);
						prev = q;
					}
				}
			}
			// Red eyes in the smoke.
			float eyes = clamp01((time - 0.4f) * 4) * (time > 1.6f ? fadeAfter(time, 1.6f, 0.3f) : 1);
			float blink = ((int) (time * 3)) % 4 == 3 ? 0.2f : 1;
			if (eyes > 0) {
				float rx = faceZ;
				float rz = -faceX;
				for (int side = -1; side <= 1; side += 2) {
					float x = faceX * 0.45f + rx * side * 0.12f;
					float z = faceZ * 0.45f + rz * side * 0.12f;
					out.sprite(x, 1.35f, z, 0.25f, glow, 0.5f * eyes * blink, GLOW);
					out.sprite(x, 1.35f, z, 0.08f, mix(glow, 0xFFFFFF, 0.4f), eyes * blink, SPARKLE);
				}
			}
		}

		private void drawKatana(Settings s, Out out) {
			int c0 = color(s, 0f, 0);
			int c1 = color(s, 1f, 0);
			float[] angles = {0.55f, -0.6f, 1.45f};
			float rx = faceZ;
			float rz = -faceX;
			for (int k = 0; k < 3; k++) {
				float t = time - k * 0.1f;
				if (t < 0 || t > 0.35f) {
					continue;
				}
				float f = t / 0.35f;
				float len = 1.6f + 0.4f * easeOut(f);
				float dx = rx * cos(angles[k]);
				float dy = sin(angles[k]);
				float dz = rz * cos(angles[k]);
				// Nearer the viewer than the body, so the cut shows across it.
				float cx = faceX * 0.4f;
				float cz = faceZ * 0.4f;
				out.beam(cx - dx * len, 1 - dy * len, cz - dz * len, cx + dx * len, 1 + dy * len, cz + dz * len, 0.35f * (1 - f), c1, 0.6f * (1 - f));
				out.beam(cx - dx * len, 1 - dy * len, cz - dz * len, cx + dx * len, 1 + dy * len, cz + dz * len, 0.06f * (1 - f), c0, 1 - f);
			}
			flash(out, 1, 0.2f, 0.25f, 1.4f, mix(c1, 0xFFFFFF, 0.5f), 0.9f);
			float ting = fadeAfter(time, 0.6f, 0.4f);
			if (ting > 0) {
				out.sprite(faceX * 0.4f, 1.1f, faceZ * 0.4f, 0.5f * (1.3f - ting), 0xFFFFFF, ting, SPARKLE, time * 4);
			}
			ring(out, 0.05f, 0.34f, 0.6f, 0.2f, 2.6f, c1, 0.8f);
		}

		private void drawJackpot(Settings s, Out out) {
			int gold = color(s, 1f, 0);
			int light = color(s, 0f, 0);
			flash(out, 1.2f, 0.28f, 0.35f, 2.2f, mix(light, 0xFFFFFF, 0.4f), 0.9f);
			ring(out, 0.05f, 0.3f, 0.6f, 0.2f, 2.8f, gold, 0.9f);
			// 7 - 7 - 7 popping up above, one after another.
			float rx = faceZ;
			float rz = -faceX;
			float leave = time > 1.6f ? ease((time - 1.6f) / 0.5f) : 0;
			for (int k = 0; k < 3; k++) {
				float t = time - 0.05f - k * 0.1f;
				if (t <= 0) {
					continue;
				}
				float pop = t < 0.15f ? easeOut(t / 0.15f) * 1.25f : 1 + 0.25f * (float) Math.exp(-(t - 0.15f) * 10) * cos((t - 0.15f) * 30);
				float off = (k - 1) * 0.55f;
				float x = rx * off + faceX * 0.3f;
				float z = rz * off + faceZ * 0.3f;
				float y = 2.45f + 0.06f * sin(time * 5 + k) + leave * 0.6f;
				float a = 1 - leave;
				card(out, x, y, z, 0.62f * pop, 0.62f * pop, 0, CELL_GLYPH + 6, gold, 0.35f * a, true);
				card(out, x, y, z, 0.5f * pop, 0.5f * pop, 0, CELL_GLYPH + 6, mix(light, 0xFFFFFF, 0.3f), a, true);
				out.sprite(x, y, z, 0.5f * pop, gold, 0.25f * a, GLOW);
			}
			out.sprite(0, 0.03f, 0, 1.8f, gold, 0.3f * clamp01(time * 3) * (1 - leave), DISC);
		}
	}
}
