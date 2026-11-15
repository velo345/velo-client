package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo - animated sprite marks (Sodium): every frame Sodium walks all visible sections that hold animated
 * textures (water, lava, fire, portals ...) and marks each of their sprites as "in view", so only visible animations get
 * uploaded on the next game tick. Animations only advance per game tick (20 Hz), so at hundreds or thousands of FPS the
 * same marks are set again dozens of times per tick (profiled 2026-11-11: ~3% of the render thread's own time). The
 * walk now runs at most every 25 ms - still at least twice per 50 ms tick, so every animation that's in view is marked
 * before the tick that uploads it, exactly as before. Only with Sodium (VeloMixinPlugin), off with -Dvelo.spriteMarkThrottle=false.
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class TurboSodiumSpriteMarksMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.spriteMarkThrottle"));
	@Unique
	private static final long INTERVAL_NANOS = 25_000_000L;
	@Unique
	private long velo$lastMarks;

	@Inject(method = "tickVisibleRenders", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$throttleMarks(CallbackInfo ci) {
		if (!ON || !net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo) {
			return;
		}
		long now = System.nanoTime();
		if (now - velo$lastMarks < INTERVAL_NANOS) {
			ci.cancel();
			return;
		}
		velo$lastMarks = now;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumSpriteMarksMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
