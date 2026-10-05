package net.veloclient.velo.client.modules.servertools;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;

/** Wireframe shapes drawn with debug gizmos (depth-tested, so terrain hides what's behind it). */
final class RadiusShapes {

	private RadiusShapes() {
	}

	/**
	 * A wire sphere: {@code rings} horizontal circles plus as many meridians. The equator (at the
	 * center's height) is drawn thicker so the radius on your own level reads clearly.
	 */
	static void sphere(double cx, double cy, double cz, double radius, int color, float width, int rings) {
		int segments = Math.max(24, rings * 3);
		for (int r = 1; r < rings; r++) {
			double phi = Math.PI * r / rings;
			double y = cy + radius * Math.cos(phi);
			double ringRadius = radius * Math.sin(phi);
			boolean equator = r * 2 == rings;
			circle(cx, y, cz, ringRadius, color, equator ? width * 2.2f : width, segments);
		}
		int meridians = Math.max(8, rings);
		int steps = Math.max(16, segments / 2);
		for (int m = 0; m < meridians; m++) {
			double theta = Math.PI * 2 * m / meridians;
			double sin = Math.sin(theta);
			double cos = Math.cos(theta);
			Vec3d previous = null;
			for (int i = 0; i <= steps; i++) {
				double phi = Math.PI * i / steps;
				double ring = radius * Math.sin(phi);
				Vec3d point = new Vec3d(cx + ring * cos, cy + radius * Math.cos(phi), cz + ring * sin);
				if (previous != null) {
					GizmoDrawing.line(previous, point, color, width);
				}
				previous = point;
			}
		}
	}

	static void circle(double cx, double y, double cz, double radius, int color, float width, int segments) {
		Vec3d previous = null;
		for (int i = 0; i <= segments; i++) {
			double a = Math.PI * 2 * i / segments;
			Vec3d point = new Vec3d(cx + radius * Math.cos(a), y, cz + radius * Math.sin(a));
			if (previous != null) {
				GizmoDrawing.line(previous, point, color, width);
			}
			previous = point;
		}
	}

	static void line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
		GizmoDrawing.line(new Vec3d(x1, y1, z1), new Vec3d(x2, y2, z2), color, width);
	}
}
