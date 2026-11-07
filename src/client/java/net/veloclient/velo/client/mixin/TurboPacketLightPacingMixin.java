package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo light pacing: every frame the client turns on light for freshly received chunks, at least 10 at once -
 * the second-biggest part of the chunk-load hitches while moving. Now at least one per frame, then more until a quarter
 * of a frame is used (1 ms minimum). With 1000+ waiting (teleport, join) it works through all of them like vanilla.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public abstract class TurboPacketLightPacingMixin {

	@Shadow
	@Final
	private java.util.Deque<Runnable> lightUpdateQueue;

	@Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$paced(CallbackInfo ci) {
		if (!net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				|| !net.veloclient.velo.client.modules.performance.PerformanceBoostModule.packetPacing) {
			return;
		}
		ci.cancel();
		int size = lightUpdateQueue.size();
		if (size == 0) {
			return;
		}
		int limit = size >= 1000 ? size : Math.max(10, size / 10);
		int fps = Math.max(1, net.minecraft.client.Minecraft.getInstance().getFps());
		long budget = Math.max(1_000_000L, 250_000_000L / fps);
		long deadline = System.nanoTime() + budget;
		for (int i = 0; i < limit; i++) {
			Runnable task = lightUpdateQueue.poll();
			if (task == null) {
				return;
			}
			task.run();
			if (size < 1000 && System.nanoTime() > deadline) {
				return;
			}
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboPacketLightPacingMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
//?}
