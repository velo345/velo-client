package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo "Terrain Culling Window" (Sodium 0.9 on 26.2+): Sodium throws its render lists and its visibility search
 * away whenever the camera changes at all, so while you move or turn it walks the whole section tree against the
 * frustum on the render thread EVERY frame (19% of the render thread in a flight over terrain, 2026-11-26 profile) and
 * keeps its async cull thread busy back to back.
 *
 * <p>Here the traversal runs against a slightly larger frustum (shifted back by 1.5 blocks and widened by ~3 degrees,
 * so it contains every view the camera can have within 0.6 blocks / 2.5 degrees of where it was) and Sodium is told "the
 * camera did not change" for as long as the camera stays inside that tolerance, up to the configured window. A superset
 * of sections is drawn (the GPU clips the rest), nothing visible can be missing; past the tolerance everything is
 * recomputed as usual. Any failure turns it off for the session.
 */
public final class SodiumCameraReuse {

	private SodiumCameraReuse() {
	}

	//? if >=26.2 {
	/*private static final double POS_TOL = 0.6;
	private static final double ROT_TOL = 2.5;
	private static final double MARGIN = 3.2;   // degrees the frustum is widened by (rotation tolerance + a little)
	private static final float BACK = 1.5F;

	private static boolean broken;
	private static boolean anchorValid;
	private static double ax;
	private static double ay;
	private static double az;
	private static final org.joml.Quaternionf aRot = new org.joml.Quaternionf();
	private static long anchorAt;
	private static float anchorFog;
	private static float anchorRow0;
	private static float anchorRow1;
	private static long anchorSection;
	private static net.caffeinemc.mods.sodium.client.render.viewport.Viewport anchorViewport;
	private static boolean suppress;

	public static boolean active() {
		return !broken && PerformanceBoostModule.terrainReuseMs > 0;
	}

	// True while Sodium's "camera changed" notification should be skipped for this setupTerrain call.
	public static boolean suppressed() {
		return suppress;
	}

	public static net.caffeinemc.mods.sodium.client.render.viewport.Viewport prepare(net.minecraft.client.Camera camera,
			net.caffeinemc.mods.sodium.client.render.viewport.Viewport original, float fogCull, org.joml.Matrix4f cullMatrix) {
		suppress = false;
		try {
			var p = camera.position();
			var rot = camera.rotation();
			long now = System.nanoTime();
			float row0 = (float) Math.sqrt(cullMatrix.m00() * cullMatrix.m00() + cullMatrix.m10() * cullMatrix.m10() + cullMatrix.m20() * cullMatrix.m20());
			float row1 = (float) Math.sqrt(cullMatrix.m01() * cullMatrix.m01() + cullMatrix.m11() * cullMatrix.m11() + cullMatrix.m21() * cullMatrix.m21());
			long section = net.minecraft.core.SectionPos.asLong(net.minecraft.core.SectionPos.posToSectionCoord(p.x()),
					net.minecraft.core.SectionPos.posToSectionCoord(p.y()), net.minecraft.core.SectionPos.posToSectionCoord(p.z()));
			if (anchorValid && now - anchorAt < PerformanceBoostModule.terrainReuseMs * 1_000_000L && fogCull == anchorFog
					&& section == anchorSection && Math.abs(row0 - anchorRow0) < 1.0E-4F && Math.abs(row1 - anchorRow1) < 1.0E-4F) {
				double dx = p.x() - ax;
				double dy = p.y() - ay;
				double dz = p.z() - az;
				// the true rotation between the two orientations (roll included - at a steep pitch a yaw turn is a roll)
				double turn = Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs((double) aRot.dot(rot)))));
				if (dx * dx + dy * dy + dz * dz <= POS_TOL * POS_TOL && turn <= ROT_TOL) {
					suppress = true;
					PerformanceBoostModule.terrainReuseFrames++;
					return anchorViewport;
				}
			}
			// a new anchor: the frustum Sodium searches with is larger than the real one
			var f = camera.forwardVector();
			// widen every half-angle by MARGIN: the projection's row lengths are 1/tan(half angle)
			double tanX = 1.0 / row0;
			double tanY = 1.0 / row1;
			float sx = (float) (tanX / Math.tan(Math.atan(tanX) + Math.toRadians(MARGIN)));
			float sy = (float) (tanY / Math.tan(Math.atan(tanY) + Math.toRadians(MARGIN)));
			org.joml.Matrix4f widened = new org.joml.Matrix4f().scale(sx, sy, 1.0F).mul(cullMatrix).translate(f.x() * BACK, f.y() * BACK, f.z() * BACK);
			var viewport = new net.caffeinemc.mods.sodium.client.render.viewport.Viewport(
					new net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum(new org.joml.FrustumIntersection(widened)),
					new org.joml.Vector3d(p.x(), p.y(), p.z()));
			anchorValid = true;
			anchorViewport = viewport;
			ax = p.x();
			ay = p.y();
			az = p.z();
			aRot.set(rot);
			anchorAt = now;
			anchorFog = fogCull;
			anchorRow0 = row0;
			anchorRow1 = row1;
			anchorSection = section;
			PerformanceBoostModule.terrainReuseAnchors++;
			return viewport;
		} catch (Throwable t) {
			broken = true;
			anchorValid = false;
			suppress = false;
			net.veloclient.velo.VeloClient.LOGGER.warn("Terrain Culling Window failed - Sodium culls every frame again this session", t);
			return original;
		}
	}

	public static void done() {
		suppress = false;
	}

	// Chunk loads/unloads and graph updates keep working (Sodium marks the graph dirty itself); a world change restarts.
	public static void reset() {
		anchorValid = false;
		anchorViewport = null;
	}
	*///?} else {
	public static boolean active() {
		return false;
	}
	//?}
}
