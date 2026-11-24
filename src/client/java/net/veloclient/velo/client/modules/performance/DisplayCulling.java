package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo - culling for display entities (block / item / text displays, what server spawns are decorated with).
 * Vanilla only frustum-tests a display when the server gave it a culling size ({@code width} and {@code height}); most
 * plugins leave those at 0, which switches culling OFF - every display was drawn every frame, behind the camera and
 * behind walls too. LuckySMP's spawn (2026-11-21) has 225 block displays and 71 text displays: drawing them was the
 * biggest part of the frame there.
 *
 * <p>For those displays a size is now worked out from what is actually drawn, always on the generous side: the shape's
 * corners (block model -1..2, item model -6..6, text from its laid-out lines with margin) through the display's own
 * transformation, at both ends of a running transformation animation, turned into a sphere around the display (so
 * billboard turning can't push anything outside). Only when that whole sphere is outside the view - or, with Sodium, in
 * chunk sections Sodium found hidden this frame, the same test Sodium uses for every mob - is the display skipped.
 * Text whose layout isn't known yet is never skipped. Off with {@code -Dvelo.displayCull=false} or Turbo off. 26.2+.
 */
public final class DisplayCulling {

	public static final boolean ON = !"false".equals(System.getProperty("velo.displayCull"));
	private static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
	/** Profiling counters. */
	public static int tested;
	public static int culled;
	private static final boolean PROFILE = Boolean.getBoolean("velo.renderProfile");

	private DisplayCulling() {
	}

	//? if >=1.21.11 && <26.1 {
	private static final org.joml.Vector3f CORNER = new org.joml.Vector3f();

	// False only when the display certainly can't be seen; render thread.
	public static boolean visible(net.minecraft.entity.decoration.DisplayEntity display, net.minecraft.client.render.Frustum frustum) {
		if (!ON || !PerformanceBoostModule.turbo) {
			return true;
		}
		var state = display.getRenderState();
		if (state == null) {
			return true;
		}
		float lo;
		float hi;
		float minY;
		float maxY;
		float depth;
		if (display instanceof net.minecraft.entity.decoration.DisplayEntity.BlockDisplayEntity) {
			lo = -1f;
			hi = 2f;
			minY = -1f;
			maxY = 2f;
			depth = 0f;
		} else if (display instanceof net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity) {
			// item models can be scaled and moved a lot by their own display transforms (up to 4x, 5 blocks): generous
			lo = -6f;
			hi = 6f;
			minY = -6f;
			maxY = 6f;
			depth = 0f;
		} else if (display instanceof net.veloclient.velo.client.mixin.HologramAccessor hologram) {
			var layout = hologram.velo$layout();
			if (layout == null) {
				return true; // not laid out yet - draw it
			}
			// text is drawn at 1/40 block per pixel around the origin: half the widest line left and right, the lines
			// (10 px each, plus background padding) above - doubled margins in every direction
			float halfWidth = (layout.width() / 2f + 4f) * 0.025f * 1.5f;
			float height = (layout.lines().size() * 10f + 4f) * 0.025f * 1.5f;
			lo = -halfWidth;
			hi = halfWidth;
			minY = -height;
			maxY = height;
			depth = 0.5f;
		} else {
			return true;
		}
		float radius = Math.max(reach(state.transformation().interpolate(0f), lo, hi, minY, maxY, depth),
				reach(state.transformation().interpolate(1f), lo, hi, minY, maxY, depth)) + 0.5f;
		if (!Float.isFinite(radius) || radius > 512f) {
			return true;
		}
		if (PROFILE && (++tested & 0x3FFFF) == 0) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo display culling: {} of the last {} untested displays skipped", culled, 0x40000);
			culled = 0;
		}
		double x = display.getX();
		double y = display.getY();
		double z = display.getZ();
		var box = new net.minecraft.util.math.Box(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
		if (!frustum.isVisible(box)) {
			culled++;
			return false;
		}
		if (SODIUM && !SodiumVisibility.boxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
			culled++;
			return false;
		}
		return true;
	}

	// The farthest any corner of the shape gets from the display's origin under this transformation.
	private static float reach(net.minecraft.util.math.AffineTransformation transformation, float lo, float hi, float minY, float maxY, float depth) {
		if (transformation == null) {
			return Float.POSITIVE_INFINITY;
		}
		org.joml.Matrix4fc matrix = transformation.getMatrix();
		float best = 0f;
		for (int i = 0; i < 8; i++) {
			CORNER.set((i & 1) == 0 ? lo : hi, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? Math.min(lo, -depth) : Math.max(hi, depth));
			matrix.transformPosition(CORNER);
			best = Math.max(best, CORNER.length());
		}
		return best;
	}
	//?} else if >=26.2 {
	/*private static final org.joml.Vector3f CORNER = new org.joml.Vector3f();

	// False only when the display certainly can't be seen; render thread.
	public static boolean visible(net.minecraft.world.entity.Display display, net.minecraft.client.renderer.culling.Frustum frustum) {
		if (!ON || !PerformanceBoostModule.turbo) {
			return true;
		}
		var state = display.renderState();
		if (state == null) {
			return true;
		}
		float lo;
		float hi;
		float minY;
		float maxY;
		float depth;
		if (display instanceof net.minecraft.world.entity.Display.BlockDisplay) {
			lo = -1f;
			hi = 2f;
			minY = -1f;
			maxY = 2f;
			depth = 0f;
		} else if (display instanceof net.minecraft.world.entity.Display.ItemDisplay) {
			// item models can be scaled and moved a lot by their own display transforms (up to 4x, 5 blocks): generous
			lo = -6f;
			hi = 6f;
			minY = -6f;
			maxY = 6f;
			depth = 0f;
		} else if (display instanceof net.veloclient.velo.client.mixin.HologramAccessor hologram) {
			var layout = hologram.velo$layout();
			if (layout == null) {
				return true; // not laid out yet - draw it
			}
			// text is drawn at 1/40 block per pixel around the origin: half the widest line left and right, the lines
			// (10 px each, plus background padding) above - doubled margins in every direction
			float halfWidth = (layout.width() / 2f + 4f) * 0.025f * 1.5f;
			float height = (layout.lines().size() * 10f + 4f) * 0.025f * 1.5f;
			lo = -halfWidth;
			hi = halfWidth;
			minY = -height;
			maxY = height;
			depth = 0.5f;
		} else {
			return true;
		}
		float radius = Math.max(reach(state.transformation().get(0f), lo, hi, minY, maxY, depth),
				reach(state.transformation().get(1f), lo, hi, minY, maxY, depth)) + 0.5f;
		if (!Float.isFinite(radius) || radius > 512f) {
			return true;
		}
		if (PROFILE && (++tested & 0x3FFFF) == 0) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo display culling: {} of the last {} untested displays skipped", culled, 0x40000);
			culled = 0;
		}
		double x = display.getX();
		double y = display.getY();
		double z = display.getZ();
		var box = new net.minecraft.world.phys.AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
		if (!frustum.isVisible(box)) {
			culled++;
			return false;
		}
		if (SODIUM && !SodiumVisibility.boxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
			culled++;
			return false;
		}
		return true;
	}

	// The farthest any corner of the shape gets from the display's origin under this transformation.
	private static float reach(com.mojang.math.Transformation transformation, float lo, float hi, float minY, float maxY, float depth) {
		if (transformation == null) {
			return Float.POSITIVE_INFINITY;
		}
		org.joml.Matrix4fc matrix = transformation.getMatrix();
		float best = 0f;
		for (int i = 0; i < 8; i++) {
			CORNER.set((i & 1) == 0 ? lo : hi, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? Math.min(lo, -depth) : Math.max(hi, depth));
			matrix.transformPosition(CORNER);
			best = Math.max(best, CORNER.length());
		}
		return best;
	}
	*///?}
}
