package net.veloclient.library;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small software 3D renderer for Minecraft players (no GPU on the server): the skin's boxes with their overlay
 * layers, the cape, any pose (joint angles), any camera angle. Quads are rasterised with a depth buffer at a higher
 * resolution and scaled down, so edges are smooth while the skin pixels stay crisp.
 */
final class SkinRenderer {

	/** Joint rotations in degrees (x = forward/back swing, y = twist, z = sideways). */
	static final class Pose {
		double[] head = new double[3], body = new double[3], rArm = new double[3], lArm = new double[3], rLeg = new double[3], lLeg = new double[3];
		double cape = 10;
		double lower;   // crouch / sit: drop the body this many pixels
		boolean sit;

		Pose copy() {
			Pose p = new Pose();
			p.head = head.clone();
			p.body = body.clone();
			p.rArm = rArm.clone();
			p.lArm = lArm.clone();
			p.rLeg = rLeg.clone();
			p.lLeg = lLeg.clone();
			p.cape = cape;
			p.lower = lower;
			p.sit = sit;
			return p;
		}
	}

	static final List<String> POSES = List.of("stand", "walk", "run", "wave", "sit", "crouch", "fight", "dab", "point", "think", "tpose", "float");

	static Pose pose(String name, double t) {
		Pose p = new Pose();
		double s = Math.sin(t * Math.PI * 2);
		switch (name) {
			case "walk" -> {
				p.rArm[0] = 30 * s;
				p.lArm[0] = -30 * s;
				p.rLeg[0] = -32 * s;
				p.lLeg[0] = 32 * s;
				p.cape = 14 + 4 * Math.abs(s);
			}
			case "run" -> {
				p.rArm[0] = 60 * s;
				p.lArm[0] = -60 * s;
				p.rLeg[0] = -55 * s;
				p.lLeg[0] = 55 * s;
				p.body[0] = -8;   // negative: the hips swing back, the upper body leans forward
				p.head[0] = 4;
				p.cape = 40;
			}
			case "wave" -> {
				p.rArm[0] = 180;
				p.rArm[2] = -18 + 22 * s;
				p.lArm[2] = 4;
				p.head[1] = -8;
				p.head[2] = 4;
			}
			case "sit" -> {
				p.sit = true;
				p.rLeg[0] = 90;
				p.lLeg[0] = 90;
				p.rLeg[1] = 8;
				p.lLeg[1] = -8;
				p.rArm[0] = 20;
				p.lArm[0] = 20;
				p.lower = 12;
				p.cape = 2;
			}
			case "crouch" -> {
				// like Minecraft: the body tips forward around the shoulders (hips back, legs with them), the head stays level
				p.body[0] = -28;
				p.head[0] = 22;
				p.rArm[0] = 20;
				p.lArm[0] = 20;
				p.lower = 3;
				p.cape = 6;   // the body's lean already tips the cape out
			}
			case "fight" -> {
				p.rArm[0] = 80;
				p.rArm[1] = -10;
				p.lArm[0] = 40;
				p.lArm[2] = 10;
				p.body[1] = 18;
				p.head[1] = -14;
				p.rLeg[0] = -14;
				p.lLeg[0] = 18;
			}
			case "dab" -> {
				p.rArm[0] = 135;
				p.rArm[2] = -40;
				p.lArm[0] = 90;
				p.lArm[1] = -60;
				p.head[0] = -32;   // chin down into the elbow
				p.head[1] = 30;
			}
			case "point" -> {
				p.rArm[0] = 90;
				p.rArm[1] = 8;
				p.head[1] = 6;
			}
			case "think" -> {
				p.rArm[0] = 120;
				p.rArm[1] = 40;
				p.rArm[2] = -10;
				p.lArm[0] = 45;
				p.lArm[1] = -50;
				p.head[0] = 12;
				p.head[2] = -8;
			}
			case "tpose" -> {
				p.rArm[2] = -90;
				p.lArm[2] = 90;
			}
			case "float" -> {
				p.rArm[2] = -20 - 6 * s;
				p.lArm[2] = 20 + 6 * s;
				p.rLeg[0] = 10;
				p.lLeg[0] = -6;
				p.rLeg[2] = -4;
				p.lLeg[2] = 4;
				p.head[0] = -10;
				p.cape = 25 + 6 * s;
			}
			default -> {
				p.rArm[2] = -3;
				p.lArm[2] = 3;
			}
		}
		return p;
	}

