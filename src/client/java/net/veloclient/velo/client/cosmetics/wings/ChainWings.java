package net.veloclient.velo.client.cosmetics.wings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chain Wings (pair {@code chainwings}): the owner's concept wing itself (upscaled, chains taken out), a plasma that flows
 * from the wing's root out into the feather tips - bright wisps and dark currents, only ever inside the feathers - a
 * polished chrome ornament with a moving sheen, and chains with sickle blades that swing with the wearer (a rope
 * simulation in world space, so they lag behind turns, jumps and runs).
 *
 * <p>One atlas (made by the chain wings build script, layout below): the feathers in their own colour (region A) and in grey
 * for recolours (B), the chrome on its own (C, never tinted), the flow tiles and metal strips. The mesh is a grid over the
 * wing; {@link ChainWingsCells} says which cells lie fully inside a feather (the flowing layers go only there, so they
 * never spill past an edge) and which are chrome. Plain Java - no Minecraft imports; the renderer turns {@link Pass}es
 * into draws.
 */
public final class ChainWings {

	public static final String ID = "chainwings";

	// Atlas layout, from build3.py.
	static final int AW = 4096, AH = 2048, BX = 1016, CXR = 2032, RW = 1008, RH = 1500, K = 3;
	static final int CX0 = 20, CY0 = 20, CELL = 4, GU = 84, GV = 125;
	static final int FLX = 3072, FLY = 0, FLW = 1024, FP = 1024, DKX = 0, DKY = 1520, DKW = 1024, DP = 256;
	static final int ST_X = 1100, ST_Y = 1520, ST_W = 16, ST_H = 256;
	static final float PIV_X = 318f, PIV_Y = 268f;
	static final float[] ANCHORS = {242f, 244f, 296f, 304f};

	/** Draw layers: cutout, alpha-blended (the dark currents), glow (full bright, blended). */
	public static final int CUTOUT = 0, BLEND = 1, GLOW = 2;
	/** The flow: one lap from the root to the tips every this many seconds. */
	private static final float LOOP = 2.2f;
	/** The glow's colour over the pair's own art. */
	private static final int OWN_GLOW = 0xFF6A72;

	private ChainWings() {
	}

	public static boolean is(String id) {
		return ID.equals(id);
	}

	/**
	 * One draw. {@code quads}: four vertex indices per quad into {@code data} ({x, y, z, nx, ny, nz} per vertex, the
	 * body's space) and {@code uv}; {@code colors}: ARGB per vertex (null = white). {@code key}/{@code version}: for the
	 * GPU path - same key = same buffer, new version = new contents.
	 */
	public record Pass(int layer, int[] quads, int vertexCount, float[] data, float[] uv, int[] colors, Object key, Object version) {
	}

	/** Where the wearer is, to swing the chains in the world: position and body yaw (degrees). Null in previews. */
	public record World(double x, double y, double z, float bodyYaw) {
	}

	// ---- per model: the cells, the flow coordinates ----

	private static final class Half {
		int[] quads;
		/** Over 2n vertices: [0, n) lifted off the wing's front, [n, 2n) off its back; each side's quads face that way. */
		int[] innerOff;
		int[] chromeOff;
		float[] uvMono;
		float[] uvChrome;
		float[] radius;
		float[] across;
		/** 0 at a feather's edge .. 1 well inside it (both lifted copies): the flowing layers fade by it. */
		float[] edge;
		int[] darkColors;
		int[] anchors = new int[2];
	}

	private static final Map<WingModel, Half[]> PREP = new WeakHashMap<>();

	private static boolean flag(String[] rows, int i, int j) {
		return j >= 0 && j < rows.length && i >= 0 && i < rows[j].length() && rows[j].charAt(i) == '1';
	}

