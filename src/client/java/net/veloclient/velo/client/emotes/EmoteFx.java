package net.veloclient.velo.client.emotes;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The emotes' own effects - plain Java (no Minecraft classes), so the launcher compiles the same file for its previews
 * (like {@code AuraEffects}). No vanilla particles: everything is a sprite from one atlas
 * ({@code textures/cosmetics/emote/fx.png}, tools/cosmetics/emotes/make_emote_atlas.py), a beam or a prop model.
 *
 * <p>An {@link Emitter} per emoting player keeps its particles, streams (effects that last a while: hand trails,
 * spirals, snow...) and props. Positions are in the player's <b>body space</b>: blocks, origin at the feet, x to the
 * player's right, y up, z forwards - an emoting player stands still, so effects stay where they were made and the same
 * numbers work on the menu avatar. An emote's track says what to fire when ({@link Spec}); the anchors (a hand, the
 * head...) come from the pose playing right now.
 */
public final class EmoteFx {

	// atlas cells (8 x 4 grid)
	public static final int GLOW = 0;
	public static final int STAR = 1;
	public static final int BEAM = 2;
	public static final int HEART = 3;
	public static final int SMOKE = 7;
	public static final int RING = 8;
	public static final int SQUARE = 15;

	private static final String[] CELL_NAMES = {"glow", "star", "beam", "heart", "note", "notes", "sparkle", "smoke", "ring", "snowflake",
			"bat", "ghost", "z", "drop", "flame", "square", "skull", "bolt", "anger", "swirl", "candy", "bubble", "L", "Y", "M", "C", "A",
			"G", "O", "!", "?", "X"};
	/** Alpha-blended cells (dark outline, read on bright backgrounds); the rest glow (drawn additively). */
	private static final boolean[] SOLID = new boolean[32];

	static {
		for (int c : new int[] {3, 4, 5, 7, 10, 11, 12, 13, 15, 16, 18, 19, 20, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31}) {
			SOLID[c] = true;
		}
	}

	/** Rainbow: a colour that cycles through the hues. */
	public static final int RAINBOW = -1;

	private static final int MAX_PARTICLES = 420;

	private EmoteFx() {
	}

	public static int cell(String name) {
		if (name == null) {
			return GLOW;
		}
		// names and the atlas' letters (Z, L, Y, M, C, A, G, O, !, ?, X), any case
		for (int i = 0; i < CELL_NAMES.length; i++) {
			if (CELL_NAMES[i].equalsIgnoreCase(name)) {
				return i;
			}
		}
		return GLOW;
	}

	public static boolean solid(int cell) {
		return cell >= 0 && cell < SOLID.length && SOLID[cell];
	}

	/**
	 * One effect of an emote's track. {@code type}: burst, fountain, confetti, ring, flash, text, bolt (lightning
	 * striking the anchor) - fired at once; trail, spiral, rain, orbit, fog, stream - running for {@code duration}
	 * seconds. Speeds are blocks per second, sizes blocks, {@code gravity} blocks per second squared (down).
	 */
	public record Spec(String type, int[] cells, int[] colors, int count, float speed, float life, float size, float gravity,
			float duration, float radius, float spin, String text) {

		public boolean continuous() {
			return switch (type) {
				case "trail", "spiral", "rain", "orbit", "fog", "stream" -> true;
				default -> false;
			};
		}
	}

	/** A prop (a 3D model) shown during an emote. */
	public record PropSpec(String id, String at, float duration, float x, float y, float z, float yaw, float pitch, float roll, float scale,
			String anim, float vx, float vy, float vz, float release, float spin, float pulse) {
	}

	/** Where to draw: everything in body space (see the class doc). */
	public interface Out {
		/** A camera-facing sprite (shifted {@code offset} blocks along the camera's right - text), rotated by {@code angle}. */
		void sprite(float x, float y, float z, float size, int rgb, float alpha, int cell, float angle, float offset);

