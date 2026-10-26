package net.veloclient.velo.client.gui.packs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A tiny software renderer for the editor's 3D preview: textured quads, orthographic, depth tested, with the
 * game's face shading. It draws into an ARGB array that {@link GpuImage} shows - no render pipelines involved,
 * so it looks the same on every version (OpenGL or Vulkan) and the texture being edited shows instantly.
 */
public final class Soft3d {

	/** A texture the quads sample: ARGB pixels. */
	public record Tex(int[] px, int width, int height) {
	}

	/**
	 * One quad: four corners (x, y, z, u, v) - y up, any units - with UVs 0..1 into {@code texture}, a tint
	 * (multiplied, 0xFFFFFFFF = none) and whether the game would shade it by facing.
	 */
	public record Quad(float[] v, String texture, int tint, boolean shade) {
	}

	public final List<Quad> quads = new ArrayList<>();

	private float[] depth = new float[0];

	/** The model's centre and radius (for framing). */
	private float cx;
	private float cy;
	private float cz;
	private float radius = 1f;

	public void add(Quad quad) {
		quads.add(quad);
	}

	/** Measures the model so {@link #render} frames it. Call after adding quads. */
	public void fit() {
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float maxZ = -Float.MAX_VALUE;
		for (Quad q : quads) {
			for (int i = 0; i < 4; i++) {
				minX = Math.min(minX, q.v[i * 5]);
				maxX = Math.max(maxX, q.v[i * 5]);
				minY = Math.min(minY, q.v[i * 5 + 1]);
				maxY = Math.max(maxY, q.v[i * 5 + 1]);
				minZ = Math.min(minZ, q.v[i * 5 + 2]);
				maxZ = Math.max(maxZ, q.v[i * 5 + 2]);
			}
		}
		if (quads.isEmpty()) {
			return;
		}
		cx = (minX + maxX) / 2;
		cy = (minY + maxY) / 2;
		cz = (minZ + maxZ) / 2;
		float dx = maxX - minX;
		float dy = maxY - minY;
		float dz = maxZ - minZ;
		radius = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
	}

	/**
	 * Renders into {@code out} ({@code size * size}, cleared to transparent). {@code yaw}/{@code pitch} in degrees,
	 * {@code zoom} 1 = the model just fits.
	 */
	public void render(int[] out, int size, float yaw, float pitch, float zoom, Map<String, Tex> textures) {
		Arrays.fill(out, 0);
		if (depth.length != out.length) {
			depth = new float[out.length];
		}
		Arrays.fill(depth, Float.NEGATIVE_INFINITY);
		double ry = Math.toRadians(yaw);
		double rp = Math.toRadians(pitch);
		float cyaw = (float) Math.cos(ry);
		float syaw = (float) Math.sin(ry);
		float cp = (float) Math.cos(rp);
		float sp = (float) Math.sin(rp);
		float scale = size / 2f / radius * 0.92f * zoom;
		float half = size / 2f;
		float[] sx = new float[4];
		float[] sy = new float[4];
		float[] sz = new float[4];
		// Opaque first, then anything with partial alpha on top (good enough without sorting).
		for (int pass = 0; pass < 2; pass++) {
			for (Quad q : quads) {
				Tex tex = textures.get(q.texture);
				if (tex == null || tex.px.length == 0) {
					continue;
				}
				for (int i = 0; i < 4; i++) {
					float x = q.v[i * 5] - cx;
					float y = q.v[i * 5 + 1] - cy;
					float z = q.v[i * 5 + 2] - cz;
					float x1 = x * cyaw + z * syaw;
					float z1 = -x * syaw + z * cyaw;
					float y2 = y * cp - z1 * sp;
					float z2 = y * sp + z1 * cp;
					sx[i] = half + x1 * scale;
					sy[i] = half - y2 * scale;
					sz[i] = z2;
				}
				float shade = q.shade ? shade(q) : 1f;
				triangle(out, size, sx, sy, sz, q, tex, shade, 0, 1, 2, pass);
				triangle(out, size, sx, sy, sz, q, tex, shade, 0, 2, 3, pass);
			}
		}
	}

