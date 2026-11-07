package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
*///?}

/**
 * Velo Turbo: Sodium walks its whole cloned-section cache every frame just to expire old entries (3% of the render
 * thread at high FPS). Expiry is measured in seconds, so four times a second is plenty; the cache's size limit is
 * enforced separately when entries are added. Only with Sodium on 26.2+ (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager.class, remap = false)
public abstract class TurboSodiumCacheCleanupMixin {

	@Unique
	private long velo$lastCleanup;

	@Redirect(method = "cleanupAndFlip", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSectionCache;cleanup()V"), require = 0)
	private void velo$cleanupRarely(net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache cache) {
		long now = System.nanoTime();
		if (now - velo$lastCleanup >= 250_000_000L || !net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo) {
			velo$lastCleanup = now;
			cache.cleanup();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumCacheCleanupMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