	private static Half[] prep(WingModel model) {
		Half[] cached = PREP.get(model);
		if (cached != null) {
			return cached;
		}
		Half[] out = new Half[model.halves.length];
		float rMax = 1e-3f;
		float aMin = Float.MAX_VALUE;
		float aMax = -Float.MAX_VALUE;
		for (int h = 0; h < out.length; h++) {
			WingModel.Half src = model.halves[h];
			Half p = new Half();
			int n = src.vertexCount();
			float[] cx = new float[n];
			float[] cy = new float[n];
			p.uvMono = new float[n * 2];
			p.uvChrome = new float[n * 2];
			p.radius = new float[n];
			p.across = new float[n];
			for (int v = 0; v < n; v++) {
				float u = src.uv[v * 2];
				float w = src.uv[v * 2 + 1];
				cx[v] = CX0 + u * AW / K;
				cy[v] = CY0 + w * AH / K;
				p.uvMono[v * 2] = u + (float) BX / AW;
				p.uvMono[v * 2 + 1] = w;
				p.uvChrome[v * 2] = u + (float) CXR / AW;
				p.uvChrome[v * 2 + 1] = w;
				float dx = cx[v] - PIV_X;
				float dy = cy[v] - PIV_Y;
				p.radius[v] = (float) Math.sqrt(dx * dx + dy * dy);
				p.across[v] = (float) Math.atan2(dy, -dx);
				rMax = Math.max(rMax, p.radius[v]);
				aMin = Math.min(aMin, p.across[v]);
				aMax = Math.max(aMax, p.across[v]);
			}
			// The grid was written as two triangles per cell (a b c, a c d), a = the cell's top-left corner.
			int[] t = src.triangles;
			int cells = t.length / 6;
			p.quads = new int[cells * 4];
			List<Integer> inner = new ArrayList<>();
			List<Integer> chrome = new ArrayList<>();
			for (int c = 0; c < cells; c++) {
				int a = t[c * 6];
				int b = t[c * 6 + 1];
				int cc = t[c * 6 + 2];
				int d = t[c * 6 + 5];
				p.quads[c * 4] = a;
				p.quads[c * 4 + 1] = b;
				p.quads[c * 4 + 2] = cc;
				p.quads[c * 4 + 3] = d;
				int ci = Math.round((cx[a] - CX0) / CELL);
				int cj = Math.round((cy[a] - CY0) / CELL);
				List<Integer> into = flag(ChainWingsCells.INNER, ci, cj) ? inner : flag(ChainWingsCells.CHROME, ci, cj) ? chrome : null;
				if (into != null) {
					into.add(a);
					into.add(b);
					into.add(cc);
					into.add(d);
				}
			}
			p.edge = new float[n * 2];
			p.darkColors = new int[n * 2];
			for (int v = 0; v < n; v++) {
				int ci = Math.round((cx[v] - CX0) / CELL);
				int cj = Math.round((cy[v] - CY0) / CELL);
				float e = cj >= 0 && cj < ChainWingsCells.EDGE.length && ci >= 0 && ci < ChainWingsCells.EDGE[cj].length()
						? (ChainWingsCells.EDGE[cj].charAt(ci) - '0') / 9f : 0f;
				p.edge[v] = p.edge[v + n] = e;
				p.darkColors[v] = p.darkColors[v + n] = Math.round(255 * e) << 24;
			}
			p.innerOff = lifted(inner, src.geometry, n);
			p.chromeOff = lifted(chrome, src.geometry, n);
			for (int k = 0; k < 2; k++) {
				float best = Float.MAX_VALUE;
				for (int v = 0; v < n; v++) {
					float dx = cx[v] - ANCHORS[k * 2];
					float dy = cy[v] - ANCHORS[k * 2 + 1];
					if (dx * dx + dy * dy < best) {
						best = dx * dx + dy * dy;
						p.anchors[k] = v;
					}
				}
			}
			out[h] = p;
		}
		for (Half p : out) {
			for (int v = 0; v < p.radius.length; v++) {
				p.radius[v] /= rMax;
				p.across[v] = (p.across[v] - aMin) / Math.max(1e-3f, aMax - aMin);
			}
		}
		PREP.put(model, out);
		return out;
	}

	/**
	 * The overlay layers sit a hair off the wing (coplanar they z-fight): each quad twice - its copy facing the wing's
	 * front over the front-lifted vertices, its reversed copy over the back-lifted ones (+n).
	 */
	private static int[] lifted(List<Integer> q, float[] geo, int n) {
		int[] out = new int[q.size() * 2];
		int half = q.size();
		for (int i = 0; i < half; i += 4) {
			int a = q.get(i);
			int b = q.get(i + 1);
			int c = q.get(i + 2);
			int d = q.get(i + 3);
			// does the quad's winding face along the vertex normal?
			float ux = geo[b * 6] - geo[a * 6];
			float uy = geo[b * 6 + 1] - geo[a * 6 + 1];
			float uz = geo[b * 6 + 2] - geo[a * 6 + 2];
			float vx = geo[d * 6] - geo[a * 6];
			float vy = geo[d * 6 + 1] - geo[a * 6 + 1];
			float vz = geo[d * 6 + 2] - geo[a * 6 + 2];
			float fx = uy * vz - uz * vy;
			float fy = uz * vx - ux * vz;
			float fz = ux * vy - uy * vx;
			boolean along = fx * geo[a * 6 + 3] + fy * geo[a * 6 + 4] + fz * geo[a * 6 + 5] >= 0;
			int f = along ? 0 : n;
			int r = along ? n : 0;
			out[i] = a + f;
			out[i + 1] = b + f;
			out[i + 2] = c + f;
			out[i + 3] = d + f;
			out[half + i] = d + r;
			out[half + i + 1] = c + r;
			out[half + i + 2] = b + r;
			out[half + i + 3] = a + r;
		}
		return out;
	}

	/** The quads front and back (the glow layer's render type culls back faces). */
	private static int[] bothSides(int[] q) {
		int[] out = new int[q.length * 2];
		System.arraycopy(q, 0, out, 0, q.length);
		for (int i = 0; i < q.length; i += 4) {
			out[q.length + i] = q[i + 3];
			out[q.length + i + 1] = q[i + 2];
			out[q.length + i + 2] = q[i + 1];
			out[q.length + i + 3] = q[i];
		}
		return out;
	}

