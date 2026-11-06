package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.emotes.EmoteProps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes the Meshy-made cosmetic meshes cheap to draw. They come with 2,000-21,000 triangles each (a crown: 21k) for
 * something a few pixels big on screen, and every triangle was sent as a quad every frame - one outfit was 200k+
 * vertices per frame per player, the biggest single cost in third person (2026-11-06 profile: 42% of the render
 * thread). {@link #simplify} merges vertices on a grid until the mesh fits a triangle budget (texture seams kept apart),
 * {@link #quads} pairs neighbouring triangles into real quads (entity render types draw quads).
 * Vertex layout (EmoteProps): x, y, z, nx, ny, nz, u, v.
 */
public final class MeshLod {

	private static final Map<EmoteProps.Mesh, int[]> QUADS = Collections.synchronizedMap(new WeakHashMap<>());

	private MeshLod() {
	}

	/** {@code mesh} with at most about {@code budget} triangles (unchanged if it already fits). */
	public static EmoteProps.Mesh simplify(EmoteProps.Mesh mesh, int budget) {
		int[] tris = mesh.triangles();
		if (tris.length / 3 <= budget) {
			return mesh;
		}
		float[] v = mesh.vertices();
		int n = v.length / 8;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			minX = Math.min(minX, v[i * 8]); maxX = Math.max(maxX, v[i * 8]);
			minY = Math.min(minY, v[i * 8 + 1]); maxY = Math.max(maxY, v[i * 8 + 1]);
			minZ = Math.min(minZ, v[i * 8 + 2]); maxZ = Math.max(maxZ, v[i * 8 + 2]);
		}
		float extent = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
		EmoteProps.Mesh best = mesh;
		// Finest grid first; coarser until it fits.
		for (int k = 160; k >= 8; k = (int) (k * 0.85f)) {
			EmoteProps.Mesh m = cluster(v, tris, n, minX, minY, minZ, extent / k);
			best = m;
			if (m.triangles().length / 3 <= budget) {
				break;
			}
		}
		return best;
	}

	private static EmoteProps.Mesh cluster(float[] v, int[] tris, int n, float ox, float oy, float oz, float cell) {
		Map<Long, Integer> clusters = new HashMap<>();
		int[] remap = new int[n];
		List<float[]> sums = new ArrayList<>();
		List<Integer> counts = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int o = i * 8;
			long cx = (long) Math.floor((v[o] - ox) / cell), cy = (long) Math.floor((v[o + 1] - oy) / cell), cz = (long) Math.floor((v[o + 2] - oz) / cell);
			// texture coordinates in the key too: vertices on either side of a UV seam must not be merged
			long cu = Math.round(v[o + 6] * 16), cv = Math.round(v[o + 7] * 16);
			long key = ((((cx * 4099 + cy) * 4099 + cz) * 1031 + cu) * 1031 + cv);
			Integer c = clusters.get(key);
			if (c == null) {
				c = sums.size();
				clusters.put(key, c);
				sums.add(new float[8]);
				counts.add(0);
			}
			float[] s = sums.get(c);
			for (int k = 0; k < 8; k++) {
				s[k] += v[o + k];
			}
			counts.set(c, counts.get(c) + 1);
			remap[i] = c;
		}
		float[] out = new float[sums.size() * 8];
		for (int c = 0; c < sums.size(); c++) {
			float[] s = sums.get(c);
			float count = counts.get(c);
			for (int k = 0; k < 8; k++) {
				out[c * 8 + k] = s[k] / count;
			}
			float nx = out[c * 8 + 3], ny = out[c * 8 + 4], nz = out[c * 8 + 5];
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1e-6f) {
				out[c * 8 + 3] = nx / len; out[c * 8 + 4] = ny / len; out[c * 8 + 5] = nz / len;
			}
		}
		Set<Long> seen = new HashSet<>();
		int[] kept = new int[tris.length];
		int m = 0;
		for (int t = 0; t < tris.length; t += 3) {
			int a = remap[tris[t]], b = remap[tris[t + 1]], c = remap[tris[t + 2]];
			if (a == b || b == c || a == c) {
				continue;
			}
			// the same triangle twice (same winding) after merging: once is enough
			int lo = Math.min(a, Math.min(b, c));
			int first = lo == a ? 0 : lo == b ? 1 : 2;
			int[] tri = {a, b, c};
			long id = ((long) tri[first] * 2_000_003L + tri[(first + 1) % 3]) * 2_000_003L + tri[(first + 2) % 3];
			if (!seen.add(id)) {
				continue;
			}
			kept[m++] = a;
			kept[m++] = b;
			kept[m++] = c;
		}
		return new EmoteProps.Mesh(out, java.util.Arrays.copyOf(kept, m));
	}

	/**
	 * Four vertex indices per quad: two neighbouring triangles that share an edge become one quad (drawn as exactly the
	 * same two triangles), a lone triangle a quad with its last corner twice. Cached per mesh.
	 */
	public static int[] quads(EmoteProps.Mesh mesh) {
		int[] cached = QUADS.get(mesh);
		if (cached != null) {
			return cached;
		}
		int[] result = quadIndices(mesh.triangles());
		QUADS.put(mesh, result);
		return result;
	}

	private static final Map<int[], int[]> QUAD_INDICES = Collections.synchronizedMap(new WeakHashMap<>());

	/** {@link #quads} for a bare triangle index list (cached per array, e.g. a wing half's). */
	public static int[] quadIndices(int[] t) {
		int[] cached = QUAD_INDICES.get(t);
		if (cached != null) {
			return cached;
		}
		int[] built = buildQuads(t);
		QUAD_INDICES.put(t, built);
		return built;
	}

	/**
	 * Pairs every triangle with a neighbour across a shared edge (walked in opposite directions = same facing) where it
	 * can - greedy over the whole mesh, not just the next triangle (Meshy's triangle order is scrambled: pairing only
	 * neighbours in the list matched ~5%, this ~98%). Leftovers become quads with their last corner twice.
	 */
	private static int[] buildQuads(int[] t) {
		int count = t.length / 3;
		Map<Long, Integer> edges = new HashMap<>(count * 4);
		for (int i = 0; i < count; i++) {
			for (int e = 0; e < 3; e++) {
				edges.put(edgeKey(t[i * 3 + e], t[i * 3 + (e + 1) % 3]), i);
			}
		}
		boolean[] used = new boolean[count];
		int[] q = new int[count * 4];
		int m = 0;
		for (int i = 0; i < count; i++) {
			if (used[i]) {
				continue;
			}
			used[i] = true;
			int found = -1;
			for (int e = 0; e < 3 && found < 0; e++) {
				int p = t[i * 3 + e], qv = t[i * 3 + (e + 1) % 3], w = t[i * 3 + (e + 2) % 3];
				Integer j = edges.get(edgeKey(qv, p));
				if (j == null || used[j]) {
					continue;
				}
				int other = -1;
				for (int k = 0; k < 3; k++) {
					int v = t[j * 3 + k];
					if (v != p && v != qv) {
						other = v;
					}
				}
				if (other < 0 || other == w) {
					continue;
				}
				used[j] = true;
				found = j;
				// drawn as (qv, w, p) and (p, other, qv): exactly the two triangles
				q[m++] = qv;
				q[m++] = w;
				q[m++] = p;
				q[m++] = other;
			}
			if (found < 0) {
				q[m++] = t[i * 3];
				q[m++] = t[i * 3 + 1];
				q[m++] = t[i * 3 + 2];
				q[m++] = t[i * 3 + 2];
			}
		}
		return java.util.Arrays.copyOf(q, m);
	}

	private static long edgeKey(int from, int to) {
		return (long) from << 32 | (to & 0xFFFFFFFFL);
	}

	/**
	 * Triangles (a,b,c) and (d,e,f) as one quad (p0,p1,p2,p3) - drawn as (p0,p1,p2) and (p2,p3,p0) - when they share an
	 * edge walked in opposite directions (same facing); else null.
	 */
	private static int[] pair(int a, int b, int c, int d, int e, int f) {
		int[] t1 = {a, b, c};
		int[] t2 = {d, e, f};
		for (int r = 0; r < 3; r++) {
			// t1 as (w, p, q): its edge p->q; t2 must contain q->p followed by its own third vertex
			int w = t1[r], p = t1[(r + 1) % 3], q = t1[(r + 2) % 3];
			for (int s = 0; s < 3; s++) {
				if (t2[s] == q && t2[(s + 1) % 3] == p) {
					int other = t2[(s + 2) % 3];
					if (other == w || other == p || other == q) {
						return null;
					}
					return new int[] {q, w, p, other};
				}
			}
		}
		return null;
	}
}
