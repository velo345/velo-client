package net.veloclient.velo.client.cosmetics.wings;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A pair of 3D wings loaded from a bundled {@code .vmesh} (made from a Meshy .glb by
 * {@code tools/cosmetics/import_wings.py}): two halves, each with its own shoulder hinge, already
 * converted to the body's axes (x sideways, y down, z backwards, in blocks) so the animator only
 * has to rotate them around their hinge.
 */
public final class WingModel {

	/** One wing. Positions are relative to the hinge; {@code weight} is 0 at the shoulder, 1 at the tip. */
	public static final class Half {
		/** Side in body space: -1 = the wearer's right (body -x), +1 = the left. */
		public final int side;
		/** {x, y, z, nx, ny, nz} per vertex, relative to the hinge, body axes. */
		public final float[] geometry;
		public final float[] uv;
		public final float[] weight;
		public final int[] triangles;
		/** Its arms ({@link WingRigs#arms}), or null. */
		public final WingRigs.Arms arms;
		/** Where the arms are posed each frame (render thread). */
		public final float[] posed;
		/** Which gear a vertex belongs to ({@link WingRigs}), -1 for the wing itself; null without gears. */
		public final int[] gear;
		/** Per gear: its centre (x, y) in this half's space and its {@link WingRigs.Gear#ratio}. */
		public final float[] gearPivot;
		public final float[] gearRatio;
		/** Per vertex: how much it glows ({@link WingRigs#mask}) and what its glow is keyed on; null without a glow. */
		public final float[] glowMask;
		public final float[] glowKey;
		/** The vertices particles start from; null without particles. */
		public final int[] sources;

		Half(int side, float[] geometry, float[] uv, float[] weight, int[] triangles) {
			this(side, geometry, uv, weight, triangles, null, null, null, null, null, null, null);
		}

		Half(int side, float[] geometry, float[] uv, float[] weight, int[] triangles, int[] gear, float[] gearPivot, float[] gearRatio,
				WingRigs.Arms arms, float[] glowMask, float[] glowKey, int[] sources) {
			this.side = side;
			this.arms = arms;
			this.posed = arms == null ? null : new float[geometry.length];
			this.glowMask = glowMask;
			this.glowKey = glowKey;
			this.sources = sources;
			this.gear = gear;
			this.gearPivot = gearPivot;
			this.gearRatio = gearRatio;
			this.geometry = geometry;
			this.uv = uv;
			this.weight = weight;
			this.triangles = triangles;
		}

		public int vertexCount() {
			return uv.length / 2;
		}
	}

	public final Half[] halves;
	/** How it shines ({@link WingRigs#shine}), or null. */
	public final WingRigs.Shine shine;
	/** What it does on top of its wingbeat ({@link WingRigs#effect}), or null. */
	public final WingRigs.Effect effect;

	private WingModel(Half[] halves) {
		this(halves, null, null);
	}

	private WingModel(Half[] halves, WingRigs.Shine shine, WingRigs.Effect effect) {
		this.halves = halves;
		this.shine = shine;
		this.effect = effect;
	}

