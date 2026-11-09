package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo: a chunk arriving from the server runs fillFrom on the render thread - for each of its 256 columns a walk
 * down through every block state until something blocks sky light (8.7% of the slow frames while moving, the biggest
 * single piece of chunk packet handling). The columns don't depend on each other, so they're computed on all cores and
 * written in one go afterwards (the packed storage isn't thread-safe). Same result as vanilla, column by column; only
 * the client's render thread does this - the integrated server keeps vanilla.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.world.level.lighting.ChunkSkyLightSources.class)
public abstract class TurboChunkSkySourcesMixin {

	// Opt-in (-Dvelo.parallelSkySources): 2026-11-08 A/B runs showed no gain beyond run-to-run noise yet.
	@org.spongepowered.asm.mixin.Unique
	private static final boolean velo$ON = Boolean.getBoolean("velo.parallelSkySources");

	@Shadow
	@Final
	private int minY;

	@Shadow
	protected abstract void set(int index, int value);

	@Shadow
	private static int index(int x, int z) {
		throw new AssertionError();
	}

	@Shadow
	private static boolean isEdgeOccluded(BlockState topState, BlockState bottomState) {
		throw new AssertionError();
	}

	@Inject(method = "fillFrom", at = @At("HEAD"), cancellable = true)
	private void velo$parallelFill(ChunkAccess chunk, CallbackInfo ci) {
		if (!velo$ON || !PerformanceBoostModule.turbo || !net.minecraft.client.Minecraft.getInstance().isSameThread()) {
			return;
		}
		int top = chunk.getHighestFilledSectionIndex();
		if (top == -1) {
			return;   // vanilla's fast path (one fill)
		}
		int[] heights = new int[256];
		java.util.stream.IntStream.range(0, 16).parallel().forEach(z -> {
			for (int x = 0; x < 16; x++) {
				heights[z << 4 | x] = Math.max(velo$lowestSource(chunk, top, x, z), minY);
			}
		});
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				set(index(x, z), heights[z << 4 | x]);
			}
		}
		ci.cancel();
	}

	// Vanilla's findLowestSourceY without the shared mutable positions (it runs on several threads at once).
	private int velo$lowestSource(ChunkAccess chunk, int topSection, int x, int z) {
		int y = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(topSection) + 1);
		BlockState above = Blocks.AIR.defaultBlockState();
		for (int s = topSection; s >= 0; s--) {
			LevelChunkSection section = chunk.getSection(s);
			if (section.hasOnlyAir()) {
				above = Blocks.AIR.defaultBlockState();
				y = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(s));
				continue;
			}
			for (int ly = 15; ly >= 0; ly--) {
				BlockState below = section.getBlockState(x, ly, z);
				if (isEdgeOccluded(above, below)) {
					return y;
				}
				above = below;
				y--;
			}
		}
		return minY;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboChunkSkySourcesMixin {
	// 26.1+ for now.
}
//?}
