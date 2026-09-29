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
	// A full resample at max radius is over a thousand columns, each doing a chunk-loaded check
	// plus two heightmap lookups and a block-state/map-color read - done in one synchronous call
	// on the render thread, that's a real, periodic frame-time spike (this is what Lunar-style
	// clients avoid by not doing this kind of work synchronously). Spreading it over several
	// frames instead - a handful of rows per call - keeps the same total cost but removes the
	// single big stall, at the price of the minimap finishing a pass a few frames later than
	// before, which isn't perceptible for a slowly-refreshing overlay like this.
	private static final int ROWS_PER_CALL = 4;

	private static NativeImageBackedTexture texture;
	private static NativeImage pendingImage;
	private static int cachedRadius = -1;
	private static long lastSampleMillis = -1;
	private static int centerX;
	private static int centerZ;
	private static int passCenterX;
	private static int passCenterZ;
	private static int passRadius = -1;
	private static int nextRow;

	private MinimapManager() {
	}

	/** Resamples if due (radius changed or the refresh interval elapsed) and returns the live texture id, or null before the first successful sample. */
	static Identifier textureFor(int radius, int refreshMillis) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return texture == null ? null : TEXTURE_ID;
		}
		long now = System.currentTimeMillis();
		boolean noPassInProgress = passRadius < 0;
		boolean due = noPassInProgress
				&& (cachedRadius != radius || lastSampleMillis < 0 || now - lastSampleMillis >= refreshMillis);
		if (due) {
			startPass(client, radius);
		}
		if (passRadius >= 0) {
			continuePass(client.world);
			if (passRadius < 0) {
				lastSampleMillis = now;
				cachedRadius = radius;
			}
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

	private static void startPass(MinecraftClient client, int radius) {
		BlockPos playerPos = client.player.getBlockPos();
		passCenterX = playerPos.getX();
		passCenterZ = playerPos.getZ();
		int size = radius * 2 + 1;
		if (pendingImage == null || pendingImage.getWidth() != size) {
			if (pendingImage != null) {
				pendingImage.close();
			}
			pendingImage = new NativeImage(size, size, false);
		}
		passRadius = radius;
		nextRow = 0;
	}

	private static void continuePass(ClientWorld world) {
		int size = passRadius * 2 + 1;
		int rowsLeft = size - nextRow;
		int rowsThisCall = Math.min(ROWS_PER_CALL, rowsLeft);
		for (int i = 0; i < rowsThisCall; i++) {
			int dz = nextRow + i - passRadius;
			int worldZ = passCenterZ + dz;
			for (int dx = -passRadius; dx <= passRadius; dx++) {
				int worldX = passCenterX + dx;
				pendingImage.setColorArgb(dx + passRadius, dz + passRadius, sampleColumn(world, worldX, worldZ));
			}
		}
		nextRow += rowsThisCall;
		if (nextRow < size) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (texture == null || texture.getImage().getWidth() != size) {
			if (texture != null) {
				texture.close();
			}
			texture = new NativeImageBackedTexture(() -> "velo-minimap", pendingImage);
			client.getTextureManager().registerTexture(TEXTURE_ID, texture);
			pendingImage = null;
		} else {
			texture.getImage().copyFrom(pendingImage);
		}
		texture.upload();
		centerX = passCenterX;
		centerZ = passCenterZ;
		passRadius = -1;
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
