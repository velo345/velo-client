package net.veloclient.velo.devbuild.colors;

/** The map colour of the block under the crosshair (RGB), or -1. */
final class BlockColor {

	private BlockColor() {
	}

	//? if <26.1 {
	static boolean noScreen() {
		return net.minecraft.client.MinecraftClient.getInstance().currentScreen == null;
	}
	//?} else if <26.2 {
	/*static boolean noScreen() {
		return net.minecraft.client.Minecraft.getInstance().screen == null;
	}
	*///?} else {
	/*static boolean noScreen() {
		return net.minecraft.client.Minecraft.getInstance().gui.screen() == null;
	}
	*///?}

	//? if <26.1 {
	static int underCrosshair() {
		var c = net.minecraft.client.MinecraftClient.getInstance();
		if (c.world != null && c.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
				&& hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
			var pos = hit.getBlockPos();
			return c.world.getBlockState(pos).getMapColor(c.world, pos).color;
		}
		return -1;
	}
	//?} else {
	/*static int underCrosshair() {
		var c = net.minecraft.client.Minecraft.getInstance();
		if (c.level != null && c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
				&& hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var pos = hit.getBlockPos();
			return c.level.getBlockState(pos).getMapColor(c.level, pos).col;
		}
		return -1;
	}
	*///?}
}
