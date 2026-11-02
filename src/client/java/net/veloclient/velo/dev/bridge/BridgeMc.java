package net.veloclient.velo.dev.bridge;

import java.util.UUID;

/** The few game lookups the bridge needs, once per Minecraft version. */
final class BridgeMc {

	private BridgeMc() {
	}

	//? if <26.1 {
	/** {x, y (at head height), z} of a player in view distance, or null. */
	static double[] playerPos(UUID uuid) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world == null) {
			return null;
		}
		var p = client.world.getPlayerByUuid(uuid);
		return p == null ? null : new double[] {p.getX(), p.getY() + p.getHeight() + 0.3, p.getZ()};
	}

	static double[] self() {
		var p = net.minecraft.client.MinecraftClient.getInstance().player;
		return p == null ? null : new double[] {p.getX(), p.getY(), p.getZ()};
	}

	static boolean inWorld() {
		return net.minecraft.client.MinecraftClient.getInstance().player != null;
	}

	static boolean isTeammate(net.minecraft.entity.Entity entity) {
		return entity instanceof net.minecraft.entity.player.PlayerEntity && VeloBridgeClient.teammateColor(entity.getUuid()) != null;
	}
	//?} else {
	/*static double[] playerPos(UUID uuid) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null) {
			return null;
		}
		var p = client.level.getPlayerByUUID(uuid);
		return p == null ? null : new double[] {p.getX(), p.getY() + p.getBbHeight() + 0.3, p.getZ()};
	}

	static double[] self() {
		var p = net.minecraft.client.Minecraft.getInstance().player;
		return p == null ? null : new double[] {p.getX(), p.getY(), p.getZ()};
	}

	static boolean inWorld() {
		return net.minecraft.client.Minecraft.getInstance().player != null;
	}

	static boolean isTeammate(net.minecraft.world.entity.Entity entity) {
		return entity instanceof net.minecraft.world.entity.player.Player && VeloBridgeClient.teammateColor(entity.getUUID()) != null;
	}
	*///?}
}