	/** The game's block shading by facing: up 1, down 0.5, north/south 0.8, east/west 0.6. */
	private static float shade(Quad q) {
		float[] v = q.v;
		float ax = v[5] - v[0];
		float ay = v[6] - v[1];
		float az = v[7] - v[2];
		float bx = v[15] - v[0];
		float by = v[16] - v[1];
		float bz = v[17] - v[2];
		float nx = ay * bz - az * by;
		float ny = az * bx - ax * bz;
		float nz = ax * by - ay * bx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) {
			return 1f;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		return nx * nx * 0.6f + nz * nz * 0.8f + ny * ny * (ny > 0 ? 1f : 0.5f);
	}

	private void triangle(int[] out, int size, float[] sx, float[] sy, float[] sz, Quad q, Tex tex, float shade, int a, int b, int c, int pass) {
		float x0 = sx[a];
		float y0 = sy[a];
		float x1 = sx[b];
		float y1 = sy[b];
		float x2 = sx[c];
		float y2 = sy[c];
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-6f) {
			return;
		}
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		float[] v = q.v;
		float u0 = v[a * 5 + 3];
		float v0 = v[a * 5 + 4];
		float u1 = v[b * 5 + 3];
		float v1 = v[b * 5 + 4];
		float u2 = v[c * 5 + 3];
		float v2 = v[c * 5 + 4];
		int tint = q.tint;
		float tr = ((tint >> 16) & 0xFF) / 255f * shade;
		float tg = ((tint >> 8) & 0xFF) / 255f * shade;
		float tb = (tint & 0xFF) / 255f * shade;
		for (int py = minY; py <= maxY; py++) {
			float fy = py + 0.5f;
			for (int px = minX; px <= maxX; px++) {
				float fx = px + 0.5f;
				float w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) / area;
				float w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) {
					continue;
				}
				float z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c];
				int i = py * size + px;
				if (z < depth[i] - 1e-4f) {
					continue;
				}
				float u = w0 * u0 + w1 * u1 + w2 * u2;
				float t = w0 * v0 + w1 * v1 + w2 * v2;
				int tx = Math.min(tex.width - 1, Math.max(0, (int) Math.floor(u * tex.width)));
				int ty = Math.min(tex.height - 1, Math.max(0, (int) Math.floor(t * tex.height)));
				int texel = tex.px[ty * tex.width + tx];
				int alpha = texel >>> 24;
				if (alpha < 26) {
					continue;
				}
				boolean partial = alpha < 250;
				if ((pass == 0) == partial) {
					continue;
				}
				int r = Math.min(255, Math.round(((texel >> 16) & 0xFF) * tr));
				int g = Math.min(255, Math.round(((texel >> 8) & 0xFF) * tg));
				int bl = Math.min(255, Math.round((texel & 0xFF) * tb));
				int color = alpha << 24 | r << 16 | g << 8 | bl;
				if (partial) {
					out[i] = PixelOps.over(out[i], color, 1f);
				} else {
					out[i] = color;
					depth[i] = z;
				}
			}
		}
	}

	/** A soft drop shadow under everything drawn (helps the model read on the dark panel). */
	public static void outline(int[] out, int size, int color) {
		int[] src = out.clone();
		for (int y = 1; y < size - 1; y++) {
			for (int x = 1; x < size - 1; x++) {
				int i = y * size + x;
				if ((src[i] >>> 24) != 0) {
					continue;
				}
				if ((src[i - 1] >>> 24) != 0 || (src[i + 1] >>> 24) != 0 || (src[i - size] >>> 24) != 0 || (src[i + size] >>> 24) != 0) {
					out[i] = color;
				}
			}
		}
	}
}
