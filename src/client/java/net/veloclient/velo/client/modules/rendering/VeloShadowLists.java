package net.veloclient.velo.client.modules.rendering;

//? if >=26.2 {
/*import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.iterator.ReversibleObjectArrayIterator;

import java.util.IdentityHashMap;
import java.util.Map;
*///?}

/**
 * Velo Shaders' shadow pass draws its own set of chunk sections: every section with geometry
 * around the player, whatever the camera looks at. Using the camera's visible list made shadows
 * pop in and out with the view direction (a tree behind you casts onto the ground in front of you).
 * Each region gets its own render list and draw batches, separate from the ones Sodium caches for
 * the camera, so the two passes never step on each other.
 */
//? if >=26.2 {
/*public final class VeloShadowLists implements ChunkRenderListIterable {

	public static final VeloShadowLists INSTANCE = new VeloShadowLists();

	private final Map<RenderRegion, ChunkRenderList> lists = new IdentityHashMap<>();
	private final Map<RenderRegion, Map<TerrainRenderPass, MultiDrawBatch>> batches = new IdentityHashMap<>();
	private final ObjectArrayList<ChunkRenderList> active = new ObjectArrayList<>();
	private int frame;

	private VeloShadowLists() {
	}

	// Collects the sections within radiusBlocks (horizontally) of the camera that have geometry.
	public void rebuild(RenderRegionManager regions, double camX, double camY, double camZ, float radiusBlocks) {
		frame++;
		active.clear();
		int cx = (int) Math.floor(camX) >> 4;
		int cy = (int) Math.floor(camY) >> 4;
		int cz = (int) Math.floor(camZ) >> 4;
		int r = (int) Math.ceil(radiusBlocks / 16f) + 1;
		int ry = 5;
		var loaded = regions.getLoadedRegions();
		java.util.Set<RenderRegion> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (RenderRegion region : loaded) {
			seen.add(region);
			int rx0 = region.getChunkX();
			int ry0 = region.getChunkY();
			int rz0 = region.getChunkZ();
			if (rx0 + RenderRegion.REGION_WIDTH < cx - r || rx0 > cx + r || rz0 + RenderRegion.REGION_LENGTH < cz - r || rz0 > cz + r
					|| ry0 + RenderRegion.REGION_HEIGHT < cy - ry || ry0 > cy + ry) {
				continue;
			}
			ChunkRenderList list = lists.computeIfAbsent(region, ChunkRenderList::new);
			list.reset(frame);
			for (int i = 0; i < RenderRegion.REGION_SIZE; i++) {
				if ((region.getSectionFlags(i) & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0) {
					continue;
				}
				int sx = rx0 + LocalSectionIndex.unpackX(i);
				int sy = ry0 + LocalSectionIndex.unpackY(i);
				int sz = rz0 + LocalSectionIndex.unpackZ(i);
				if (Math.abs(sx - cx) > r || Math.abs(sz - cz) > r || Math.abs(sy - cy) > ry) {
					continue;
				}
				list.add(i);
			}
			if (list.getSectionsWithGeometryCount() > 0) {
				active.add(list);
				var perPass = batches.get(region);
				if (perPass != null) {
					perPass.values().forEach(MultiDrawBatch::clear);
				}
			}
		}
		lists.keySet().retainAll(seen);
		batches.keySet().retainAll(seen);
	}

	// The shadow pass's own draw batch for a region and layer (refilled every frame).
	public MultiDrawBatch batch(RenderRegion region, TerrainRenderPass pass) {
		return batches.computeIfAbsent(region, k -> new IdentityHashMap<>())
				.computeIfAbsent(pass, k -> MultiDrawBatch.newBatch((ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE) + 1));
	}

	@Override
	public ReversibleObjectArrayIterator<ChunkRenderList> iterator(boolean reverse) {
		return new ReversibleObjectArrayIterator<>(active, reverse);
	}
}
*///?} else {
public final class VeloShadowLists {
	// Sodium shadow pass: 26.2 only.
}
//?}
