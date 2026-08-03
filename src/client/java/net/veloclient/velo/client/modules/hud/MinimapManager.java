package net.veloclient.velo.client.modules.hud;

import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * Samples nearby loaded terrain into a small top-down color raster for
 * {@link MinimapModule}, reusing vanilla's own block-to-map-color system
 * (the same lookup item maps use, {@link BlockState#getMapColor}) so terrain
 * reads correctly at a glance without shipping any new art assets. Doing a
 * full resample periodically is simpler and cheap enough at sane radii, so
 * this doesn't bother tracking per-column dirty state from block updates -
 * the next scheduled resample just picks them up.
 */
final class MinimapManager {

	private static final Identifier TEXTURE_ID = Identifier.of("velo-client", "minimap");
	private static final BlockPos.Mutable SCRATCH_POS = new BlockPos.Mutable();

	private static NativeImageBackedTexture texture;
	private static int cachedRadius = -1;
	private static long lastSampleMillis = -1;
	private static int centerX;
	private static int centerZ;

	private MinimapManager() {
	}

	/** Resamples if due (radius changed or the refresh interval elapsed) and returns the live texture id, or null before the first successful sample. */
	static Identifier textureFor(int radius, int refreshMillis) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return texture == null ? null : TEXTURE_ID;
		}
		long now = System.currentTimeMillis();
		boolean due = cachedRadius != radius || lastSampleMillis < 0 || now - lastSampleMillis >= refreshMillis;
		if (due) {
			resample(client, radius);
			lastSampleMillis = now;
			cachedRadius = radius;
		}
		return TEXTURE_ID;
	}

	/** World column the texture is currently centered on - needed to place the player dot/rotation pivot correctly between resamples, since the player keeps moving after the last sample. */
	static int centerX() {
		return centerX;
	}

	static int centerZ() {
		return centerZ;
	}

	private static void resample(MinecraftClient client, int radius) {
		ClientWorld world = client.world;
		BlockPos playerPos = client.player.getBlockPos();
		centerX = playerPos.getX();
		centerZ = playerPos.getZ();
		int size = radius * 2 + 1;

		if (texture == null || texture.getImage().getWidth() != size) {
			if (texture != null) {
				texture.close();
			}
			texture = new NativeImageBackedTexture(() -> "velo-minimap", new NativeImage(size, size, false));
			client.getTextureManager().registerTexture(TEXTURE_ID, texture);
		}

		NativeImage image = texture.getImage();
		for (int dz = -radius; dz <= radius; dz++) {
			int worldZ = centerZ + dz;
			for (int dx = -radius; dx <= radius; dx++) {
				int worldX = centerX + dx;
				image.setColorArgb(dx + radius, dz + radius, sampleColumn(world, worldX, worldZ));
			}
		}
		texture.upload();
	}

	private static int sampleColumn(ClientWorld world, int x, int z) {
		if (!world.isChunkLoaded(x >> 4, z >> 4)) {
			return 0xFF1A1A1A;
		}
		int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
		SCRATCH_POS.set(x, topY, z);
		var state = world.getBlockState(SCRATCH_POS);
		MapColor mapColor = state.getMapColor(world, SCRATCH_POS);
		if (mapColor == MapColor.CLEAR) {
			return 0xFF1A1A1A;
		}
		// Cheap contour shading, same idea as vanilla's own map rendering:
		// darker where the terrain drops going north, brighter where it
		// rises, so slopes/cliffs actually read as shapes instead of flat
		// color blobs.
		int northY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z - 1) - 1;
		MapColor.Brightness brightness = northY < topY ? MapColor.Brightness.HIGH
				: northY > topY ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		return mapColor.getRenderColor(brightness) | 0xFF000000;
	}
}