	/** Overrides from the URL: head=x,y,z rarm=... larm=... rleg=... lleg=... body=... (degrees). */
	static void customise(Pose p, Map<String, String> q) {
		String[][] names = {{"head"}, {"body"}, {"rarm"}, {"larm"}, {"rleg"}, {"lleg"}};
		double[][] targets = {p.head, p.body, p.rArm, p.lArm, p.rLeg, p.lLeg};
		for (int i = 0; i < names.length; i++) {
			String v = q.get(names[i][0]);
			if (v == null) {
				continue;
			}
			String[] xyz = v.split(",");
			for (int k = 0; k < Math.min(3, xyz.length); k++) {
				try {
					targets[i][k] = Math.max(-360, Math.min(360, Double.parseDouble(xyz[k].trim())));
				} catch (NumberFormatException ignored) {
					// keep the pose's value
				}
			}
		}
	}

	record Options(int size, double yaw, double pitch, boolean overlay, boolean cape, int background, boolean headOnly, boolean shadow, boolean layers3d) {
	}

	/** Legacy 64x32 skins get the 64x64 layout (left limbs mirrored from the right), and any HD skin is kept as is. */
	static BufferedImage normalise(BufferedImage skin) {
		int w = skin.getWidth();
		BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(skin, 0, 0, null);
		if (skin.getHeight() * 2 == w) {
			int s = w / 64;
			// Minecraft's "Notch transparency hack": an old skin's hat area that has no transparent pixel at all
			// is ignored (old skins often filled it with solid black)
			boolean anyClear = false;
			for (int y = 0; y < 16 * s && !anyClear; y++) {
				for (int x = 32 * s; x < 64 * s; x++) {
					if ((skin.getRGB(x, y) >>> 24) < 128) {
						anyClear = true;
						break;
					}
				}
			}
			if (!anyClear) {
				g.setComposite(java.awt.AlphaComposite.Clear);
				g.fillRect(32 * s, 0, 32 * s, 16 * s);
				g.setComposite(java.awt.AlphaComposite.SrcOver);
			}
			// left leg from right leg, left arm from right arm, each face mirrored
			mirrorLimb(g, skin, 0, 16, 16, 48, s);
			mirrorLimb(g, skin, 40, 16, 32, 48, s);
		}
		g.dispose();
		return out;
	}

	private static void mirrorLimb(Graphics2D g, BufferedImage src, int su, int sv, int du, int dv, int s) {
		int[][] faces = {{4, 0, 4, 4}, {8, 0, 4, 4}, {0, 4, 4, 12}, {4, 4, 4, 12}, {8, 4, 4, 12}, {12, 4, 4, 12}};
		int[] targetX = {4, 8, 8, 4, 0, 12};
		for (int i = 0; i < faces.length; i++) {
			int[] f = faces[i];
			int x0 = (su + f[0]) * s, y0 = (sv + f[1]) * s, fw = f[2] * s, fh = f[3] * s;
			int dx = (du + targetX[i]) * s, dy = (dv + f[1]) * s;
			g.drawImage(src, dx + fw, dy, dx, dy + fh, x0, y0, x0 + fw, y0 + fh, null);
		}
	}

	// ---- geometry ----

	private record Box(double x0, double y0, double z0, double x1, double y1, double z1, int u, int v, int w, int h, int d, boolean overlay) {
	}

	private record Quad(double[][] p, int u0, int v0, int u1, int v1, BufferedImage tex, boolean overlay, double shade, boolean cull) {
	}

