package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.util.BulkVertexSink;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Fast vertex output for the 3D cosmetics (bodywear, wings): every mesh vertex is transformed once per frame (not once
 * per quad corner), then all quads go straight into the buffer's memory in one reservation (26.2+, BulkVertexSink) -
 * Minecraft's per-vertex calls cost ~40 ns each and were most of a third-person frame. Returns false when the buffer
 * can't take the bulk path; the caller then writes vertex by vertex as before.
 */
public final class CosmeticVertexWriter {

	/** One mesh transformed by one matrix: reused when another pass draws the same mesh with the same matrix (wings + glow). */
	private record Transformed(float[] data, Matrix4f m, float scale, float squeeze, float[] positions, byte[] normals, boolean[] done) {
	}

	private static final java.util.ArrayDeque<Transformed> RECENT = new java.util.ArrayDeque<>();

	/** Transforms the vertices {@code quads} uses (each once), or hands back what an earlier pass this frame already did. */
	private static Transformed transformed(int[] quads, int vertexCount, float[] data, int stride, int posOff, int nrmOff, float scale,
			float squeeze, Matrix4f m, Matrix3f n) {
		Transformed t = null;
		for (Transformed r : RECENT) {
			if (r.data() == data && r.scale() == scale && r.squeeze() == squeeze && r.m().equals(m) && r.positions().length >= vertexCount * 3) {
				t = r;
				break;
			}
		}
		if (t == null) {
			t = new Transformed(data, new Matrix4f(m), scale, squeeze, new float[vertexCount * 3], new byte[vertexCount * 3], new boolean[vertexCount]);
			RECENT.addFirst(t);
			if (RECENT.size() > 8) {
				RECENT.removeLast();
			}
		}
		float[] p = t.positions();
		byte[] nb = t.normals();
		boolean[] done = t.done();
		for (int k = 0; k < quads.length; k++) {
			int v = quads[k];
			if (done[v]) {
				continue;
			}
			done[v] = true;
			int i = v * stride;
			float x = data[i + posOff];
			if (squeeze != 0) {
				x = squeeze + (x - squeeze) * 0.75f;
			}
			x *= scale;
			float y = data[i + posOff + 1] * scale, z = data[i + posOff + 2] * scale;
			p[v * 3] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
			p[v * 3 + 1] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
			p[v * 3 + 2] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
			float a = data[i + nrmOff], b = data[i + nrmOff + 1], c = data[i + nrmOff + 2];
			float nx = n.m00() * a + n.m10() * b + n.m20() * c;
			float ny = n.m01() * a + n.m11() * b + n.m21() * c;
			float nz = n.m02() * a + n.m12() * b + n.m22() * c;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			float inv = len > 1e-6f ? 127f / len : 0f;
			nb[v * 3] = (byte) (int) (nx * inv);
			nb[v * 3 + 1] = (byte) (int) (ny * inv);
			nb[v * 3 + 2] = (byte) (int) (nz * inv);
		}
		return t;
	}

	private CosmeticVertexWriter() {
	}

	/**
	 * @param data      per-vertex floats: position at {@code posOff} (x,y,z), normal at {@code nrmOff}; {@code stride} floats each
	 * @param uv        texture coordinates (u,v) at {@code uvOff} with {@code uvStride} floats per vertex (may be {@code data})
	 * @param scale     position scale (1/16 for skin-pixel meshes)
	 * @param squeeze   bodywear slim-arm squeeze edge (0 = none)
	 * @param colors    per-vertex ARGB, or null to use {@code argb} everywhere
	 */
	public static boolean writeQuads(Object consumer, int[] quads, int vertexCount, float[] data, int stride, int posOff, int nrmOff,
			float[] uv, int uvStride, int uvOff, float scale, float squeeze, Matrix4f m, Matrix3f n, int argb, int[] colors,
			int overlay, int light) {
		if (!(consumer instanceof BulkVertexSink sink) || quads.length == 0) {
			return false;
		}
		long pointer = sink.velo$reserveEntityVertices(quads.length);
		if (pointer == -1L) {
			return false;
		}
		Transformed t = transformed(quads, vertexCount, data, stride, posOff, nrmOff, scale, squeeze, m, n);
		float[] p = t.positions();
		byte[] nb = t.normals();
		int abgr = abgr(argb);
		long overlayLight = (overlay & 0xFFFFFFFFL) | (long) light << 32;
		for (int k = 0; k < quads.length; k++) {
			int v = quads[k];
			int i3 = v * 3;
			int iu = v * uvStride + uvOff;
			// little-endian (checked by the bulk sink): two values per 64-bit write
			MemoryUtil.memPutLong(pointer, (Float.floatToRawIntBits(p[i3]) & 0xFFFFFFFFL) | (long) Float.floatToRawIntBits(p[i3 + 1]) << 32);
			MemoryUtil.memPutLong(pointer + 8L, (Float.floatToRawIntBits(p[i3 + 2]) & 0xFFFFFFFFL)
					| (long) (colors == null ? abgr : abgr(colors[v])) << 32);
			MemoryUtil.memPutLong(pointer + 16L, (Float.floatToRawIntBits(uv[iu]) & 0xFFFFFFFFL) | (long) Float.floatToRawIntBits(uv[iu + 1]) << 32);
			MemoryUtil.memPutLong(pointer + 24L, overlayLight);
			MemoryUtil.memPutInt(pointer + 32L, (nb[i3] & 0xFF) | (nb[i3 + 1] & 0xFF) << 8 | (nb[i3 + 2] & 0xFF) << 16);
			pointer += BulkVertexSink.ENTITY_VERTEX_SIZE;
		}
		return true;
	}

	private static int[] sequential = new int[0];

	/** Vertices already expanded per corner ({@code count} of them, in quad order): 0, 1, 2, ... */
	public static boolean writeSequential(Object consumer, int count, float[] data, int stride, int posOff, int nrmOff, int uvOff,
			Matrix4f m, Matrix3f n, int argb, int overlay, int light) {
		if (sequential.length < count) {
			int[] seq = new int[Math.max(count, sequential.length * 2)];
			for (int i = 0; i < seq.length; i++) {
				seq[i] = i;
			}
			sequential = seq;
		}
		int[] quads = sequential.length == count ? sequential : java.util.Arrays.copyOf(sequential, count);
		return writeQuads(consumer, quads, count, data, stride, posOff, nrmOff, data, stride, uvOff, 1f, 0f, m, n, argb, null, overlay, light);
	}

	private static int abgr(int argb) {
		return argb & 0xFF00FF00 | (argb >> 16) & 0xFF | (argb & 0xFF) << 16;
	}
}
