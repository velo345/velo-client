package net.veloclient.velo.dev.pets;

/**
 * One frame of a pet: every node's local position / rotation (quaternion) / scale / visibility, the root's transform in
 * its clip's frame, and the materials' glow and opacity. Sampled from clips with interpolation, and blended for
 * cross-fades between states.
 */
final class PetPose {

	final float[] p;
	final float[] q;
	final float[] s;
	final float[] v;
	final float[] rootP = new float[3];
	final float[] rootQ = {0, 0, 0, 1};
	final float[] rootS = {1, 1, 1};
	final float[] emissive;
	final float[] opacity;
	final float[] emissiveColor;

	PetPose(PetModel model) {
		int n = model.nodes.length;
		p = new float[n * 3];
		q = new float[n * 4];
		s = new float[n * 3];
		v = new float[n];
		emissive = new float[model.materials.length];
		opacity = new float[model.materials.length];
		emissiveColor = new float[model.materials.length * 3];
	}

	/** Clip frame {@code f} (fractional, already wrapped into range). */
	void sample(PetModel model, PetModel.Clip clip, float f) {
		int last = clip.frames - 1;
		f = Math.max(0, Math.min(last, f));
		int a = (int) Math.floor(f);
		int b = Math.min(last, a + 1);
		float w = f - a;
		read(clip.root.p, 3, a, b, w, rootP, 0);
		readQ(clip.root.q, a, b, w, rootQ, 0);
		read(clip.root.s, 3, a, b, w, rootS, 0);
		for (int i = 0; i < clip.nodes.length; i++) {
			PetModel.Channels c = clip.nodes[i];
			read(c.p, 3, a, b, w, p, i * 3);
			readQ(c.q, a, b, w, q, i * 4);
			read(c.s, 3, a, b, w, s, i * 3);
			v[i] = c.v.length == 1 ? c.v[0] : (w < .5f ? c.v[a] : c.v[b]);
		}
		for (int i = 0; i < emissive.length; i++) {
			float[] e = clip.emissive[i];
			float[] o = clip.opacity[i];
			emissive[i] = e.length == 1 ? e[0] : e[a] + (e[b] - e[a]) * w;
			opacity[i] = o.length == 1 ? o[0] : o[a] + (o[b] - o[a]) * w;
			read(clip.emissiveColor[i], 3, a, b, w, emissiveColor, i * 3);
		}
	}

	/** This pose moved {@code w} (0..1) of the way towards {@code to}. */
	void blendTowards(PetPose to, float w) {
		if (w <= 0) {
			return;
		}
		if (w >= 1) {
			copyFrom(to);
			return;
		}
		lerp(p, to.p, w);
		lerp(s, to.s, w);
		for (int i = 0; i < q.length; i += 4) {
			nlerp(q, i, to.q, i, w);
		}
		for (int i = 0; i < v.length; i++) {
			v[i] = w < .5f ? v[i] : to.v[i];
		}
		lerp(rootP, to.rootP, w);
		lerp(rootS, to.rootS, w);
		nlerp(rootQ, 0, to.rootQ, 0, w);
		lerp(emissive, to.emissive, w);
		lerp(opacity, to.opacity, w);
		lerp(emissiveColor, to.emissiveColor, w);
	}

	void copyFrom(PetPose o) {
		System.arraycopy(o.p, 0, p, 0, p.length);
		System.arraycopy(o.q, 0, q, 0, q.length);
		System.arraycopy(o.s, 0, s, 0, s.length);
		System.arraycopy(o.v, 0, v, 0, v.length);
		System.arraycopy(o.rootP, 0, rootP, 0, 3);
		System.arraycopy(o.rootQ, 0, rootQ, 0, 4);
		System.arraycopy(o.rootS, 0, rootS, 0, 3);
		System.arraycopy(o.emissive, 0, emissive, 0, emissive.length);
		System.arraycopy(o.opacity, 0, opacity, 0, opacity.length);
		System.arraycopy(o.emissiveColor, 0, emissiveColor, 0, emissiveColor.length);
	}

	private static void read(float[] src, int width, int a, int b, float w, float[] dst, int at) {
		if (src.length == width) {
			System.arraycopy(src, 0, dst, at, width);
			return;
		}
		for (int k = 0; k < width; k++) {
			float x = src[a * width + k];
			dst[at + k] = x + (src[b * width + k] - x) * w;
		}
	}

	private static void readQ(float[] src, int a, int b, float w, float[] dst, int at) {
		if (src.length == 4) {
			System.arraycopy(src, 0, dst, at, 4);
			return;
		}
		System.arraycopy(src, a * 4, dst, at, 4);
		nlerp(dst, at, src, b * 4, w);
	}

	private static void lerp(float[] a, float[] b, float w) {
		for (int i = 0; i < a.length; i++) {
			a[i] += (b[i] - a[i]) * w;
		}
	}

	/** Normalised quaternion lerp along the short way (fine for the small steps between frames and fades). */
	static void nlerp(float[] a, int ai, float[] b, int bi, float w) {
		float dot = a[ai] * b[bi] + a[ai + 1] * b[bi + 1] + a[ai + 2] * b[bi + 2] + a[ai + 3] * b[bi + 3];
		float sign = dot < 0 ? -1 : 1;
		float len = 0;
		for (int k = 0; k < 4; k++) {
			a[ai + k] += (b[bi + k] * sign - a[ai + k]) * w;
			len += a[ai + k] * a[ai + k];
		}
		len = (float) Math.sqrt(len);
		if (len > 1e-6f) {
			for (int k = 0; k < 4; k++) {
				a[ai + k] /= len;
			}
		}
	}
}
