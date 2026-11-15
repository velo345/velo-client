package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo - region upkeep (Sodium): every frame Sodium's region manager flips its staging buffer, runs the incremental
 * GPU memory defragmenter (which first measures every arena's fragmentation) and walks every loaded region to free the
 * ones that went empty - 2.8% of the render thread on the static bench (profiled 2026-11-11). Only the staging flip has to
 * happen per frame; freeing an empty region or defragmenting a few milliseconds later changes nothing on screen, so the
 * rest runs at most every 25 ms. Only with Sodium
 * (VeloMixinPlugin), off with -Dvelo.regionUpkeepThrottle=false.
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager", remap = false)
public abstract class TurboSodiumRegionUpdateMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.regionUpkeepThrottle"));
	@Unique
	private static final long INTERVAL_NANOS = 25_000_000L;
	@Unique
	private long velo$lastUpkeep;

	@Shadow
	@Final
	private net.caffeinemc.mods.sodium.client.gpu.arena.staging.StagingBuffer stagingBuffer;

	@Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$throttleUpkeep(CallbackInfo ci) {
		if (!ON || !net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo) {
			return;
		}
		long now = System.nanoTime();
		if (now - velo$lastUpkeep < INTERVAL_NANOS) {
			stagingBuffer.flip();
			ci.cancel();
			return;
		}
		velo$lastUpkeep = now;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumRegionUpdateMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
