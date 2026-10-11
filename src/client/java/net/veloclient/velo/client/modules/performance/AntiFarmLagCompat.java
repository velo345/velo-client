package net.veloclient.velo.client.modules.performance;

/** Version-specific reads for {@link AntiFarmLagModule}. */
final class AntiFarmLagCompat {

	private AntiFarmLagCompat() {
	}

	static Object world() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().world;
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().level;
		*///?}
	}

	static double[] playerPos() {
		//? if <26.1 {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		//?} else {
		/*var player = net.minecraft.client.Minecraft.getInstance().player;
		*///?}
		return player == null ? null : new double[] {player.getX(), player.getY(), player.getZ()};
	}

	/** The player's section coordinates {x, y, z}, or null. */
	static int[] cameraSection() {
		double[] pos = playerPos();
		return pos == null ? null : new int[] {(int) Math.floor(pos[0]) >> 4, (int) Math.floor(pos[1]) >> 4, (int) Math.floor(pos[2]) >> 4};
	}
}
