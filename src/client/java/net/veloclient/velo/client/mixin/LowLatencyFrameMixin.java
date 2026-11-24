package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of every frame (after present and vanilla's limiter, before the next input poll) for Low Latency Mode's GPU
 * wait and frame pacing - see {@link net.veloclient.velo.client.modules.performance.LowLatencyModule#frameEnd()}.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.Minecraft.class)
public abstract class LowLatencyFrameMixin {

	@Inject(method = "runTick", at = @At("RETURN"), require = 0)
	private void velo$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.LowLatencyModule.frameEnd();
	}
}
*///?} else {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class LowLatencyFrameMixin {

	@Inject(method = "render", at = @At("RETURN"), require = 0)
	private void velo$frameEnd(boolean tick, CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.LowLatencyModule.frameEnd();
	}
}
//?}