	// ---- per wearer ----

	private static final class Wearer {
		Rope[] ropes;
		float ropeScale;
		boolean worldMode;
		long lastNanos;
		long lastSeen;
		float[][] flat;
		float[][] over;
		float[][] uvGlow;
		float[][] uvDark;
		float[][] uvShine;
		int[][] glowColors;
		int glowRgb = -1;
	}

	private static final Map<Object, Wearer> WEARERS = new HashMap<>();

	/**
	 * This frame's draws for one wearer: the wing (own colour or tinted), the chrome, the flowing dark currents and glow,
	 * the chrome's sheen, and the chains.
	 *
	 * @param geometry the posed halves ({@link WingAnimator#deform}), body space
	 * @param seconds  the pair's clock ({@link WingAnimator.Pose#seconds})
	 * @param tint     the wearer's colour (RGB), or -1 for the pair's own
	 * @param world    where the wearer stands, or null (previews: the chains just hang)
	 * @param scale    the pair's size on the body (type scale x wearer's size)
	 * @param lite     Cosmetics Quality: no flow, sheen or chains
	 */
	public static List<Pass> frame(Object wearerKey, WingModel model, float[][] geometry, float seconds, int tint, World world, float scale,
			boolean lite) {
		Half[] prep = prep(model);
		long now = System.nanoTime();
		Wearer w = WEARERS.computeIfAbsent(wearerKey, k -> new Wearer());
		w.lastSeen = now;
		if (WEARERS.size() > 64) {
			WEARERS.values().removeIf(x -> now - x.lastSeen > 10_000_000_000L);
		}
		boolean own = tint < 0;
		int base = own ? 0xFFFFFFFF : 0xFF000000 | tint;
		int glowRgb = own ? OWN_GLOW : lift(tint, 0.45f);
		if (w.flat == null || w.flat.length != prep.length) {
			w.flat = new float[prep.length][];
			w.over = new float[prep.length][];
			w.uvGlow = new float[prep.length][];
			w.uvDark = new float[prep.length][];
			w.uvShine = new float[prep.length][];
			w.glowColors = new int[prep.length][];
		}
		boolean recolour = w.glowRgb != glowRgb;
		w.glowRgb = glowRgb;
		float tt = seconds / LOOP;
		Object version = new Object();
		List<Pass> passes = new ArrayList<>();
		for (int h = 0; h < prep.length; h++) {
			Half p = prep[h];
			WingModel.Half src = model.halves[h];
			int n = src.vertexCount();
			float[] g = geometry[h];
			// Normals straight up: the wing is light itself, never shaded darker by the entity lighting.
			float[] flat = w.flat[h];
			if (flat == null || flat.length != g.length) {
				flat = w.flat[h] = new float[g.length];
			}
			for (int v = 0; v < n; v++) {
				int i = v * 6;
				flat[i] = g[i];
				flat[i + 1] = g[i + 1];
				flat[i + 2] = g[i + 2];
				flat[i + 3] = 0;
				flat[i + 4] = -1;
				flat[i + 5] = 0;
			}
			passes.add(new Pass(CUTOUT, p.quads, n, flat, own ? src.uv : p.uvMono, own ? null : fill(n, base), List.of(wearerKey, "wing", h, own), version));
			passes.add(new Pass(CUTOUT, p.quads, n, flat, p.uvChrome, null, List.of(wearerKey, "chrome", h), version));
			if (lite) {
				continue;
			}
			float[] glow = w.uvGlow[h] = arr(w.uvGlow[h], n * 4);
			float[] dark = w.uvDark[h] = arr(w.uvDark[h], n * 4);
			float[] shine = w.uvShine[h] = arr(w.uvShine[h], n * 4);
			float[] over = w.over[h] = arr(w.over[h], n * 12);
			float lift = 0.004f * scale;
			for (int v = 0; v < n; v++) {
				int i = v * 6;
				float nx = g[i + 3];
				float ny = g[i + 4];
				float nz = g[i + 5];
				for (int side = 0; side < 2; side++) {
					int o = (v + side * n) * 6;
					float e = side == 0 ? lift : -lift;
					over[o] = g[i] + nx * e;
					over[o + 1] = g[i + 1] + ny * e;
					over[o + 2] = g[i + 2] + nz * e;
					over[o + 3] = 0;
					over[o + 4] = -1;
					over[o + 5] = 0;
				}
			}
			float offGlow = 1 - frac(tt);
			float offDark = 1 - frac(tt * 0.7f + 0.31f);
			float offShine = 1 - frac(tt * 0.35f + 0.6f);
			for (int v = 0; v < n; v++) {
				float a = p.across[v];
				float r = p.radius[v];
				float gu = (FLX + 4 + a * (FLW - 8)) / AW;
				float gv = (FLY + FP * (r * 0.999f + offGlow)) / AH;
				float du = (DKX + 4 + a * (DKW - 8)) / AW;
				float dv = (DKY + DP * (r * 0.999f + offDark)) / AH;
				// the chrome's sheen: the same wisps, slower and from another part of the tile
				float su = (FLX + 4 + frac(a * 0.5f + 0.5f) * (FLW - 8)) / AW;
				float sv = (FLY + FP * (r * 0.999f + offShine)) / AH;
				for (int side = 0; side < 2; side++) {
					int o = (v + side * n) * 2;
					glow[o] = gu;
					glow[o + 1] = gv;
					dark[o] = du;
					dark[o + 1] = dv;
					shine[o] = su;
					shine[o + 1] = sv;
				}
			}
			int[] gc = w.glowColors[h];
			if (gc == null || gc.length != n * 2 || recolour) {
				gc = w.glowColors[h] = new int[n * 2];
				for (int v = 0; v < n * 2; v++) {
					gc[v] = Math.round(255 * p.edge[v]) << 24 | glowRgb;
				}
			}
			passes.add(new Pass(BLEND, p.innerOff, n * 2, over, dark, p.darkColors, List.of(wearerKey, "dark", h), version));
			passes.add(new Pass(GLOW, p.innerOff, n * 2, over, glow, gc, List.of(wearerKey, "glow", h), version));
			passes.add(new Pass(GLOW, p.chromeOff, n * 2, over, shine, fill(n * 2, 0xC0FFFFFF), List.of(wearerKey, "shine", h), version));
		}
		if (!lite) {
			chains(w, model, prep, geometry, world, scale, own, base, glowRgb, tt, now, wearerKey, passes);
		}
		return passes;
	}

