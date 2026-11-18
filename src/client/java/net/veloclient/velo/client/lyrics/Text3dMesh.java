package net.veloclient.velo.client.lyrics;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real 3D letters: a glyph's outline from the TTF (STB, the same library MinecraftClient draws its own TTF fonts with) turned
 * into a solid with a front face, a bevel, smooth sides and a back face. The mesh is in line units (the layout's font units:
 * x along the line from the letter's own origin, y down from the cell top, z from -depth/2 (front, towards the viewer)
 * to +depth/2), cached per letter.
 */
final class Text3dMesh {

	/** Triangles as separate vertices: x, y, z, nx, ny, nz, kind (0 = front face, 1 = back face, 2 = bevel and sides). */
	record Mesh(float[] data, int vertices, float advance) {
		static final int STRIDE = 7;
	}

	private static final Map<String, String> FILES = new HashMap<>();
	private static final Map<String, Face> FACES = new HashMap<>();
	private static final Map<String, Mesh> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Mesh> eldest) {
			return size() > 3000;
		}
	};
	private static final Mesh EMPTY = new Mesh(new float[0], 0, 0f);
	/** A line is {@link LyricsRenderer#EM} font units tall, like the 2D text it replaces. */
	private static final float LINE = LyricsRenderer.EM;

	static {
		FILES.put("Archivo", "archivo_semiexp_extrabold.ttf");
		FILES.put("Inter", "inter_semibold.ttf");
		FILES.put("Sora", "sora_bold.ttf");
		FILES.put("Space Grotesk", "space_grotesk_bold.ttf");
		FILES.put("Poppins", "poppins_regular.ttf");
		FILES.put("Audiowide", "audiowide.ttf");
		FILES.put("JetBrains Mono", "jetbrains_mono_medium.ttf");
		FILES.put("MinecraftClient", "archivo_semiexp_extrabold.ttf");
	}

	private record Face(STBTTFontinfo info, ByteBuffer data, float scale, float ascent) {
	}

	private Text3dMesh() {
	}

	private static synchronized Face face(String font) {
		String file = FILES.getOrDefault(font, "archivo_semiexp_extrabold.ttf");
		Face f = FACES.get(file);
		if (f != null) {
			return f;
		}
		try (InputStream in = Text3dMesh.class.getResourceAsStream("/assets/velo-client/font/" + file)) {
			if (in == null) {
				return null;
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
			buf.put(bytes).flip();
			STBTTFontinfo info = STBTTFontinfo.create();
			if (!STBTruetype.stbtt_InitFont(info, buf)) {
				MemoryUtil.memFree(buf);
				return null;
			}
			float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, LINE);
			int ascent;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var a = stack.mallocInt(1);
				var d = stack.mallocInt(1);
				var g = stack.mallocInt(1);
				STBTruetype.stbtt_GetFontVMetrics(info, a, d, g);
				ascent = a.get(0);
			}
			f = new Face(info, buf, scale, ascent * scale);
			FACES.put(file, f);
			return f;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The solid for one letter, or an empty mesh (spaces, missing glyphs). {@code depth} and {@code bevel} in font units. */
	static synchronized Mesh glyph(String font, int codePoint, float depth, float bevel) {
		String key = font + "|" + codePoint + "|" + Math.round(depth * 20) + "|" + Math.round(bevel * 100);
		Mesh cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		Mesh m = build(font, codePoint, depth, bevel);
		CACHE.put(key, m);
		return m;
	}

	/** A letter's advance in font units (also for spaces). */
	static synchronized float advance(String font, int codePoint) {
		Face f = face(font);
		if (f == null) {
			return LINE * 0.5f;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var adv = stack.mallocInt(1);
			var lsb = stack.mallocInt(1);
			STBTruetype.stbtt_GetCodepointHMetrics(f.info, codePoint, adv, lsb);
			return adv.get(0) * f.scale;
		}
	}

	private static Mesh build(String font, int cp, float depth, float bevel) {
		Face f = face(font);
		if (f == null) {
			return EMPTY;
		}
		float advance = advance(font, cp);
		List<double[]> contours = outline(f, cp);
		if (contours.isEmpty()) {
			return new Mesh(new float[0], 0, advance);
		}
		// orientation of the fill: the biggest contour is an outer ring, every ring turning the same way is solid, the others are holes
		double biggest = 0;
		for (double[] c : contours) {
			double a = area(c);
			if (Math.abs(a) > Math.abs(biggest)) {
				biggest = a;
			}
		}
		double fillSign = Math.signum(biggest);
		List<double[]> outers = new ArrayList<>();
		List<List<double[]>> holes = new ArrayList<>();
		List<double[]> holeList = new ArrayList<>();
		for (double[] c : contours) {
			if (Math.signum(area(c)) == fillSign) {
				outers.add(c);
				holes.add(new ArrayList<>());
			} else {
				holeList.add(c);
			}
		}
		for (double[] h : holeList) {
			for (int k = 0; k < outers.size(); k++) {
				if (contains(outers.get(k), h[0], h[1])) {
					holes.get(k).add(h);
					break;
				}
			}
		}
		float hd = depth / 2f;
		// the bevel may not eat more than a third of the depth or of a thin stroke
		float b = Math.max(0f, Math.min(bevel, depth * 0.33f));
		float zFront = -hd;
		float zBack = hd;
		float zFrontIn = -hd + b;
		float zBackIn = hd - b;
		FloatList out = new FloatList();
		for (int k = 0; k < outers.size(); k++) {
			List<double[]> rings = new ArrayList<>();
			rings.add(outers.get(k));
			rings.addAll(holes.get(k));
			List<double[]> insets = new ArrayList<>();
			for (double[] ring : rings) {
				insets.add(b > 0 ? inset(ring, b, fillSign) : ring);
			}
			// front and back faces from the inset outline
			int[] holeStarts = new int[rings.size() - 1];
			double[] flat = Earcut.concat(insets, holeStarts);
			int[] tri = Earcut.triangulate(flat, holeStarts);
			for (int t = 0; t + 2 < tri.length; t += 3) {
				face(out, flat, tri[t], tri[t + 1], tri[t + 2], zFront, -1f, 0);
				face(out, flat, tri[t], tri[t + 1], tri[t + 2], zBack, 1f, 1);
			}
			// bevel rings and side walls
			for (int r = 0; r < rings.size(); r++) {
				double[] ring = rings.get(r);
				double[] in = insets.get(r);
				int n = ring.length / 2;
				double[][] nrm = edgeNormals(ring, fillSign);   // outward, per edge
				for (int i = 0; i < n; i++) {
					int j = (i + 1) % n;
					int prev = (i + n - 1) % n;
					int next = (j + 1) % n;
					double[] ni = smooth(nrm[i], nrm[prev]);
					double[] nj = smooth(nrm[i], nrm[next]);
					float x0 = (float) ring[i * 2], y0 = (float) ring[i * 2 + 1], x1 = (float) ring[j * 2], y1 = (float) ring[j * 2 + 1];
					if (b > 0) {
						float ix0 = (float) in[i * 2], iy0 = (float) in[i * 2 + 1], ix1 = (float) in[j * 2], iy1 = (float) in[j * 2 + 1];
						// chamfer: outward normal tilted towards the face
						quad(out, ix0, iy0, zFront, ix1, iy1, zFront, x1, y1, zFrontIn, x0, y0, zFrontIn,
								tilt(ni, b, depth, -1f), tilt(nj, b, depth, -1f), tilt(nj, b, depth, -1f), tilt(ni, b, depth, -1f), 2);
						quad(out, x0, y0, zBackIn, x1, y1, zBackIn, ix1, iy1, zBack, ix0, iy0, zBack,
								tilt(ni, b, depth, 1f), tilt(nj, b, depth, 1f), tilt(nj, b, depth, 1f), tilt(ni, b, depth, 1f), 2);
					}
					quad(out, x0, y0, zFrontIn, x1, y1, zFrontIn, x1, y1, zBackIn, x0, y0, zBackIn,
							new float[] {(float) ni[0], (float) ni[1], 0f}, new float[] {(float) nj[0], (float) nj[1], 0f},
							new float[] {(float) nj[0], (float) nj[1], 0f}, new float[] {(float) ni[0], (float) ni[1], 0f}, 2);
				}
			}
		}
		return new Mesh(out.toArray(), out.size() / Mesh.STRIDE, advance);
	}

	// ------------------------------------------------------------ outline

	/** Closed rings (x, y pairs in line units, y down, no repeated end point). */
	private static List<double[]> outline(Face f, int cp) {
		List<double[]> rings = new ArrayList<>();
		STBTTVertex.Buffer v = STBTruetype.stbtt_GetCodepointShape(f.info, cp);
		if (v == null) {
			return rings;
		}
		try {
			List<double[]> pts = new ArrayList<>();
			double lastX = 0;
			double lastY = 0;
			for (int i = 0; i < v.limit(); i++) {
				STBTTVertex e = v.get(i);
				double x = e.x() * f.scale;
				double y = f.ascent - e.y() * f.scale;
				switch (e.type()) {
					case STBTruetype.STBTT_vmove -> {
						close(pts, rings);
						pts.add(new double[] {x, y});
					}
					case STBTruetype.STBTT_vline -> pts.add(new double[] {x, y});
					case STBTruetype.STBTT_vcurve -> {
						double cx = e.cx() * f.scale;
						double cy = f.ascent - e.cy() * f.scale;
						int steps = 7;
						for (int s = 1; s <= steps; s++) {
							double t = s / (double) steps;
							double u = 1 - t;
							pts.add(new double[] {u * u * lastX + 2 * u * t * cx + t * t * x, u * u * lastY + 2 * u * t * cy + t * t * y});
						}
					}
					case STBTruetype.STBTT_vcubic -> {
						double c1x = e.cx() * f.scale;
						double c1y = f.ascent - e.cy() * f.scale;
						double c2x = e.cx1() * f.scale;
						double c2y = f.ascent - e.cy1() * f.scale;
						int steps = 8;
						for (int s = 1; s <= steps; s++) {
							double t = s / (double) steps;
							double u = 1 - t;
							pts.add(new double[] {u * u * u * lastX + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x,
									u * u * u * lastY + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y});
						}
					}
					default -> {
					}
				}
				lastX = x;
				lastY = y;
			}
			close(pts, rings);
		} finally {
			STBTruetype.stbtt_FreeShape(f.info, v);
		}
		return rings;
	}

	private static void close(List<double[]> pts, List<double[]> rings) {
		if (pts.size() >= 3) {
			// drop a repeated end point and points closer than a hair
			List<double[]> clean = new ArrayList<>();
			for (double[] p : pts) {
				if (clean.isEmpty() || Math.hypot(p[0] - clean.get(clean.size() - 1)[0], p[1] - clean.get(clean.size() - 1)[1]) > 1e-4) {
					clean.add(p);
				}
			}
			if (clean.size() > 1 && Math.hypot(clean.get(0)[0] - clean.get(clean.size() - 1)[0], clean.get(0)[1] - clean.get(clean.size() - 1)[1]) < 1e-4) {
				clean.remove(clean.size() - 1);
			}
			if (clean.size() >= 3) {
				double[] ring = new double[clean.size() * 2];
				for (int i = 0; i < clean.size(); i++) {
					ring[i * 2] = clean.get(i)[0];
					ring[i * 2 + 1] = clean.get(i)[1];
				}
				rings.add(ring);
			}
		}
		pts.clear();
	}

	private static double area(double[] ring) {
		double a = 0;
		int n = ring.length / 2;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			a += ring[i * 2] * ring[j * 2 + 1] - ring[j * 2] * ring[i * 2 + 1];
		}
		return a / 2;
	}

	private static boolean contains(double[] ring, double px, double py) {
		boolean in = false;
		int n = ring.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = ring[i * 2], yi = ring[i * 2 + 1], xj = ring[j * 2], yj = ring[j * 2 + 1];
			if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
				in = !in;
			}
		}
		return in;
	}

	/** Per edge i (from point i to i + 1): the unit normal pointing OUT of the solid. */
	private static double[][] edgeNormals(double[] ring, double fillSign) {
		int n = ring.length / 2;
		double[][] out = new double[n][];
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double dx = ring[j * 2] - ring[i * 2];
			double dy = ring[j * 2 + 1] - ring[i * 2 + 1];
			double len = Math.hypot(dx, dy);
			if (len < 1e-9) {
				len = 1;
			}
			// the fill is on the left when the signed area is positive: the outward normal is the right-hand one
			out[i] = new double[] {fillSign * dy / len, -fillSign * dx / len};
		}
		return out;
	}

	/** Averaged normal where two edges meet softly, the edge's own at a corner. */
	private static double[] smooth(double[] own, double[] other) {
		double[] a = own;
		double[] b = other;
		double dot = a[0] * b[0] + a[1] * b[1];
		if (dot < 0.64) {   // more than about 50 degrees: a real corner keeps its own edge's normal
			return own;
		}
		double x = a[0] + b[0];
		double y = a[1] + b[1];
		double len = Math.hypot(x, y);
		return len < 1e-9 ? a : new double[] {x / len, y / len};
	}

	private static double[] inset(double[] ring, float b, double fillSign) {
		int n = ring.length / 2;
		double[][] nrm = edgeNormals(ring, fillSign);
		double[] out = new double[ring.length];
		for (int i = 0; i < n; i++) {
			double[] e0 = nrm[(i + n - 1) % n];
			double[] e1 = nrm[i];
			double x = e0[0] + e1[0];
			double y = e0[1] + e1[1];
			double len = Math.hypot(x, y);
			double cos = len / 2;   // cos of half the turn
			double k = b / Math.max(cos, 0.5);
			if (len < 1e-9) {
				x = e1[0];
				y = e1[1];
				len = 1;
				k = b;
			}
			// inward = against the outward normal
			out[i * 2] = ring[i * 2] - x / len * k;
			out[i * 2 + 1] = ring[i * 2 + 1] - y / len * k;
		}
		return out;
	}

	/** A bevel's outward normal tilted towards the face (sign -1 front, +1 back). */
	private static float[] tilt(double[] n, float b, float depth, float sign) {
		double slope = Math.max(b, 1e-4);
		double nx = n[0] * slope;
		double ny = n[1] * slope;
		double nz = sign * slope;
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return new float[] {(float) (nx / len), (float) (ny / len), (float) (nz / len)};
	}

	// ------------------------------------------------------------ triangles

	private static void face(FloatList out, double[] flat, int a, int b, int c, float z, float nz, int kind) {
		double ax = flat[a * 2], ay = flat[a * 2 + 1], bx = flat[b * 2], by = flat[b * 2 + 1], cx = flat[c * 2], cy = flat[c * 2 + 1];
		// winding: the cross product in the letter's frame (x right, y down, z away) must point against the normal's side
		double cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
		boolean flip = (cross > 0) == (nz < 0);
		if (flip) {
			double tx = bx;
			double ty = by;
			bx = cx;
			by = cy;
			cx = tx;
			cy = ty;
		}
		out.vertex((float) ax, (float) ay, z, 0, 0, nz, kind);
		out.vertex((float) bx, (float) by, z, 0, 0, nz, kind);
		out.vertex((float) cx, (float) cy, z, 0, 0, nz, kind);
	}

	private static void quad(FloatList out, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
			float x3, float y3, float z3, float[] n0, float[] n1, float[] n2, float[] n3, int kind) {
		out.vertex(x0, y0, z0, n0[0], n0[1], n0[2], kind);
		out.vertex(x1, y1, z1, n1[0], n1[1], n1[2], kind);
		out.vertex(x2, y2, z2, n2[0], n2[1], n2[2], kind);
		out.vertex(x0, y0, z0, n0[0], n0[1], n0[2], kind);
		out.vertex(x2, y2, z2, n2[0], n2[1], n2[2], kind);
		out.vertex(x3, y3, z3, n3[0], n3[1], n3[2], kind);
	}

	private static final class FloatList {
		private float[] a = new float[2048];
		private int n;

		void vertex(float x, float y, float z, float nx, float ny, float nz, float kind) {
			if (n + Mesh.STRIDE > a.length) {
				a = java.util.Arrays.copyOf(a, a.length * 2);
			}
			a[n++] = x;
			a[n++] = y;
			a[n++] = z;
			a[n++] = nx;
			a[n++] = ny;
			a[n++] = nz;
			a[n++] = kind;
		}

		int size() {
			return n;
		}

		float[] toArray() {
			return java.util.Arrays.copyOf(a, n);
		}
	}
}
