package net.veloclient.velo.client.worldmap;

import net.minecraft.client.MinecraftClient;

/**
 * Every Minecraft call the world map needs, per version - the rest of the map code only deals in
 * plain ints and strings. A "chunk" here is an opaque object handed over by the chunk-load event.
 */
final class MapCompat {

	private MapCompat() {
	}

	/**
	 * Column result: top block Y, RGB color (0 = nothing to draw), water depth (0 = not water), and
	 * the ground height without tree leaves (for relief and contour lines).
	 */
	record Column(int y, int rgb, int waterDepth, int ground) {
		Column(int y, int rgb, int waterDepth) {
			this(y, rgb, waterDepth, y);
		}
	}

	//? if <26.1 {
	static void onChunkLoad(java.util.function.Consumer<Object> callback) {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> callback.accept(chunk));
	}

	static int chunkX(Object chunk) {
		return ((net.minecraft.world.chunk.WorldChunk) chunk).getPos().x;
	}

	static int chunkZ(Object chunk) {
		return ((net.minecraft.world.chunk.WorldChunk) chunk).getPos().z;
	}

	/** The loaded chunk at chunk coords, or null. */
	static Object loadedChunk(int cx, int cz) {
		var world = MinecraftClient.getInstance().world;
		if (world == null || !world.getChunkManager().isChunkLoaded(cx, cz)) {
			return null;
		}
		return world.getChunk(cx, cz);
	}

	static boolean hasCeiling() {
		var world = MinecraftClient.getInstance().world;
		return world != null && world.getDimension().hasCeiling();
	}

	static int bottomY() {
		var world = MinecraftClient.getInstance().world;
		return world == null ? -64 : world.getBottomY();
	}

	/**
	 * Surface column at local (lx, lz): the top block from the heightmap, or - with a ceiling (the
	 * Nether) or for caves - the first floor found going down from {@code startY}.
	 */
	static Column column(Object chunkObject, int lx, int lz, int startY, int minY, boolean fromHeightmap) {
		var chunk = (net.minecraft.world.chunk.WorldChunk) chunkObject;
		var world = MinecraftClient.getInstance().world;
		int baseX = chunk.getPos().getStartX();
		int baseZ = chunk.getPos().getStartZ();
		var pos = new net.minecraft.util.math.BlockPos.Mutable(baseX + lx, 0, baseZ + lz);
		int y;
		if (fromHeightmap) {
			y = chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, lx, lz);
		} else {
			y = startY;
			boolean sawAir = false;
			for (; y > minY; y--) {
				pos.setY(y);
				var state = chunk.getBlockState(pos);
				if (state.isAir()) {
					sawAir = true;
				} else if (sawAir) {
					break;
				}
			}
			if (y <= minY) {
				return new Column(minY, 0, 0);
			}
		}
		pos.setY(y);
		var state = chunk.getBlockState(pos);
		int depth = 0;
		while (!state.getFluidState().isEmpty() && state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER) && depth < 16) {
			depth++;
			pos.setY(y - depth);
			state = chunk.getBlockState(pos);
		}
		int ground = fromHeightmap ? chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, lx, lz) : y;
		if (depth > 0) {
			return new Column(y, net.minecraft.block.MapColor.WATER_BLUE.color, depth, y - depth / 2);
		}
		var color = state.getMapColor(world, pos);
		return new Column(y, color == net.minecraft.block.MapColor.CLEAR ? 0 : color.color, 0, ground);
	}

	static String biome(int x, int y, int z) {
		var world = MinecraftClient.getInstance().world;
		if (world == null) {
			return "";
		}
		return world.getBiome(new net.minecraft.util.math.BlockPos(x, y, z)).getKey().map(k -> k.getValue().toString()).orElse("");
	}

	static double[] player() {
		var player = MinecraftClient.getInstance().player;
		return player == null ? null : new double[] {player.getX(), player.getY(), player.getZ(), player.getYaw()};
	}
	//?} else {
	/*static void onChunkLoad(java.util.function.Consumer<Object> callback) {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> callback.accept(chunk));
	}

	static int chunkX(Object chunk) {
		return ((net.minecraft.world.level.chunk.LevelChunk) chunk).getPos().x();
	}

	static int chunkZ(Object chunk) {
		return ((net.minecraft.world.level.chunk.LevelChunk) chunk).getPos().z();
	}

	static Object loadedChunk(int cx, int cz) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null || !level.getChunkSource().hasChunk(cx, cz)) {
			return null;
		}
		return level.getChunk(cx, cz);
	}

	static boolean hasCeiling() {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		return level != null && level.dimensionType().hasCeiling();
	}

	static int bottomY() {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		return level == null ? -64 : level.getMinY();
	}

	static Column column(Object chunkObject, int lx, int lz, int startY, int minY, boolean fromHeightmap) {
		var chunk = (net.minecraft.world.level.chunk.LevelChunk) chunkObject;
		var level = net.minecraft.client.Minecraft.getInstance().level;
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos(baseX + lx, 0, baseZ + lz);
		int y;
		if (fromHeightmap) {
			y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, lx, lz);
		} else {
			y = startY;
			boolean sawAir = false;
			for (; y > minY; y--) {
				pos.setY(y);
				var state = chunk.getBlockState(pos);
				if (state.isAir()) {
					sawAir = true;
				} else if (sawAir) {
					break;
				}
			}
			if (y <= minY) {
				return new Column(minY, 0, 0);
			}
		}
		pos.setY(y);
		var state = chunk.getBlockState(pos);
		int depth = 0;
		while (!state.getFluidState().isEmpty() && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && depth < 16) {
			depth++;
			pos.setY(y - depth);
			state = chunk.getBlockState(pos);
		}
		int ground = fromHeightmap ? chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) : y;
		if (depth > 0) {
			return new Column(y, net.minecraft.world.level.material.MapColor.WATER.col, depth, y - depth / 2);
		}
		var color = state.getMapColor(level, pos);
		return new Column(y, color == net.minecraft.world.level.material.MapColor.NONE ? 0 : color.col, 0, ground);
	}

	static String biome(int x, int y, int z) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) {
			return "";
		}
		return level.getBiome(new net.minecraft.core.BlockPos(x, y, z)).unwrapKey().map(k -> k.identifier().toString()).orElse("");
	}

	static double[] player() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return player == null ? null : new double[] {player.getX(), player.getY(), player.getZ(), player.getYRot()};
	}
	*///?}
}
