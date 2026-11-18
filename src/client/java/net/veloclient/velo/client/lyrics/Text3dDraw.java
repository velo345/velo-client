package net.veloclient.velo.client.lyrics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Writes a letter solid ({@link Text3dMesh}) as quads: a triangle is a quad with its last corner repeated. */
final class Text3dDraw {

	/** The vertex call of the running MinecraftClient version. */
	@FunctionalInterface
	interface Sink {
		void vertex(float x, float y, float z, int color, float u, float v, float nx, float ny, float nz);
	}

	static final String WHITE_TEXTURE = "textures/misc/white.png";

	private Text3dDraw() {
	}

	/** Darkens a colour (alpha kept): the sides of a letter are a little darker than its face unless the style sets one. */
	static int shade(int argb, float f) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((argb & 0xFF) * f));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	/**
	 * @param offsetX where the letter's own origin sits in its cell (centres the glyph in the width the layout gave it)
	 * @param pos     the draw's position matrix, {@code nrm} its normal matrix: vertices are written already transformed
	 */
	static void emit(Text3dMesh.Mesh mesh, float offsetX, int face, int side, Matrix4f letterPose, Matrix4f pos, Matrix3f nrm, Sink sink) {
		// the stack only carries the letter's pose in its position matrix: its normals are turned here
		Matrix3f local = new Matrix3f(letterPose).invert().transpose();
		float[] d = mesh.data();
		int n = mesh.vertices();
		Vector3f p = new Vector3f();
		Vector3f nn = new Vector3f();
		for (int v = 0; v < n; v++) {
			int o = v * Text3dMesh.Mesh.STRIDE;
			pos.transformPosition(d[o] + offsetX, d[o + 1], d[o + 2], p);
			local.transform(d[o + 3], d[o + 4], d[o + 5], nn);
			nrm.transform(nn);
			nn.normalize();
			int color = d[o + 6] > 1.5f ? side : face;
			sink.vertex(p.x, p.y, p.z, color, 0.5f, 0.5f, nn.x, nn.y, nn.z);
			// every third vertex closes a triangle: repeat it as the quad's fourth corner
			if (v % 3 == 2) {
				sink.vertex(p.x, p.y, p.z, color, 0.5f, 0.5f, nn.x, nn.y, nn.z);
			}
		}
	}
}
