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
	/** Each block is a 2x2 pixel square: room for crisp edges where the terrain steps (the old 1 px per block looked mushy). */
	static final int PX = 2;

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
		int size = (radius * 2 + 1) * PX;
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
		if (net.veloclient.velo.client.privacy.MinimapPrivacy.replacesAll()) {
			rowsThisCall = rowsLeft; // the privacy pass paints every pixel: skip sampling the real terrain
		} else for (int i = 0; i < rowsThisCall; i++) {
			int dz = nextRow + i - passRadius;
			int worldZ = passCenterZ + dz;
			for (int dx = -passRadius; dx <= passRadius; dx++) {
				int worldX = passCenterX + dx;
				sampleBlock(world, worldX, worldZ, (dx + passRadius) * PX, (dz + passRadius) * PX);
			}
		}
		nextRow += rowsThisCall;
		if (nextRow < size) {
			return;
		}
		size *= PX;

		net.veloclient.velo.client.privacy.MinimapPrivacy.apply(pendingImage, passCenterX, passCenterZ, PX);
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

	private static final int EMPTY = 0xFF1A1A1A;

	/**
	 * One block as a PX x PX square: its map color (grass, leaves and water in the real biome tint), shaded by the
	 * slope to the north and west, water darker the deeper it is, and a darker/brighter edge row where the terrain
	 * steps - so ledges and paths read as crisp lines.
	 */
	private static void sampleBlock(ClientWorld world, int x, int z, int px, int py) {
		if (!world.isChunkLoaded(x >> 4, z >> 4)) {
			fill(px, py, EMPTY);
			return;
		}
		int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
		SCRATCH_POS.set(x, topY, z);
		var state = world.getBlockState(SCRATCH_POS);
		MapColor mapColor = state.getMapColor(world, SCRATCH_POS);
		if (mapColor == MapColor.CLEAR) {
			fill(px, py, EMPTY);
			return;
		}
		int northY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z - 1) - 1;
		int westY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x - 1, z) - 1;
		int base = mapColor.getRenderColor(MapColor.Brightness.NORMAL);
		float shade = 1f;
		int id = mapColor.id;
		if (id == 12) { // water: the biome's water, darker with depth
			int floor = world.getTopY(Heightmap.Type.OCEAN_FLOOR, x, z) - 1;
			base = mix(tint(world, SCRATCH_POS, 2), base, 0.35f);
			shade = 1.05f - Math.min(12, Math.max(0, topY - floor)) * 0.035f;
		} else {
			if (id == 1) { // grass
				base = mix(tint(world, SCRATCH_POS, 0), base, 0.25f);
			} else if (id == 7) { // leaves and plants
				base = mix(tint(world, SCRATCH_POS, 1), base, 0.3f);
			}
			shade = 1f + Math.clamp((topY - northY) * 0.07f + (topY - westY) * 0.045f, -0.3f, 0.22f);
		}
		int c = scale(base, shade);
		fill(px, py, c);
		if (id != 12) {
			// the step itself: a dark line on the low side of a ledge, a light one on the high side
			if (northY > topY) {
				row(px, py, scale(c, 0.72f));
			} else if (northY < topY - 1) {
				row(px, py, scale(c, 1.12f));
			}
			if (westY > topY) {
				column(px, py, scale(c, 0.78f));
			}
		}
	}

	/** The biome's grass (0), foliage (1) or water (2) color at {@code pos}. */
	private static int tint(ClientWorld world, BlockPos pos, int kind) {
		//? if <26.1 {
		return switch (kind) {
			case 0 -> net.minecraft.client.color.world.BiomeColors.getGrassColor(world, pos);
			case 1 -> net.minecraft.client.color.world.BiomeColors.getFoliageColor(world, pos);
			default -> net.minecraft.client.color.world.BiomeColors.getWaterColor(world, pos);
		} | 0xFF000000;
		//?} else {
		/*return switch (kind) {
			case 0 -> net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(world, pos);
			case 1 -> net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(world, pos);
			default -> net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(world, pos);
		} | 0xFF000000;
		*///?}
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	private static int scale(int c, float f) {
		int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((c >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((c & 0xFF) * f));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static void fill(int px, int py, int c) {
		for (int j = 0; j < PX; j++) {
			for (int i = 0; i < PX; i++) {
				pendingImage.setColorArgb(px + i, py + j, c);
			}
		}
	}

	private static void row(int px, int py, int c) {
		for (int i = 0; i < PX; i++) {
			pendingImage.setColorArgb(px + i, py, c);
		}
	}

	private static void column(int px, int py, int c) {
		for (int j = 0; j < PX; j++) {
			pendingImage.setColorArgb(px, py + j, c);
		}
	}
}