	/**
	 * This model rigged the way pair {@code id} moves: its gears ({@link WingRigs#rig}) added to each half, its arms
	 * found ({@link WingRigs#arms}; tentacle-style pairs writhe), its glow picked out of {@code texture}
	 * ({@link WingRigs#effect}; null: no glow) and where its particles start, or set to shine ({@link WingRigs#shine}).
	 * Itself if it has none of those. {@code scale}: the pair's, as given to {@link #read}.
	 */
	public WingModel withRig(String id, float scale, boolean tentacle, WingRigs.Sampler texture) {
		WingModel geared = withGears(id, scale);
		WingRigs.Effect effect = WingRigs.effect(id);
		WingRigs.Motion motion = tentacle ? WingRigs.Motion.WRITHE : effect != null ? effect.motion() : null;
		WingRigs.Shine shine = WingRigs.shine(id);
		if (motion == null && effect == null && shine == null) {
			return geared;
		}
		Half[] out = new Half[geared.halves.length];
		for (int h = 0; h < out.length; h++) {
			Half half = geared.halves[h];
			int n = half.vertexCount();
			float[] g = half.geometry;
			float far = 1e-4f;
			for (int v = 0; v < n; v++) {
				far = Math.max(far, (float) Math.sqrt(g[v * 6] * g[v * 6] + g[v * 6 + 1] * g[v * 6 + 1] + g[v * 6 + 2] * g[v * 6 + 2]));
			}
			float[] mask = null;
			float[] key = null;
			if (effect != null && effect.glow() != null && texture != null) {
				mask = new float[n];
				key = new float[n];
				for (int v = 0; v < n; v++) {
					mask[v] = WingRigs.mask(effect.glow(), texture.argb(half.uv[v * 2], half.uv[v * 2 + 1]));
					float reach = (float) Math.sqrt(g[v * 6] * g[v * 6] + g[v * 6 + 1] * g[v * 6 + 1] + g[v * 6 + 2] * g[v * 6 + 2]) / far;
					key[v] = WingRigs.glowKey(effect.glow().pattern(), g[v * 6], g[v * 6 + 1], g[v * 6 + 2], half.side, reach);
				}
			}
			int[] sources = null;
			if (effect != null && effect.particles().length > 0) {
				java.util.List<Integer> picked = new java.util.ArrayList<>();
				for (int v = 0; v < n; v++) {
					float reach = (float) Math.sqrt(g[v * 6] * g[v * 6] + g[v * 6 + 1] * g[v * 6 + 1] + g[v * 6 + 2] * g[v * 6 + 2]) / far;
					boolean use = switch (effect.source()) {
						case GLOW -> mask != null && mask[v] > 0.5f;
						case TIPS -> reach > 0.6f;
						case ANY -> true;
					};
					if (use) {
						picked.add(v);
					}
				}
				if (picked.size() < 8) {
					picked.clear();
					for (int v = 0; v < n; v++) {
						picked.add(v);
					}
				}
				sources = picked.stream().mapToInt(Integer::intValue).toArray();
			}
			WingRigs.Arms arms = motion == null ? null : WingRigs.arms(g, 6, half.triangles, motion);
			out[h] = new Half(half.side, g, half.uv, half.weight, half.triangles, half.gear, half.gearPivot, half.gearRatio, arms, mask, key, sources);
		}
		return new WingModel(out, shine, effect);
	}

	/** This model with pair {@code id}'s gears ({@link WingRigs#rig}) added to each half, or itself if it has none. */
	private WingModel withGears(String id, float scale) {
		WingRigs.Rig rig = WingRigs.rig(id);
		if (rig == null) {
			return this;
		}
		Half[] out = new Half[halves.length];
		for (int h = 0; h < halves.length; h++) {
			Half half = halves[h];
			int base = half.vertexCount();
			WingRigs.GearMesh[] meshes = new WingRigs.GearMesh[rig.gears().length];
			int extra = 0;
			int extraTris = 0;
			for (int g = 0; g < meshes.length; g++) {
				meshes[g] = WingRigs.gearMesh(rig.gears()[g], rig, half.side, scale);
				extra += meshes[g].uv().length / 2;
				extraTris += meshes[g].triangles().length;
			}
			float[] geometry = java.util.Arrays.copyOf(half.geometry, (base + extra) * 6);
			float[] uv = java.util.Arrays.copyOf(half.uv, (base + extra) * 2);
			float[] weight = java.util.Arrays.copyOf(half.weight, base + extra);
			int[] triangles = java.util.Arrays.copyOf(half.triangles, half.triangles.length + extraTris);
			int[] gear = new int[base + extra];
			java.util.Arrays.fill(gear, -1);
			float[] pivot = new float[meshes.length * 2];
			float[] ratio = new float[meshes.length];
			int v0 = base;
			int t0 = half.triangles.length;
			for (int g = 0; g < meshes.length; g++) {
				WingRigs.Gear spec = rig.gears()[g];
				// Model space (y up, +z towards the wearer) -> body space, like read() does.
				float cx = half.side * spec.x() * scale;
				float cy = -spec.y() * scale;
				float cz = -spec.z() * scale;
				pivot[g * 2] = cx;
				pivot[g * 2 + 1] = cy;
				ratio[g] = spec.ratio();
				// The gear bends with the part of the wing it sits on.
				float w = 0;
				float best = Float.MAX_VALUE;
				for (int v = 0; v < base; v++) {
					float dx = half.geometry[v * 6] - cx;
					float dy = half.geometry[v * 6 + 1] - cy;
					float d = dx * dx + dy * dy;
					if (d < best) {
						best = d;
						w = half.weight[v];
					}
				}
				WingRigs.GearMesh m = meshes[g];
				int n = m.uv().length / 2;
				for (int v = 0; v < n; v++) {
					geometry[(v0 + v) * 6] = m.geometry()[v * 6] + cx;
					geometry[(v0 + v) * 6 + 1] = m.geometry()[v * 6 + 1] + cy;
					geometry[(v0 + v) * 6 + 2] = m.geometry()[v * 6 + 2] + cz;
					System.arraycopy(m.geometry(), v * 6 + 3, geometry, (v0 + v) * 6 + 3, 3);
					uv[(v0 + v) * 2] = m.uv()[v * 2];
					uv[(v0 + v) * 2 + 1] = m.uv()[v * 2 + 1];
					weight[v0 + v] = w;
					gear[v0 + v] = g;
				}
				for (int i = 0; i < m.triangles().length; i++) {
					triangles[t0 + i] = m.triangles()[i] + v0;
				}
				v0 += n;
				t0 += m.triangles().length;
			}
			out[h] = new Half(half.side, geometry, uv, weight, triangles, gear, pivot, ratio, null, null, null, null);
		}
		return new WingModel(out);
	}