		/** A sprite lying flat (on the ground), turned by {@code angle}. */
		void flat(float x, float y, float z, float size, int rgb, float alpha, int cell, float angle);

		void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha);
	}

	/** The pose's anchor points right now, in body space; false when unknown. */
	public interface Anchors {
		boolean anchor(String at, float[] out);
	}

	/** A prop as it is now: draw model {@code id} attached to {@code at} ("ground" = body space, else a body part). */
	public record PropView(String id, String at, float x, float y, float z, float yaw, float pitch, float roll, float scale) {
	}

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
		float grow = 1f;
		float gravity;
		float drag = 1.2f;
		float angle;
		float spin;
		float flutter;
		float seed;
		int rgb;
		int cell;
		boolean flat;
		boolean rainbow;
		float offset;
		float pop;
	}

	private static final class Stream {
		final Spec spec;
		final String at;
		float age;
		float debt;
		final float phase;

		Stream(Spec spec, String at, float phase) {
			this.spec = spec;
			this.at = at;
			this.phase = phase;
		}
	}

	private static final class Bolt {
		float x;
		float z;
		float tx;
		float ty;
		float tz;
		float age;
		float life;
		long seed;
		int rgb;
		float width;
	}

	private static final class Prop {
		final PropSpec spec;
		float age;
		boolean flying;
		float px;
		float py;
		float pz;
		float vx;
		float vy;
		float vz;
		float stopAge = -1f;

		Prop(PropSpec spec) {
			this.spec = spec;
		}
	}

	/** One player's emote effects. Not thread-safe: fired and drawn on the render/client thread. */
	public static final class Emitter {
		private final List<P> particles = new ArrayList<>();
		private final List<Stream> streams = new ArrayList<>();
		private final List<Bolt> bolts = new ArrayList<>();
		private final List<Prop> props = new ArrayList<>();
		private final Random random = new Random();
		private final float[] tmp = new float[3];
		private float time;
		private long lastNanos;

		/** Seconds since the last call (0 the first time, at most 0.1). */
		public float tick(long nanos) {
			float dt = lastNanos == 0 ? 0 : Math.min(0.1f, Math.max(0, (nanos - lastNanos) / 1e9f));
			lastNanos = nanos;
			return dt;
		}

		public long lastNanos() {
			return lastNanos;
		}

		public boolean idle() {
			return particles.isEmpty() && streams.isEmpty() && bolts.isEmpty() && props.isEmpty();
		}

		/** Ends everything that lasts (streams; props shrink away) - the emote stopped. Particles finish their lives. */
		public void release() {
			streams.clear();
			for (Prop p : props) {
				if (p.stopAge < 0) {
					p.stopAge = p.age;
				}
			}
		}

		public void clear() {
			particles.clear();
			streams.clear();
			bolts.clear();
			props.clear();
		}

		// ---------------------------------------------------------------- firing

		/** Fires an effect at anchor {@code at} (body space position from {@code anchors}). */
		public void fire(Spec s, String at, Anchors anchors) {
			if (s.continuous()) {
				streams.add(new Stream(s, at, random.nextFloat()));
				return;
			}
			float[] a = new float[3];
			if (!anchors.anchor(at, a)) {
				return;
			}
			switch (s.type()) {
				case "fountain" -> fountain(s, a, false);
				case "confetti" -> fountain(s, a, true);
				case "ring" -> ring(s, a);
				case "flash" -> flash(s, a);
				case "text" -> text(s, a);
				case "bolt" -> bolt(s, a);
				default -> burst(s, a);
			}
		}

		public void prop(PropSpec spec) {
			props.add(new Prop(spec));
		}

		private P particle(Spec s, float x, float y, float z) {
			P p = new P();
			p.x = x;
			p.y = y;
			p.z = z;
			p.life = s.life() * (0.75f + 0.5f * random.nextFloat());
			p.size = s.size() * (0.7f + 0.6f * random.nextFloat());
			p.gravity = s.gravity();
			p.cell = s.cells().length == 0 ? GLOW : s.cells()[random.nextInt(s.cells().length)];
			int color = s.colors().length == 0 ? 0xFFFFFF : s.colors()[random.nextInt(s.colors().length)];
			p.rainbow = color == RAINBOW;
			p.rgb = p.rainbow ? hsv(random.nextFloat(), 0.7f, 1f) : color;
			p.angle = solid(p.cell) && p.cell >= 22 ? 0 : random.nextFloat() * 6.283f;
			p.spin = s.spin() * (random.nextBoolean() ? 1 : -1) * (0.5f + random.nextFloat());
			p.seed = random.nextFloat() * 100;
			return p;
		}

		private void add(P p) {
			if (particles.size() >= MAX_PARTICLES) {
				particles.remove(0);
			}
			particles.add(p);
		}

		private void burst(Spec s, float[] a) {
			for (int i = 0; i < s.count(); i++) {
				P p = particle(s, a[0], a[1], a[2]);
				float[] d = sphere();
				float v = s.speed() * (0.4f + 0.6f * random.nextFloat());
				p.vx = d[0] * v;
				p.vy = d[1] * v;
				p.vz = d[2] * v;
				p.drag = 2f;
				add(p);
			}
		}

		private void fountain(Spec s, float[] a, boolean confetti) {
			for (int i = 0; i < s.count(); i++) {
				P p = particle(s, a[0], a[1], a[2]);
				float yaw = random.nextFloat() * 6.283f;
				float cone = (float) Math.toRadians(confetti ? 40 : 28) * random.nextFloat();
				float v = s.speed() * (0.65f + 0.55f * random.nextFloat());
				p.vx = (float) (Math.sin(cone) * Math.cos(yaw)) * v;
				p.vy = (float) Math.cos(cone) * v;
				p.vz = (float) (Math.sin(cone) * Math.sin(yaw)) * v;
				p.gravity = s.gravity() == 0 ? (confetti ? 3f : 7f) : s.gravity();
				if (confetti) {
					p.drag = 2.6f;
					p.flutter = 1f;
					p.spin = (random.nextBoolean() ? 1 : -1) * (4 + random.nextFloat() * 6);
				} else {
					p.drag = 0.6f;
				}
				add(p);
			}
		}

		private void ring(Spec s, float[] a) {
			P p = particle(s, a[0], Math.max(0.02f, a[1]), a[2]);
			p.flat = true;
			p.cell = RING;
			p.size = 0.15f;
			p.grow = Math.max(1.5f, s.radius() / 0.15f);
			p.life = s.life();
			p.drag = 0;
			add(p);
			// sparks thrown out along the ring
			for (int i = 0; i < s.count(); i++) {
				float ang = (float) (i * 6.283 / Math.max(1, s.count())) + random.nextFloat() * 0.2f;
				P q = particle(s, a[0], Math.max(0.05f, a[1]) + 0.05f, a[2]);
				if (q.cell == RING) {
					q.cell = STAR;
				}
				float v = s.radius() / Math.max(0.1f, s.life()) * (0.8f + 0.4f * random.nextFloat());
				q.vx = (float) Math.cos(ang) * v;
				q.vz = (float) Math.sin(ang) * v;
				q.vy = s.speed() * random.nextFloat();
				q.size = s.size() * (0.4f + 0.4f * random.nextFloat());
				q.drag = 1.8f;
				add(q);
			}
		}

		private void flash(Spec s, float[] a) {
			P p = particle(s, a[0], a[1], a[2]);
			p.size = s.size();
			p.grow = 1.8f;
			p.life = s.life();
			p.drag = 0;
			p.spin = 0;
			add(p);
		}

		private void text(Spec s, float[] a) {
			String text = s.text() == null ? "!" : s.text();
			int n = text.length();
			int color = s.colors().length == 0 ? 0xFFFFFF : s.colors()[0];
			for (int i = 0; i < n; i++) {
				char ch = text.charAt(i);
				if (ch == ' ') {
					continue;
				}
				P p = new P();
				p.x = a[0];
				p.y = a[1];
				p.z = a[2];
				p.cell = cell(String.valueOf(ch));
				p.size = s.size();
				p.life = s.life();
				p.vy = s.speed();
				p.drag = 1.5f;
				p.offset = (i - (n - 1) / 2f) * s.size() * 1.6f;
				p.rgb = color == RAINBOW ? hsv(i / (float) n, 0.7f, 1f) : s.colors().length > 1 ? s.colors()[i % s.colors().length] : color;
				p.pop = 0.08f * i;
				add(p);
			}
		}

		private void bolt(Spec s, float[] a) {
			int color = s.colors().length == 0 ? 0xBFE6FF : s.colors()[0];
			for (int i = 0; i < Math.max(1, s.count()); i++) {
				Bolt b = new Bolt();
				float r = s.radius() * (float) Math.sqrt(random.nextFloat());
				float ang = random.nextFloat() * 6.283f;
				b.tx = a[0] + (float) Math.cos(ang) * r;
				b.ty = a[1];
				b.tz = a[2] + (float) Math.sin(ang) * r;
				b.x = b.tx + (random.nextFloat() - 0.5f) * 1.5f;
				b.z = b.tz + (random.nextFloat() - 0.5f) * 1.5f;
				b.life = s.life();
				b.seed = random.nextLong();
				b.rgb = color;
				b.width = s.size();
				bolts.add(b);
				// impact flash and sparks
				P f = new P();
				f.x = b.tx;
				f.y = b.ty + 0.1f;
				f.z = b.tz;
				f.cell = GLOW;
				f.rgb = color;
				f.size = 0.9f;
				f.grow = 1.6f;
				f.life = 0.35f;
				f.drag = 0;
				add(f);
				for (int k = 0; k < 10; k++) {
					P q = new P();
					q.x = b.tx;
					q.y = b.ty + 0.05f;
					q.z = b.tz;
					float[] d = sphere();
					q.vx = d[0] * 3;
					q.vy = Math.abs(d[1]) * 3;
					q.vz = d[2] * 3;
					q.gravity = 8;
					q.drag = 1;
					q.cell = STAR;
					q.rgb = color;
					q.size = 0.08f;
					q.life = 0.5f + random.nextFloat() * 0.3f;
					add(q);
				}
			}
		}

		// ---------------------------------------------------------------- update

		/** Advances everything by {@code dt} seconds. */
		public void update(float dt, Anchors anchors) {
			if (dt <= 0) {
				return;
			}
			time += dt;
			for (int i = streams.size() - 1; i >= 0; i--) {
				Stream st = streams.get(i);
				st.age += dt;
				if (st.spec.duration() > 0 && st.age > st.spec.duration()) {
					streams.remove(i);
					continue;
				}
				emit(st, dt, anchors);
			}
			for (int i = particles.size() - 1; i >= 0; i--) {
				P p = particles.get(i);
				p.age += dt;
				if (p.age >= p.life) {
					particles.remove(i);
					continue;
				}
				float drag = (float) Math.exp(-p.drag * dt);
				p.vx *= drag;
				p.vy *= drag;
				p.vz *= drag;
				p.vy -= p.gravity * dt;
				if (p.flutter > 0) {
					// paper: slows its fall and sways
					p.vy = Math.max(p.vy, -0.9f);
					p.x += (float) Math.sin(time * 5 + p.seed) * 0.6f * dt;
					p.z += (float) Math.cos(time * 4 + p.seed) * 0.6f * dt;
				}
				p.x += p.vx * dt;
				p.y += p.vy * dt;
				p.z += p.vz * dt;
				if (p.y < 0.02f && p.gravity > 0 && !p.flat) {
					p.y = 0.02f;
					p.vy = -p.vy * 0.25f;
					p.vx *= 0.6f;
					p.vz *= 0.6f;
				}
				p.angle += p.spin * dt;
			}
			for (int i = bolts.size() - 1; i >= 0; i--) {
				Bolt b = bolts.get(i);
				b.age += dt;
				if (b.age >= b.life) {
					bolts.remove(i);
				}
			}
			for (int i = props.size() - 1; i >= 0; i--) {
				Prop p = props.get(i);
				p.age += dt;
				PropSpec s = p.spec;
				if (p.stopAge < 0 && s.duration() > 0 && p.age > s.duration()) {
					p.stopAge = p.age;
				}
				if (p.stopAge >= 0 && p.age - p.stopAge > 0.3f) {
					props.remove(i);
					continue;
				}
				if (!p.flying && s.release() > 0 && p.age >= s.release() && ("throw".equals(s.anim()) || "kick".equals(s.anim()))) {
					p.flying = true;
					if ("throw".equals(s.anim()) && anchors.anchor(s.at(), tmp)) {
						p.px = tmp[0];
						p.py = tmp[1];
						p.pz = tmp[2];
					} else {
						p.px = s.x();
						p.py = s.y();
						p.pz = s.z();
					}
					p.vx = s.vx();
					p.vy = s.vy();
					p.vz = s.vz();
				}
				if (p.flying) {
					p.vy -= 9.5f * dt;
					p.px += p.vx * dt;
					p.py += p.vy * dt;
					p.pz += p.vz * dt;
					if (p.py < 0 && p.vy < 0) {
						p.py = 0;
						p.vy = -p.vy * 0.35f;
						p.vx *= 0.7f;
						p.vz *= 0.7f;
					}
				}
			}
		}

		private void emit(Stream st, float dt, Anchors anchors) {
			Spec s = st.spec;
			if ("orbit".equals(s.type())) {
				return; // drawn directly
			}
			float[] a = tmp;
			if (!anchors.anchor(st.at, a)) {
				return;
			}
			st.debt += dt * s.count();
			int n = (int) st.debt;
			st.debt -= n;
			for (int i = 0; i < n; i++) {
				switch (s.type()) {
					case "spiral" -> {
						float ang = (st.age * s.speed() * 2.2f + (i % 2) * 3.1416f + st.phase * 6.283f);
						float r = s.radius() <= 0 ? 0.55f : s.radius();
						P p = particle(s, (float) Math.cos(ang) * r, 0.05f, (float) Math.sin(ang) * r);
						p.vy = s.speed();
						p.vx = (float) -Math.sin(ang) * 0.5f;
						p.vz = (float) Math.cos(ang) * 0.5f;
						p.drag = 0.3f;
						add(p);
					}
					case "rain" -> {
						float ang = random.nextFloat() * 6.283f;
						float r = (float) Math.sqrt(random.nextFloat()) * (s.radius() <= 0 ? 1.4f : s.radius());
						P p = particle(s, (float) Math.cos(ang) * r, 2.6f + random.nextFloat() * 0.6f, (float) Math.sin(ang) * r);
						p.vy = -s.speed();
						p.gravity = s.gravity();
						p.flutter = 1f;
						p.drag = 0.2f;
						add(p);
					}
					case "fog" -> {
						float ang = random.nextFloat() * 6.283f;
						float r = (s.radius() <= 0 ? 1.2f : s.radius()) * (0.4f + 0.6f * random.nextFloat());
						P p = particle(s, (float) Math.cos(ang) * r, 0.05f + random.nextFloat() * 0.3f, (float) Math.sin(ang) * r);
						p.vx = (float) Math.cos(ang) * s.speed();
						p.vz = (float) Math.sin(ang) * s.speed();
						p.vy = 0.08f;
						p.grow = 1.8f;
						p.drag = 0.4f;
						p.spin = (random.nextFloat() - 0.5f) * 0.6f;
						add(p);
					}
					case "stream" -> {
						// a jet out of the anchor along +y (steam from a cup, sparks off a hand)
						P p = particle(s, a[0] + (random.nextFloat() - 0.5f) * 0.06f, a[1], a[2] + (random.nextFloat() - 0.5f) * 0.06f);
						p.vy = s.speed() * (0.7f + 0.6f * random.nextFloat());
						p.vx = (random.nextFloat() - 0.5f) * 0.25f;
						p.vz = (random.nextFloat() - 0.5f) * 0.25f;
						p.drag = 0.8f;
						p.grow = 2f;
						add(p);
					}
					default -> {
						// trail: left behind by a moving hand
						P p = particle(s, a[0] + (random.nextFloat() - 0.5f) * 0.05f, a[1] + (random.nextFloat() - 0.5f) * 0.05f,
								a[2] + (random.nextFloat() - 0.5f) * 0.05f);
						float[] d = sphere();
						p.vx = d[0] * s.speed();
						p.vy = d[1] * s.speed();
						p.vz = d[2] * s.speed();
						p.drag = 2.5f;
						p.grow = 0.2f;
						add(p);
					}
				}
			}
		}

		// ---------------------------------------------------------------- drawing

		/** Draws everything into {@code out}. */
		public void draw(Out out, Anchors anchors) {
			for (P p : particles) {
				float f = p.age / p.life;
				float alpha = Math.min(1f, p.age / 0.06f) * (f < 0.6f ? 1f : 1f - (f - 0.6f) / 0.4f);
				float size = p.size * (1 + (p.grow - 1) * f);
				if (p.pop > 0 || p.offset != 0) {
					// text: elastic pop in, letter by letter
					float t = Math.max(0, p.age - p.pop);
					size *= t <= 0 ? 0 : elastic(Math.min(1, t / 0.45f));
					alpha *= t <= 0 ? 0 : 1;
				}
				int rgb = p.rainbow ? hsv((p.seed * 0.1f + time * 0.4f) % 1f, 0.7f, 1f) : p.rgb;
				if (p.flat) {
					out.flat(p.x, p.y, p.z, size, rgb, alpha * (1 - f), p.cell, p.angle);
				} else {
					out.sprite(p.x, p.y, p.z, size, rgb, alpha, p.cell, p.angle, p.offset);
				}
			}
			float[] a = new float[3];
			for (Stream st : streams) {
				if (!"orbit".equals(st.spec.type()) || !anchors.anchor(st.at, a)) {
					continue;
				}
				Spec s = st.spec;
				float fade = Math.min(1f, st.age / 0.4f) * (s.duration() > 0 ? Math.min(1f, (s.duration() - st.age) / 0.4f) : 1f);
				for (int i = 0; i < s.count(); i++) {
					float ang = st.age * s.speed() + i * 6.283f / s.count() + st.phase;
					float bob = (float) Math.sin(st.age * 3 + i) * 0.08f;
					int cell = s.cells().length == 0 ? GLOW : s.cells()[i % s.cells().length];
					int color = s.colors().length == 0 ? 0xFFFFFF : s.colors()[i % s.colors().length];
					int rgb = color == RAINBOW ? hsv((i / (float) s.count() + st.age * 0.2f) % 1f, 0.7f, 1f) : color;
					out.sprite(a[0] + (float) Math.cos(ang) * s.radius(), a[1] + bob, a[2] + (float) Math.sin(ang) * s.radius(), s.size(), rgb, fade,
							cell, 0, 0);
				}
			}
			for (Bolt b : bolts) {
				float f = b.age / b.life;
				float alpha = (f < 0.5f ? 1f : 1f - (f - 0.5f) * 2) * (((int) (b.age * 30) % 3 == 0) ? 0.55f : 1f);
				Random r = new Random(b.seed + (long) (b.age * 20));
				float x = b.x;
				float y = b.ty + 9f;
				float z = b.z;
				int segments = 9;
				for (int i = 1; i <= segments; i++) {
					float k = i / (float) segments;
					float nx = b.x + (b.tx - b.x) * k + (i == segments ? 0 : (r.nextFloat() - 0.5f) * 0.7f);
					float ny = b.ty + 9f * (1 - k);
					float nz = b.z + (b.tz - b.z) * k + (i == segments ? 0 : (r.nextFloat() - 0.5f) * 0.7f);
					out.beam(x, y, z, nx, ny, nz, b.width * 2.6f, b.rgb, alpha * 0.45f);
					out.beam(x, y, z, nx, ny, nz, b.width, 0xFFFFFF, alpha);
					x = nx;
					y = ny;
					z = nz;
				}
			}
		}

		/** The props now, with their animation applied. */
		public List<PropView> props(Anchors anchors) {
			List<PropView> out = new ArrayList<>(props.size());
			for (Prop p : props) {
				PropSpec s = p.spec;
				float scale = s.scale();
				float y = s.y();
				float yaw = s.yaw();
				float pitch = s.pitch();
				float roll = s.roll();
				switch (s.anim() == null ? "pop" : s.anim()) {
					case "rise" -> {
						float k = smooth(Math.min(1, p.age / 0.9f));
						y -= scale * 1.05f * (1 - k);
						roll += (float) Math.sin(p.age * 40) * 2.5f * (1 - k);
					}
					case "drop" -> {
						float k = Math.min(1, p.age / 0.5f);
						y += (1 - k * k) * 2.2f;
						if (k >= 1) {
							float b = p.age - 0.5f;
							y += Math.abs((float) Math.sin(b * 9)) * 0.25f * (float) Math.exp(-b * 5);
						}
					}
					case "hat" -> {
						float k = Math.min(1, p.age / 0.45f);
						y += (1 - smooth(k)) * 0.9f;
						yaw += (1 - smooth(k)) * 200f;
					}
					default -> scale *= elastic(Math.min(1, p.age / 0.4f));
				}
				if (s.pulse() > 0) {
					// bounce on the beat (pulse = beats per second)
					float beat = (p.age * s.pulse()) % 1f;
					scale *= 1 + 0.07f * (float) Math.exp(-beat * 7);
				}
				if (s.spin() != 0 && !p.flying) {
					yaw += s.spin() * p.age;
				}
				if (p.stopAge >= 0) {
					scale *= 1 - smooth(Math.min(1, (p.age - p.stopAge) / 0.3f));
				}
				if (p.flying) {
					float t = p.age - s.release();
					out.add(new PropView(s.id(), "ground", p.px, p.py, p.pz, yaw + s.spin() * t, pitch + 540 * t, roll, scale));
				} else if ("kick".equals(s.anim()) || "ground".equals(s.at()) || s.at() == null) {
					out.add(new PropView(s.id(), "ground", s.x(), y, s.z(), yaw, pitch, roll, scale));
				} else {
					out.add(new PropView(s.id(), s.at(), s.x(), y, s.z(), yaw, pitch, roll, scale));
				}
			}
			return out;
		}

		private float[] sphere() {
			float z = random.nextFloat() * 2 - 1;
			float a = random.nextFloat() * 6.283f;
			float r = (float) Math.sqrt(1 - z * z);
			return new float[] {(float) Math.cos(a) * r, z, (float) Math.sin(a) * r};
		}
	}

	// ---------------------------------------------------------------- helpers

	static float smooth(float x) {
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	/** 0 -> 1 with an overshoot that settles (a pop). */
	static float elastic(float x) {
		if (x <= 0) {
			return 0;
		}
		if (x >= 1) {
			return 1;
		}
		return (float) (Math.pow(2, -10 * x) * Math.sin((x - 0.075) * (2 * Math.PI) / 0.3) + 1);
	}

	public static int hsv(float h, float s, float v) {
		float c = v * s;
		float x = c * (1 - Math.abs((h * 6) % 2 - 1));
		float m = v - c;
		float r;
		float g;
		float b;
		switch ((int) (h * 6) % 6) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}
		return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
	}
}