	private static Box box(double x, double y, double z, int w, int h, int d, int u, int v, double inflate, boolean overlay) {
		return new Box(x - inflate, y - inflate, z - inflate, x + w + inflate, y + h + inflate, z + d + inflate, u, v, w, h, d, overlay);
	}

	/** Renders the player; returns an ARGB image of opts.size x opts.size (head-only: square around the head). */
	static BufferedImage render(Players.Textures tex, Pose pose, Options o) {
		return render(tex, pose, o, null);
	}

	/** A shared frame for animations: {minX, maxX, minY, maxY, mode}. Mode 0 = only measure (grow it, no image), 1 = use it. */
	static double[] newFrame() {
		return new double[] {1e9, -1e9, 1e9, -1e9, 0};
	}

	/**
	 * With {@code frame}: every frame of a GIF framed the same (measured over all frames first), so the player doesn't
	 * grow and shrink as the arms and legs move.
	 */
	static BufferedImage render(Players.Textures tex, Pose pose, Options o, double[] frame) {
		int ss = o.size() <= 256 ? 4 : o.size() <= 640 ? 2 : 1;
		int big = o.size() * ss;
		List<Quad> quads = new ArrayList<>();
		BufferedImage skin = tex.skin();
		int armW = tex.slim() ? 3 : 4;
		double body = pose.lower;

		// Each part: boxes in its local space around a pivot, rotated by the joint, then moved to the pivot.
		double[] neck = {0, 24 - body, 0};
		List<Box> head = List.of(box(-4, 0, -4, 8, 8, 8, 0, 0, 0, false), box(-4, 0, -4, 8, 8, 8, 32, 0, o.overlay() ? (o.layers3d() ? 0 : 0.5) : -99, true));
		addPart(quads, head, neck, pose.head, pose.body, skin, o);
		if (!o.headOnly()) {
			List<Box> torso = List.of(box(-4, -12, -2, 8, 12, 4, 16, 16, 0, false), box(-4, -12, -2, 8, 12, 4, 16, 32, o.overlay() ? (o.layers3d() ? 0 : 0.25) : -99, true));
			addPart(quads, torso, neck, new double[3], pose.body, skin, o);
			double[] rShoulder = {-4 - armW / 2.0, 22 - body, 0};
			double[] lShoulder = {4 + armW / 2.0, 22 - body, 0};
			addPart(quads, List.of(box(-armW / 2.0, -10, -2, armW, 12, 4, 40, 16, 0, false), box(-armW / 2.0, -10, -2, armW, 12, 4, 40, 32, o.overlay() ? (o.layers3d() ? 0 : 0.25) : -99, true)),
					rShoulder, pose.rArm, pose.body, skin, o, neck);
			addPart(quads, List.of(box(-armW / 2.0, -10, -2, armW, 12, 4, 32, 48, 0, false), box(-armW / 2.0, -10, -2, armW, 12, 4, 48, 48, o.overlay() ? (o.layers3d() ? 0 : 0.25) : -99, true)),
					lShoulder, pose.lArm, pose.body, skin, o, neck);
			// the legs hang from the hips wherever the body's lean puts them (they don't lean with it)
			double[] hipAt = mul(rotation(pose.body), 0, (12 - body) - neck[1], 0);
			double hip = hipAt[1] + neck[1];
			double hipZ = hipAt[2];
			addPart(quads, List.of(box(-2, -12, -2, 4, 12, 4, 0, 16, 0, false), box(-2, -12, -2, 4, 12, 4, 0, 32, o.overlay() ? (o.layers3d() ? 0 : 0.25) : -99, true)),
					new double[] {-2 + hipAt[0], hip, hipZ}, pose.rLeg, null, skin, o);
			addPart(quads, List.of(box(-2, -12, -2, 4, 12, 4, 16, 48, 0, false), box(-2, -12, -2, 4, 12, 4, 0, 48, o.overlay() ? (o.layers3d() ? 0 : 0.25) : -99, true)),
					new double[] {2 + hipAt[0], hip, hipZ}, pose.lLeg, null, skin, o);
			if (o.cape() && tex.cape() != null) {
				Box c = box(-5, -16, 0, 10, 16, 1, 0, 0, 0, false);
				double[] capeRot = {pose.cape, 180, 0};
				addBox(quads, c, tex.cape(), 64, rotateFn(new double[] {0, 24 - body, -2}, capeRot, pose.body, neck), false);
			}
		}
		quads.removeIf(q -> q == null);

		// camera
		double yaw = Math.toRadians(o.yaw()), pitch = Math.toRadians(o.pitch());
		for (Quad q : quads) {
			for (double[] p : q.p()) {
				double x = p[0] * Math.cos(yaw) + p[2] * Math.sin(yaw);
				double z = -p[0] * Math.sin(yaw) + p[2] * Math.cos(yaw);
				double y = p[1] * Math.cos(pitch) - z * Math.sin(pitch);
				z = p[1] * Math.sin(pitch) + z * Math.cos(pitch);
				p[0] = x;
				p[1] = y;
				p[2] = z;
			}
		}
		double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
		for (Quad q : quads) {
			for (double[] p : q.p()) {
				minX = Math.min(minX, p[0]);
				maxX = Math.max(maxX, p[0]);
				minY = Math.min(minY, p[1]);
				maxY = Math.max(maxY, p[1]);
			}
		}
		if (frame != null && frame[4] == 0) {
			frame[0] = Math.min(frame[0], minX);
			frame[1] = Math.max(frame[1], maxX);
			frame[2] = Math.min(frame[2], minY);
			frame[3] = Math.max(frame[3], maxY);
			return null;
		}
		if (frame != null) {
			minX = frame[0];
			maxX = frame[1];
			minY = frame[2];
			maxY = frame[3];
		}
		double span = Math.max(maxX - minX, maxY - minY) * 1.08;
		double scale = big / span;
		double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;

		int[] color = new int[big * big];
		float[] depth = new float[big * big];
		java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
		// opaque base layer first, overlays after (their transparent pixels must not hide anything behind)
		for (int pass = 0; pass < 2; pass++) {
			for (Quad q : quads) {
				if (q.overlay() != (pass == 1)) {
					continue;
				}
				double[][] s = new double[4][];
				for (int i = 0; i < 4; i++) {
					double[] p = q.p()[i];
					s[i] = new double[] {(p[0] - cx) * scale + big / 2.0, big / 2.0 - (p[1] - cy) * scale, p[2]};
				}
				// back faces away
				double cross = (s[1][0] - s[0][0]) * (s[3][1] - s[0][1]) - (s[1][1] - s[0][1]) * (s[3][0] - s[0][0]);
				if (cross <= 0 && q.cull()) {
					continue;
				}
				double shade = q.shade();
				raster(color, depth, big, s[0], s[1], s[2], 0, 0, 1, 0, 1, 1, q, shade);
				raster(color, depth, big, s[0], s[2], s[3], 0, 0, 1, 1, 0, 1, q, shade);
			}
		}
		BufferedImage hi = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
		hi.setRGB(0, 0, big, big, color, 0, big);
		BufferedImage out = new BufferedImage(o.size(), o.size(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		if (o.background() != 0) {
			g.setColor(new java.awt.Color(o.background(), true));
			g.fillRect(0, 0, o.size(), o.size());
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		BufferedImage step = hi;
		while (step.getWidth() / 2 >= o.size()) {
			BufferedImage half = new BufferedImage(step.getWidth() / 2, step.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
			Graphics2D hg = half.createGraphics();
			hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			hg.drawImage(step, 0, 0, half.getWidth(), half.getHeight(), null);
			hg.dispose();
			step = half;
		}
		g.drawImage(step, 0, 0, o.size(), o.size(), null);
		g.dispose();
		return out;
	}

	private interface Transform {
		double[] apply(double x, double y, double z);
	}

	private static void addPart(List<Quad> quads, List<Box> boxes, double[] pivot, double[] joint, double[] bodyRot, BufferedImage skin, Options o) {
		addPart(quads, boxes, pivot, joint, bodyRot, skin, o, pivot);
	}

	private static void addPart(List<Quad> quads, List<Box> boxes, double[] pivot, double[] joint, double[] bodyRot, BufferedImage skin, Options o, double[] bodyPivot) {
		Transform t = rotateFn(pivot, joint, bodyRot, bodyPivot);
		for (Box b : boxes) {
			if (b.x0() < -50) {
				continue; // overlay switched off
			}
			if (b.overlay() && o.layers3d()) {
				addVoxels(quads, b, skin, t);
			} else {
				addBox(quads, b, skin, 64, t, b.overlay());
			}
		}
	}

	/**
	 * 3D skin layers: every visible overlay pixel becomes a small cube standing on the base box's surface (like the
	 * "3D Skin Layers" mod), so hats, jackets and sleeves have real depth.
	 */
	private static void addVoxels(List<Quad> quads, Box b, BufferedImage tex, Transform t) {
		double sc = tex.getWidth() / 64.0;
		int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
		double x0 = b.x0(), y0 = b.y0(), z0 = b.z0(), x1 = b.x1(), y1 = b.y1(), z1 = b.z1();
		double[] lbf = {x0, y0, z1}, rbf = {x1, y0, z1}, ltf = {x0, y1, z1}, rtf = {x1, y1, z1};
		double[] lbb = {x0, y0, z0}, rbb = {x1, y0, z0}, ltb = {x0, y1, z0}, rtb = {x1, y1, z0};
		Object[][] faces = {
				{new double[][] {ltf, rtf, rbf, lbf}, u + d, v + d, w, h},
				{new double[][] {rtb, ltb, lbb, rbb}, u + 2 * d + w, v + d, w, h},
				{new double[][] {ltb, ltf, lbf, lbb}, u, v + d, d, h},
				{new double[][] {rtf, rtb, rbb, rbf}, u + d + w, v + d, d, h},
				{new double[][] {ltb, rtb, rtf, ltf}, u + d, v, w, d},
				{new double[][] {lbf, rbf, rbb, lbb}, u + d + w, v, w, d}};
		double thick = 0.9;
		for (Object[] f : faces) {
			double[][] c = (double[][]) f[0];
			int fu = (int) f[1], fv = (int) f[2], fw = (int) f[3], fh = (int) f[4];
			double[] du = sub(c[1], c[0]), dv = sub(c[3], c[0]);
			double[] n = cross(du, dv);
			double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (len < 1e-9) {
				continue;
			}
			// outward = -(du x dv) for this corner order
			n = new double[] {-n[0] / len * thick, -n[1] / len * thick, -n[2] / len * thick};
			for (int j = 0; j < fh; j++) {
				for (int i = 0; i < fw; i++) {
					int px = (int) ((fu + i) * sc), py = (int) ((fv + j) * sc);
					if (px >= tex.getWidth() || py >= tex.getHeight() || (tex.getRGB(px, py) >>> 24) < 128) {
						continue;
					}
					double[] a = add(c[0], add(scale(du, i / (double) fw), scale(dv, j / (double) fh)));
					double[] bu = scale(du, 1.0 / fw), bv = scale(dv, 1.0 / fh);
					voxel(quads, a, bu, bv, n, t, tex, px, py, (int) Math.max(1, Math.round(sc)));
				}
			}
		}
	}

	/** One cube from corner a spanning bu, bv (on the surface) and n (outwards), all faces the texel's colour. */
	private static void voxel(List<Quad> quads, double[] a, double[] bu, double[] bv, double[] n, Transform t, BufferedImage tex, int px, int py, int span) {
		double[] p000 = a, p100 = add(a, bu), p010 = add(a, bv), p110 = add(add(a, bu), bv);
		double[] p001 = add(p000, n), p101 = add(p100, n), p011 = add(p010, n), p111 = add(p110, n);
		double[][][] fs = {
				{p001, p101, p111, p011}, // outer
				{p000, p100, p101, p001}, // edge along u (top)
				{p010, p110, p111, p011}, // edge along u (bottom)
				{p000, p010, p011, p001}, // edge along v (left)
				{p100, p110, p111, p101}, // edge along v (right)
		};
		double[] shades = {1.0, 0.92, 0.7, 0.8, 0.8};
		for (int k = 0; k < fs.length; k++) {
			double[][] pts = new double[4][];
			for (int m = 0; m < 4; m++) {
				pts[m] = t.apply(fs[k][m][0], fs[k][m][1], fs[k][m][2]);
			}
			quads.add(new Quad(pts, px, py, Math.min(tex.getWidth(), px + span), Math.min(tex.getHeight(), py + span), tex, true, shades[k], false));
		}
	}

	private static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] scale(double[] a, double f) {
		return new double[] {a[0] * f, a[1] * f, a[2] * f};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	/** Local point -> joint rotation -> pivot -> (optional) body rotation around the neck. */
	private static Transform rotateFn(double[] pivot, double[] joint, double[] bodyRot, double[] bodyPivot) {
		double[][] rj = rotation(joint);
		double[][] rb = bodyRot == null ? null : rotation(bodyRot);
		return (x, y, z) -> {
			double[] p = mul(rj, x, y, z);
			p[0] += pivot[0];
			p[1] += pivot[1];
			p[2] += pivot[2];
			if (rb != null) {
				double[] q = mul(rb, p[0] - bodyPivot[0], p[1] - bodyPivot[1], p[2] - bodyPivot[2]);
				p[0] = q[0] + bodyPivot[0];
				p[1] = q[1] + bodyPivot[1];
				p[2] = q[2] + bodyPivot[2];
			}
			return p;
		};
	}

	/** Rotation for joint angles: x swings the limb forward (toward +z) for positive values, like Minecraft's arms. */
	private static double[][] rotation(double[] deg) {
		double ax = Math.toRadians(-deg[0]), ay = Math.toRadians(deg[1]), az = Math.toRadians(deg[2]);
		double[][] rx = {{1, 0, 0}, {0, Math.cos(ax), -Math.sin(ax)}, {0, Math.sin(ax), Math.cos(ax)}};
		double[][] ry = {{Math.cos(ay), 0, Math.sin(ay)}, {0, 1, 0}, {-Math.sin(ay), 0, Math.cos(ay)}};
		double[][] rz = {{Math.cos(az), -Math.sin(az), 0}, {Math.sin(az), Math.cos(az), 0}, {0, 0, 1}};
		return matmul(ry, matmul(rx, rz));
	}

	private static double[][] matmul(double[][] a, double[][] b) {
		double[][] r = new double[3][3];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
			}
		}
		return r;
	}

	private static double[] mul(double[][] m, double x, double y, double z) {
		return new double[] {m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z, m[2][0] * x + m[2][1] * y + m[2][2] * z};
	}

	/**
	 * The six faces of a box with Minecraft's UV unwrap (texture pixels, scaled for HD textures). The player faces +z
	 * (towards the camera at yaw 0); their right side is -x.
	 */
	private static void addBox(List<Quad> quads, Box b, BufferedImage tex, int baseWidth, Transform t, boolean overlay) {
		int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
		double x0 = b.x0(), y0 = b.y0(), z0 = b.z0(), x1 = b.x1(), y1 = b.y1(), z1 = b.z1();
		double s = tex.getWidth() / (double) baseWidth;
		// corners: name by (x,y,z) min/max
		double[] lbf = {x0, y0, z1}, rbf = {x1, y0, z1}, ltf = {x0, y1, z1}, rtf = {x1, y1, z1};
		double[] lbb = {x0, y0, z0}, rbb = {x1, y0, z0}, ltb = {x0, y1, z0}, rtb = {x1, y1, z0};
		// quad points: top-left, top-right, bottom-right, bottom-left as seen from outside
		quads.add(quad(t, new double[][] {ltf, rtf, rbf, lbf}, u + d, v + d, w, h, s, tex, overlay, 1.0));               // front (+z)
		quads.add(quad(t, new double[][] {rtb, ltb, lbb, rbb}, u + 2 * d + w, v + d, w, h, s, tex, overlay, 0.82));      // back (-z)
		quads.add(quad(t, new double[][] {ltb, ltf, lbf, lbb}, u, v + d, d, h, s, tex, overlay, 0.78));                  // player's right (-x)
		quads.add(quad(t, new double[][] {rtf, rtb, rbb, rbf}, u + d + w, v + d, d, h, s, tex, overlay, 0.78));          // player's left (+x)
		quads.add(quad(t, new double[][] {ltb, rtb, rtf, ltf}, u + d, v, w, d, s, tex, overlay, 1.12));                  // top
		quads.add(quad(t, new double[][] {lbf, rbf, rbb, lbb}, u + d + w, v, w, d, s, tex, overlay, 0.6));               // bottom
	}

	private static Quad quad(Transform t, double[][] corners, int u, int v, int w, int h, double s, BufferedImage tex, boolean overlay, double shade) {
		double[][] p = new double[4][];
		for (int i = 0; i < 4; i++) {
			p[i] = t.apply(corners[i][0], corners[i][1], corners[i][2]);
		}
		int u0 = (int) Math.round(u * s), v0 = (int) Math.round(v * s), u1 = (int) Math.round((u + w) * s), v1 = (int) Math.round((v + h) * s);
		if (u1 > tex.getWidth() || v1 > tex.getHeight()) {
			return null;
		}
		return new Quad(p, u0, v0, u1, v1, tex, overlay, shade, true);
	}

	/** One triangle with (tu,tv) in 0..1 of the quad's texture rectangle, depth-tested, alpha-tested. */
	private static void raster(int[] color, float[] depth, int size, double[] a, double[] b, double[] c,
			double au, double av, double bu, double bv, double cu, double cv, Quad q, double shade) {
		int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
		int maxX = (int) Math.min(size - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
		int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
		int maxY = (int) Math.min(size - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
		double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
		if (Math.abs(area) < 1e-9) {
			return;
		}
		int tw = q.u1() - q.u0(), th = q.v1() - q.v0();
		BufferedImage tex = q.tex();
		for (int y = minY; y <= maxY; y++) {
			double py = y + 0.5;
			for (int x = minX; x <= maxX; x++) {
				double px = x + 0.5;
				double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
				double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
				double w2 = 1 - w0 - w1;
				if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) {
					continue;
				}
				float z = (float) (w0 * a[2] + w1 * b[2] + w2 * c[2]);
				int idx = y * size + x;
				if (z <= depth[idx]) {
					continue;
				}
				double tu = w0 * au + w1 * bu + w2 * cu, tv = w0 * av + w1 * bv + w2 * cv;
				int sx = q.u0() + Math.min(tw - 1, Math.max(0, (int) (tu * tw)));
				int sy = q.v0() + Math.min(th - 1, Math.max(0, (int) (tv * th)));
				int argb = tex.getRGB(sx, sy);
				int alpha = argb >>> 24;
				if (alpha < 128) {
					continue;
				}
				int r = (int) Math.min(255, ((argb >> 16) & 255) * shade);
				int gg = (int) Math.min(255, ((argb >> 8) & 255) * shade);
				int bb = (int) Math.min(255, (argb & 255) * shade);
				color[idx] = 0xff000000 | r << 16 | gg << 8 | bb;
				depth[idx] = z;
			}
		}
	}

	/** The flat face with its hat layer. */
	static BufferedImage face(BufferedImage skin, int size, boolean overlay) {
		int s = skin.getWidth() / 64;
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(skin, 0, 0, size, size, 8 * s, 8 * s, 16 * s, 16 * s, null);
		if (overlay) {
			g.drawImage(skin, 0, 0, size, size, 40 * s, 8 * s, 48 * s, 16 * s, null);
		}
		g.dispose();
		return out;
	}
}