	/** Reads {@code /assets/velo-client/cosmetics/wings/<id>.vmesh}, scaled to blocks. */
	public static WingModel load(String id, float scale) throws IOException {
		String path = "/assets/velo-client/cosmetics/wings/" + id + ".vmesh";
		try (InputStream raw = WingModel.class.getResourceAsStream(path)) {
			if (raw == null) {
				throw new IOException("Missing " + path);
			}
			return read(raw, path, scale);
		}
	}

	/** Reads a {@code .vmesh} from a stream (a downloaded one for server-only pairs), scaled to blocks. */
	public static WingModel read(InputStream raw, String path, float scale) throws IOException {
		{
			DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (!"VMSH".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)) || in.readInt() != 1) {
				throw new IOException("Not a wing mesh: " + path);
			}
			int count = in.readInt();
			if (count < 1 || count > 4) {
				throw new IOException("Damaged wing mesh: " + path);
			}
			Half[] halves = new Half[count];
			for (int h = 0; h < count; h++) {
				float hx = in.readFloat();
				float hy = in.readFloat();
				float hz = in.readFloat();
				float span = Math.max(1e-3f, in.readFloat());
				int vertices = in.readInt();
				if (vertices < 0 || vertices > 200_000) {
					throw new IOException("Damaged wing mesh: " + path);
				}
				float[] geometry = new float[vertices * 6];
				float[] uv = new float[vertices * 2];
				float[] weight = new float[vertices];
				float sideSum = 0;
				for (int v = 0; v < vertices; v++) {
					float px = in.readFloat() - hx;
					float py = in.readFloat() - hy;
					float pz = in.readFloat() - hz;
					float nx = in.readFloat();
					float ny = in.readFloat();
					float nz = in.readFloat();
					// Model space (y up, +z towards the wearer) -> body space (y down, +z backwards):
					// a half turn around x, which keeps left and right where they are.
					geometry[v * 6] = px * scale;
					geometry[v * 6 + 1] = -py * scale;
					geometry[v * 6 + 2] = -pz * scale;
					geometry[v * 6 + 3] = nx;
					geometry[v * 6 + 4] = -ny;
					geometry[v * 6 + 5] = -nz;
					uv[v * 2] = in.readFloat();
					uv[v * 2 + 1] = in.readFloat();
					weight[v] = Math.min(1f, Math.abs(px) / span);
					sideSum += px;
				}
				int indexCount = in.readInt();
				if (indexCount < 0 || indexCount % 3 != 0 || indexCount > 600_000) {
					throw new IOException("Damaged wing mesh: " + path);
				}
				int[] triangles = new int[indexCount];
				for (int i = 0; i < indexCount; i++) {
					int index = in.readInt();
					if (index < 0 || index >= vertices) {
						throw new IOException("Damaged wing mesh: " + path);
					}
					triangles[i] = index;
				}
				halves[h] = new Half(sideSum < 0 ? -1 : 1, geometry, uv, weight, triangles);
			}
			return new WingModel(halves);
		}
	}
}