	private static float[] arr(float[] old, int n) {
		return old != null && old.length == n ? old : new float[n];
	}

	private static final Map<Long, int[]> FILLS = new HashMap<>();

	/** A shared array of {@code n} times one colour. */
	private static synchronized int[] fill(int n, int argb) {
		if (FILLS.size() > 256) {
			FILLS.clear();
		}
		return FILLS.computeIfAbsent((long) n << 32 | (argb & 0xFFFFFFFFL), k -> {
			int[] a = new int[n];
			java.util.Arrays.fill(a, argb);
			return a;
		});
	}

	/** {@code rgb} lifted {@code f} of the way to white. */
	private static int lift(int rgb, float f) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		return Math.round(r + (255 - r) * f) << 16 | Math.round(g + (255 - g) * f) << 8 | Math.round(b + (255 - b) * f);
	}

	private static float frac(float x) {
		return x - (float) Math.floor(x);
	}

	// ---- chains ----

	/** One chain: verlet nodes (in the world, or in the body's space in previews). */
	private static final class Rope {
		final int half;
		final int side;
		final int which;
		final boolean blade;
		final float pitch;
		final double[] p;
		final double[] q;
		final double[] anchor = new double[3];
		final double[] lastAnchor = new double[3];
		boolean placed;

		Rope(int half, int side, int which, boolean blade, float length, float pitch) {
			this.half = half;
			this.side = side;
			this.which = which;
			this.blade = blade;
			this.pitch = pitch;
			int n = Math.max(3, Math.round(length / pitch) + 1);
			p = new double[n * 3];
			q = new double[n * 3];
		}

		int nodes() {
			return p.length / 3;
		}
	}

	/** Concept px per block, and the chains' lengths there (the outer one carries the blade). */
	private static final float PX = 260f;
	private static final float[] LENGTHS = {160 / PX, 250 / PX};
	private static final float LINK_PITCH = 22 / PX;
	/** Link proportions (link lengths): width, wire radius, the stud across the middle. */
	private static final float LINK_W = 0.66f;
	private static final float LINK_WIRE = 0.13f;
	private static final float STUD = 0.09f;
	private static final float BLADE = 0.42f;
	private static final double GRAVITY = 14.0;
	private static final double STEP = 1 / 120.0;

	private static void chains(Wearer w, WingModel model, Half[] prep, float[][] geometry, World world, float scale, boolean own, int tintArgb,
			int glowRgb, float tt, long now, Object key, List<Pass> out) {
		if (prep.length < 2) {
			return;
		}
		boolean worldMode = world != null;
		if (w.ropes == null || w.ropeScale != scale || w.worldMode != worldMode) {
			w.ropes = new Rope[4];
			int k = 0;
			for (int h = 0; h < 2; h++) {
				for (int c = 0; c < 2; c++) {
					w.ropes[k++] = new Rope(h, model.halves[h].side, c, c == 0, LENGTHS[c] * scale, LINK_PITCH * scale);
				}
			}
			w.ropeScale = scale;
			w.worldMode = worldMode;
			w.lastNanos = 0;
		}
		double dt = w.lastNanos == 0 ? 0 : Math.min(0.06, (now - w.lastNanos) / 1e9);
		w.lastNanos = now;
		double[] tmp = new double[3];
		for (Rope r : w.ropes) {
			int v = prep[r.half].anchors[r.which];
			float[] g = geometry[r.half];
			// Under the wing (+z = towards the wearer's back).
			toSim(world, g[v * 6], g[v * 6 + 1], g[v * 6 + 2] + 0.02 * scale, tmp);
			System.arraycopy(r.anchor, 0, r.lastAnchor, 0, 3);
			System.arraycopy(tmp, 0, r.anchor, 0, 3);
			if (!r.placed) {
				double[] down = worldMode ? new double[] {0, -1, 0} : new double[] {0, 1, 0};
				for (int i = 0; i < r.nodes(); i++) {
					for (int a = 0; a < 3; a++) {
						r.p[i * 3 + a] = r.anchor[a] + down[a] * i * r.pitch;
						r.q[i * 3 + a] = r.p[i * 3 + a];
					}
				}
				System.arraycopy(r.anchor, 0, r.lastAnchor, 0, 3);
				r.placed = true;
			}
		}
		int steps = (int) Math.min(8, Math.ceil(dt / STEP));
		double h = steps == 0 ? 0 : dt / steps;
		double gy = worldMode ? -GRAVITY : GRAVITY;
		for (int s = 1; s <= steps; s++) {
			double f = s / (double) steps;
			for (Rope r : w.ropes) {
				step(r, f, h, gy, world);
			}
		}
		chainMesh(w, world, scale, own, tintArgb, glowRgb, tt, key, out);
	}

	private static void step(Rope r, double f, double h, double gy, World world) {
		int n = r.nodes();
		double[] p = r.p;
		double[] q = r.q;
		double drag = 0.985;
		for (int i = 1; i < n; i++) {
			int o = i * 3;
			for (int a = 0; a < 3; a++) {
				double vel = (p[o + a] - q[o + a]) * drag;
				q[o + a] = p[o + a];
				p[o + a] += vel + (a == 1 ? gy * h * h : 0);
			}
		}
		for (int a = 0; a < 3; a++) {
			p[a] = r.lastAnchor[a] + (r.anchor[a] - r.lastAnchor[a]) * f;
			q[a] = p[a];
		}
		for (int it = 0; it < 8; it++) {
			for (int i = 0; i + 1 < n; i++) {
				int o = i * 3;
				double dx = p[o + 3] - p[o];
				double dy = p[o + 4] - p[o + 1];
				double dz = p[o + 5] - p[o + 2];
				double len = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-9;
				// The anchor doesn't move; the blade at the end is heavier.
				double w0 = i == 0 ? 0 : 1;
				double w1 = r.blade && i + 1 == n - 1 ? 0.3 : 1;
				double k = (len - r.pitch) / len / (w0 + w1);
				p[o] += dx * k * w0;
				p[o + 1] += dy * k * w0;
				p[o + 2] += dz * k * w0;
				p[o + 3] -= dx * k * w1;
				p[o + 4] -= dy * k * w1;
				p[o + 5] -= dz * k * w1;
			}
		}
		// Keep out of the body: the chains hang behind the wearer's back, never through it.
		double[] m = new double[3];
		for (int i = 1; i < n; i++) {
			int o = i * 3;
			toBody(world, p[o], p[o + 1], p[o + 2], m);
			if (Math.abs(m[0]) < 0.3 && m[1] > -0.15 && m[1] < 1.55 && m[2] < 0.15) {
				m[2] = 0.15;
				toSim(world, m[0], m[1], m[2], m);
				p[o] = m[0];
				p[o + 1] = m[1];
				p[o + 2] = m[2];
			}
		}
	}

	/**
	 * Body space (the renderer's: x sideways, y down, z backwards, from the neck) to where the chains are simulated:
	 * the world - undoing what the player renderer does (yaw, flip, 15/16 scale, 1.501 down) - or body space itself.
	 */
	private static void toSim(World world, double x, double y, double z, double[] out) {
		if (world == null) {
			out[0] = x;
			out[1] = y;
			out[2] = z;
			return;
		}
		double s = 0.9375;
		double mx = -x * s;
		double my = -(y - 1.501) * s;
		double mz = z * s;
		double th = Math.toRadians(180 - world.bodyYaw());
		double c = Math.cos(th);
		double sn = Math.sin(th);
		out[0] = world.x() + mx * c + mz * sn;
		out[1] = world.y() + my;
		out[2] = world.z() - mx * sn + mz * c;
	}

	private static void toBody(World world, double x, double y, double z, double[] out) {
		if (world == null) {
			out[0] = x;
			out[1] = y;
			out[2] = z;
			return;
		}
		double s = 0.9375;
		double dx = x - world.x();
		double dy = y - world.y();
		double dz = z - world.z();
		double th = Math.toRadians(180 - world.bodyYaw());
		double c = Math.cos(th);
		double sn = Math.sin(th);
		double mx = dx * c - dz * sn;
		double mz = dx * sn + dz * c;
		out[0] = -mx / s;
		out[1] = 1.501 - dy / s;
		out[2] = mz / s;
	}

	// Link: an oval ring of wire (U round the ring, V round the wire, +1 for the seam) and a stud across its middle.
	private static final int LU = 10;
	private static final int LV = 5;
	private static final int RING_VERTS = LU * (LV + 1);
	private static final int RING_QUADS = LU * LV;
	private static final int STUD_VERTS = 2 * 5;
	private static final int STUD_QUADS = 4;
	private static final int LINK_VERTS = RING_VERTS + STUD_VERTS;
	private static final int LINK_QUADS = RING_QUADS + STUD_QUADS;
	// Blade: S rows of 5 (outer edge, ridge, inner edge, ridge, outer again for the seam).
	private static final int BS = 18;
	private static final int BLADE_VERTS = (BS + 1) * 5;
	private static final int BLADE_QUADS = BS * 4;

	private static void chainMesh(Wearer w, World world, float scale, boolean own, int tintArgb, int glowRgb, float tt, Object key, List<Pass> out) {
		int links = 0;
		int blades = 0;
		for (Rope r : w.ropes) {
			links += r.nodes() - 1;
			if (r.blade) {
				blades++;
			}
		}
		// each blade also has a plain ring on top
		int verts = links * LINK_VERTS + blades * (RING_VERTS + BLADE_VERTS);
		int quadCount = links * LINK_QUADS + blades * (RING_QUADS + BLADE_QUADS);
		float[] data = new float[verts * 6];
		float[] uv = new float[verts * 2];
		float[] uvGlow = new float[verts * 2];
		int[] colors = new int[verts];
		int[] quads = new int[quadCount * 4];
		int[] bladeQuads = new int[blades * BLADE_QUADS * 4];
		int[] cursor = {0, 0, 0};
		double[] a = new double[3];
		double[] b = new double[3];
		float glint = frac(tt * 0.6f);
		for (Rope r : w.ropes) {
			int n = r.nodes();
			float linkLen = r.pitch / 0.8f;
			for (int i = 0; i + 1 < n; i++) {
				toBody(world, r.p[i * 3], r.p[i * 3 + 1], r.p[i * 3 + 2], a);
				toBody(world, r.p[i * 3 + 3], r.p[i * 3 + 4], r.p[i * 3 + 5], b);
				// a light running down the chain
				float x = i / (float) n - glint;
				x -= Math.round(x);
				int lum = Math.round(225 + 30 * (float) Math.exp(-x * x / 0.004f));
				int argb = 0xFF000000 | lum << 16 | lum << 8 | lum;
				link(a, b, linkLen, i % 2 == 1, argb, data, uv, colors, quads, cursor);
			}
			if (r.blade) {
				toBody(world, r.p[(n - 2) * 3], r.p[(n - 2) * 3 + 1], r.p[(n - 2) * 3 + 2], a);
				toBody(world, r.p[(n - 1) * 3], r.p[(n - 1) * 3 + 1], r.p[(n - 1) * 3 + 2], b);
				blade(a, b, BLADE * scale, r.side, own, tintArgb, tt, linkLen, data, uv, uvGlow, colors, quads, bladeQuads, cursor);
			}
		}
		Object version = new Object();
		out.add(new Pass(CUTOUT, quads, verts, data, uv, colors, List.of(key, "chains"), version));
		if (blades > 0) {
			out.add(new Pass(GLOW, bothSides(bladeQuads), verts, data, uvGlow, fill(verts, 0xFF000000 | glowRgb), List.of(key, "bladeglow"), version));
		}
	}

	/** One chain link from {@code a} to {@code b} (body space); {@code turned}: every other link stands at right angles. */
	private static void link(double[] a, double[] b, float len, boolean turned, int argb, float[] data, float[] uv, int[] colors, int[] quads,
			int[] cursor) {
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double dz = b[2] - a[2];
		double l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-9;
		dx /= l;
		dy /= l;
		dz /= l;
		double[] xa = perpendicular(dx, dy, dz, 1, 0, 0);
		if (turned) {
			xa = cross(dx, dy, dz, xa[0], xa[1], xa[2]);
		}
		double[] ya = cross(dx, dy, dz, xa[0], xa[1], xa[2]);
		double cx = (a[0] + b[0]) / 2;
		double cy = (a[1] + b[1]) / 2;
		double cz = (a[2] + b[2]) / 2;
		double rx = len * LINK_W / 2;
		double wire = len * LINK_WIRE;
		double[] za = {dx, dy, dz};
		ring(cx, cy, cz, xa, ya, za, rx, len / 2, wire, 0, argb, data, uv, colors, quads, cursor);
		// the stud: a short bar across the middle, from wire to wire
		stud(cx, cy, cz, xa, ya, za, Math.max(wire * 1.2, rx - wire), len * STUD, argb, data, uv, colors, quads, cursor);
	}

	/** An oval wire ring round centre c: half-width rx along xa, half-length rz along za, wire radius {@code wire}. */
	private static void ring(double cx, double cy, double cz, double[] xa, double[] ya, double[] za, double rx, double rz, double wire, int strip,
			int argb, float[] data, float[] uv, int[] colors, int[] quads, int[] cursor) {
		int v0 = cursor[0];
		rx = Math.max(wire * 1.2, rx - wire);
		rz = Math.max(wire * 1.2, rz - wire);
		float u = (ST_X + strip * (ST_W + 4) + ST_W * 0.5f) / AW;
		for (int i = 0; i < LU; i++) {
			double phi = Math.PI * 2 * i / LU;
			double px = rx * Math.cos(phi);
			double pz = rz * Math.sin(phi);
			double ox = Math.cos(phi) / rx;
			double oz = Math.sin(phi) / rz;
			double ol = Math.sqrt(ox * ox + oz * oz);
			ox /= ol;
			oz /= ol;
			for (int j = 0; j <= LV; j++) {
				double al = Math.PI * 2 * j / LV;
				double nx = ox * Math.cos(al);
				double ny = Math.sin(al);
				double nz = oz * Math.cos(al);
				double lx = px + nx * wire;
				double ly = ny * wire;
				double lz = pz + nz * wire;
				put(cursor[0]++, cx + xa[0] * lx + ya[0] * ly + za[0] * lz, cy + xa[1] * lx + ya[1] * ly + za[1] * lz,
						cz + xa[2] * lx + ya[2] * ly + za[2] * lz, xa[0] * nx + ya[0] * ny + za[0] * nz, xa[1] * nx + ya[1] * ny + za[1] * nz,
						xa[2] * nx + ya[2] * ny + za[2] * nz, u, (ST_Y + 0.5f + (ST_H - 1) * (j / (float) LV)) / AH, argb, data, uv, colors);
			}
		}
		for (int i = 0; i < LU; i++) {
			int i2 = (i + 1) % LU;
			for (int j = 0; j < LV; j++) {
				int q = cursor[1]++ * 4;
				quads[q] = v0 + i * (LV + 1) + j;
				quads[q + 1] = v0 + i2 * (LV + 1) + j;
				quads[q + 2] = v0 + i2 * (LV + 1) + j + 1;
				quads[q + 3] = v0 + i * (LV + 1) + j + 1;
			}
		}
	}

	/** The stud: a four-sided bar along xa through the centre, half-length {@code half}, radius {@code r}. */
	private static void stud(double cx, double cy, double cz, double[] xa, double[] ya, double[] za, double half, double r, int argb, float[] data,
			float[] uv, int[] colors, int[] quads, int[] cursor) {
		int v0 = cursor[0];
		float u = (ST_X + ST_W * 0.5f) / AW;
		for (int e = 0; e < 2; e++) {
			double s = e == 0 ? -half : half;
			for (int k = 0; k <= 4; k++) {
				double al = Math.PI * 2 * k / 4 + Math.PI / 4;
				double ny = Math.cos(al);
				double nz = Math.sin(al);
				put(cursor[0]++, cx + xa[0] * s + (ya[0] * ny + za[0] * nz) * r, cy + xa[1] * s + (ya[1] * ny + za[1] * nz) * r,
						cz + xa[2] * s + (ya[2] * ny + za[2] * nz) * r, ya[0] * ny + za[0] * nz, ya[1] * ny + za[1] * nz, ya[2] * ny + za[2] * nz,
						u, (ST_Y + 0.5f + (ST_H - 1) * (k / 4f)) / AH, argb, data, uv, colors);
			}
		}
		for (int k = 0; k < 4; k++) {
			int q = cursor[1]++ * 4;
			quads[q] = v0 + k;
			quads[q + 1] = v0 + 5 + k;
			quads[q + 2] = v0 + 5 + k + 1;
			quads[q + 3] = v0 + k + 1;
		}
	}

	private static void put(int v, double x, double y, double z, double nx, double ny, double nz, float u, float w, int argb, float[] data,
			float[] uv, int[] colors) {
		data[v * 6] = (float) x;
		data[v * 6 + 1] = (float) y;
		data[v * 6 + 2] = (float) z;
		data[v * 6 + 3] = (float) nx;
		data[v * 6 + 4] = (float) ny;
		data[v * 6 + 5] = (float) nz;
		uv[v * 2] = u;
		uv[v * 2 + 1] = w;
		colors[v] = argb;
	}

	/**
	 * The sickle blade hanging from the chain's last node {@code b} (last link from {@code a}): it hangs down, then sweeps
	 * out to the wearer's side into a needle tip; keen convex edge, three saw teeth on the inner curve, a ridge down the
	 * middle; a ring on top joins it to the chain. Its faces carry the wing's own feather art, and the plasma glows over them.
	 */
	private static void blade(double[] a, double[] b, float size, int side, boolean own, int tintArgb, float tt, float linkLen, float[] data,
			float[] uv, float[] uvGlow, int[] colors, int[] quads, int[] bladeQuads, int[] cursor) {
		double hx = b[0] - a[0];
		double hy = b[1] - a[1];
		double hz = b[2] - a[2];
		double hl = Math.sqrt(hx * hx + hy * hy + hz * hz) + 1e-9;
		hx /= hl;
		hy /= hl;
		hz /= hl;
		double[] sa = perpendicular(hx, hy, hz, side, 0, 0);
		double[] ta = cross(hx, hy, hz, sa[0], sa[1], sa[2]);
		double ringLen = linkLen * 0.9;
		ring(b[0] + hx * ringLen * 0.35, b[1] + hy * ringLen * 0.35, b[2] + hz * ringLen * 0.35, ta, sa, new double[] {hx, hy, hz},
				ringLen * 0.55 / 2, ringLen / 2, ringLen * LINK_WIRE, 2, 0xFFFFFFFF, data, uv, colors, quads, cursor);
		double ox = b[0] + hx * ringLen * 0.8;
		double oy = b[1] + hy * ringLen * 0.8;
		double oz = b[2] + hz * ringLen * 0.8;
		int v0 = cursor[0];
		int argb = own ? 0xFFFFFFFF : tintArgb;
		int regionX = own ? 0 : BX;
		float offGlow = 1 - frac(tt * 1.4f);
		double radius = 0.46;
		double a0 = Math.toRadians(180);
		double a1 = Math.toRadians(180 + 112);
		for (int k = 0; k <= BS; k++) {
			double t = k / (double) BS;
			double ang = a0 + (a1 - a0) * t;
			double spx = radius + radius * Math.cos(ang);
			double spz = -0.10 + radius * Math.sin(ang);
			double onx = Math.cos(ang);
			double onz = Math.sin(ang);
			double wd = 0.07 * (1 - t) + 0.25 * Math.sin(Math.PI * Math.pow(Math.min(1.0, t * 1.3), 0.65)) * Math.pow(1 - t, 0.75);
			double tooth = 0;
			for (double c0 : new double[] {0.26, 0.42, 0.58}) {
				double x = (t - c0) / 0.07;
				if (x > -1 && x < 0.25) {
					tooth = Math.max(tooth, 0.06 * (1 - t * 0.6) * (x < 0 ? 1 + x : 1 - x / 0.25));
				}
			}
			double th = 0.026 * Math.pow(1 - t, 0.45) + 0.003;
			double[][] pts = {
					{spx + onx * wd * 0.62, 0, spz + onz * wd * 0.62},
					{spx + onx * wd * 0.12, th, spz + onz * wd * 0.12},
					{spx - onx * (wd * 0.38 + tooth), 0, spz - onz * (wd * 0.38 + tooth)},
					{spx + onx * wd * 0.12, -th, spz + onz * wd * 0.12},
					{spx + onx * wd * 0.62, 0, spz + onz * wd * 0.62}};
			double[][] nrm = {{onx, 0, onz}, {0, 1, 0}, {-onx, 0, -onz}, {0, -1, 0}, {onx, 0, onz}};
			for (int c = 0; c < 5; c++) {
				int v = cursor[0];
				double bx = pts[c][0] * size;
				double by = pts[c][1] * size;
				double bz = pts[c][2] * size;
				// the feather art along the blade: a bright stretch of feather C (concept x 210 -> 110 at y ~ 214..230)
				float s = c == 2 ? 1f : c == 0 || c == 4 ? 0f : 0.5f;
				float tx = (float) (210 - t * 100 - CX0) * K + regionX;
				float ty = (214 + s * 16 - CY0) * K;
				put(v, ox + sa[0] * bx + ta[0] * by - hx * bz, oy + sa[1] * bx + ta[1] * by - hy * bz, oz + sa[2] * bx + ta[2] * by - hz * bz,
						sa[0] * nrm[c][0] + ta[0] * nrm[c][1] - hx * nrm[c][2], sa[1] * nrm[c][0] + ta[1] * nrm[c][1] - hy * nrm[c][2],
						sa[2] * nrm[c][0] + ta[2] * nrm[c][1] - hz * nrm[c][2], tx / AW, ty / AH, argb, data, uv, colors);
				uvGlow[v * 2] = (FLX + 4 + (0.3f + s * 0.15f) * (FLW - 8)) / AW;
				uvGlow[v * 2 + 1] = (FLY + FP * ((float) t * 0.5f + offGlow)) / AH;
				cursor[0]++;
			}
		}
		for (int k = 0; k < BS; k++) {
			for (int c = 0; c < 4; c++) {
				int q = cursor[1]++ * 4;
				quads[q] = v0 + k * 5 + c;
				quads[q + 1] = v0 + (k + 1) * 5 + c;
				quads[q + 2] = v0 + (k + 1) * 5 + c + 1;
				quads[q + 3] = v0 + k * 5 + c + 1;
				System.arraycopy(quads, q, bladeQuads, cursor[2], 4);
				cursor[2] += 4;
			}
		}
	}

	private static double[] perpendicular(double dx, double dy, double dz, double rx, double ry, double rz) {
		double dot = rx * dx + ry * dy + rz * dz;
		double px = rx - dx * dot;
		double py = ry - dy * dot;
		double pz = rz - dz * dot;
		double l = Math.sqrt(px * px + py * py + pz * pz);
		if (l < 1e-4) {
			dot = dz;
			px = -dx * dot;
			py = -dy * dot;
			pz = 1 - dz * dot;
			l = Math.sqrt(px * px + py * py + pz * pz) + 1e-9;
		}
		return new double[] {px / l, py / l, pz / l};
	}

	private static double[] cross(double ax, double ay, double az, double bx, double by, double bz) {
		return new double[] {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
	}
}
