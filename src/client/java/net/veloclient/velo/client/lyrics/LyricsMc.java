package net.veloclient.velo.client.lyrics;

import net.minecraft.client.MinecraftClient;

/** The Minecraft calls Lyrics needs, one {@code //? if} branch per mapping. */
final class LyricsMc {

	private LyricsMc() {
	}

	/** The render camera's position, or null outside a world. */
	static double[] cameraPosition() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		//? if <26.1 {
		var position = client.gameRenderer.getCamera().getCameraPos();
		//?} else if <26.2 {
		/*var position = client.gameRenderer.getMainCamera().position();
		*///?} else {
		/*var position = client.gameRenderer.mainCamera().position();
		*///?}
		return new double[] {position.x, position.y, position.z};
	}

	/** How far the world is drawn, in blocks. */
	static double renderDistanceBlocks() {
		return net.veloclient.velo.client.util.WorldInfo.renderDistance() * 16.0;
	}

	/** Nothing solid between the camera and the point (also works in third person). */
	static boolean canSee(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		double[] cam = cameraPosition();
		if (cam == null) {
			return true;
		}
		//? if <26.1 {
		if (client.world == null) {
			return true;
		}
		var from = new net.minecraft.util.math.Vec3d(cam[0], cam[1], cam[2]);
		var to = new net.minecraft.util.math.Vec3d(x, y, z);
		var hit = client.world.raycast(new net.minecraft.world.RaycastContext(from, to, net.minecraft.world.RaycastContext.ShapeType.VISUAL,
				net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player));
		return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
		//?} else {
		/*if (client.level == null) {
			return true;
		}
		var from = new net.minecraft.world.phys.Vec3(cam[0], cam[1], cam[2]);
		var to = new net.minecraft.world.phys.Vec3(x, y, z);
		var hit = client.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
				net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
		*///?}
	}
}
