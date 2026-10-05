package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;

/**
 * Version-neutral reads of the things the debug screen shows (position, look direction, light,
 * time, what you're looking at, server info, distances). Every method is null/zero-safe when
 * there's no world or player.
 */
public final class WorldInfo {

	private WorldInfo() {
	}

	//? if <26.1 {
	public static boolean inWorld() {
		MinecraftClient c = MinecraftClient.getInstance();
		return c.world != null && c.player != null;
	}

	public static double[] position() {
		var p = MinecraftClient.getInstance().player;
		return new double[] {p.getX(), p.getY(), p.getZ()};
	}

	public static int[] blockPos() {
		var b = MinecraftClient.getInstance().player.getBlockPos();
		return new int[] {b.getX(), b.getY(), b.getZ()};
	}

	public static float[] rotation() {
		var p = MinecraftClient.getInstance().player;
		return new float[] {net.minecraft.util.math.MathHelper.wrapDegrees(p.getYaw()), p.getPitch()};
	}

	public static String facing() {
		return MinecraftClient.getInstance().player.getHorizontalFacing().asString();
	}

	public static int[] light() {
		var c = MinecraftClient.getInstance();
		var pos = c.player.getBlockPos();
		return new int[] {c.world.getLightLevel(net.minecraft.world.LightType.SKY, pos), c.world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos)};
	}

	public static long dayTime() {
		return MinecraftClient.getInstance().world.getTimeOfDay();
	}

	public static String weather() {
		var w = MinecraftClient.getInstance().world;
		return w.isThundering() ? "Thunder" : w.isRaining() ? "Rain" : "Clear";
	}

	/** {id, "x y z"} of the block under the crosshair, or null. */
	public static String[] targetBlock() {
		var c = MinecraftClient.getInstance();
		if (c.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
			var pos = hit.getBlockPos();
			var block = c.world.getBlockState(pos).getBlock();
			return new String[] {net.minecraft.registry.Registries.BLOCK.getId(block).toString(), pos.getX() + " " + pos.getY() + " " + pos.getZ()};
		}
		return null;
	}

	public static String targetEntity() {
		var c = MinecraftClient.getInstance();
		if (c.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult hit) {
			return net.minecraft.registry.Registries.ENTITY_TYPE.getId(hit.getEntity().getType()).toString();
		}
		return null;
	}

	public static String brand() {
		var h = MinecraftClient.getInstance().getNetworkHandler();
		return h == null ? null : h.getBrand();
	}

	public static int ping() {
		var c = MinecraftClient.getInstance();
		var h = c.getNetworkHandler();
		var entry = h == null || c.player == null ? null : h.getPlayerListEntry(c.player.getUuid());
		return entry == null ? -1 : entry.getLatency();
	}

	/** Server's simulation distance in chunks (what's actually ticked around you). */
	public static int simulationDistance() {
		var w = MinecraftClient.getInstance().world;
		return w == null ? 0 : w.getSimulationDistance();
	}

	public static int renderDistance() {
		return MinecraftClient.getInstance().options.getClampedViewDistance();
	}

	public static int loadedChunks() {
		var w = MinecraftClient.getInstance().world;
		return w == null ? 0 : w.getChunkManager().getLoadedChunkCount();
	}

	public static int entityCount() {
		var w = MinecraftClient.getInstance().world;
		return w == null ? 0 : w.getRegularEntityCount();
	}
	//?} else {
	/*public static boolean inWorld() {
		MinecraftClient c = MinecraftClient.getInstance();
		return c.level != null && c.player != null;
	}

	public static double[] position() {
		var p = MinecraftClient.getInstance().player;
		return new double[] {p.getX(), p.getY(), p.getZ()};
	}

	public static int[] blockPos() {
		var b = MinecraftClient.getInstance().player.blockPosition();
		return new int[] {b.getX(), b.getY(), b.getZ()};
	}

	public static float[] rotation() {
		var p = MinecraftClient.getInstance().player;
		return new float[] {net.minecraft.util.Mth.wrapDegrees(p.getYRot()), p.getXRot()};
	}

	public static String facing() {
		return MinecraftClient.getInstance().player.getDirection().getSerializedName();
	}

	public static int[] light() {
		var c = MinecraftClient.getInstance();
		var pos = c.player.blockPosition();
		return new int[] {c.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos), c.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos)};
	}

	public static long dayTime() {
		return MinecraftClient.getInstance().level.getOverworldClockTime();
	}

	public static String weather() {
		var w = MinecraftClient.getInstance().level;
		return w.isThundering() ? "Thunder" : w.isRaining() ? "Rain" : "Clear";
	}

	public static String[] targetBlock() {
		var c = MinecraftClient.getInstance();
		if (c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var pos = hit.getBlockPos();
			var block = c.level.getBlockState(pos).getBlock();
			return new String[] {net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(), pos.getX() + " " + pos.getY() + " " + pos.getZ()};
		}
		return null;
	}

	public static String targetEntity() {
		var c = MinecraftClient.getInstance();
		if (c.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) {
			return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(hit.getEntity().getType()).toString();
		}
		return null;
	}

	public static String brand() {
		var h = MinecraftClient.getInstance().getConnection();
		return h == null ? null : h.serverBrand();
	}

	public static int ping() {
		var c = MinecraftClient.getInstance();
		var h = c.getConnection();
		var entry = h == null || c.player == null ? null : h.getPlayerInfo(c.player.getUUID());
		return entry == null ? -1 : entry.getLatency();
	}

	public static int simulationDistance() {
		var w = MinecraftClient.getInstance().level;
		return w == null ? 0 : w.getServerSimulationDistance();
	}

	public static int renderDistance() {
		return MinecraftClient.getInstance().options.getEffectiveRenderDistance();
	}

	public static int loadedChunks() {
		var w = MinecraftClient.getInstance().level;
		return w == null ? 0 : w.getChunkSource().getLoadedChunksCount();
	}

	public static int entityCount() {
		var w = MinecraftClient.getInstance().level;
		return w == null ? 0 : w.getEntityCount();
	}
	*///?}

	/** "overworld" / "the_nether" / "the_end" / other path. */
	public static String dimension() {
		String id = ClientCompat.dimensionId();
		return id == null ? "" : id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
	}
}
