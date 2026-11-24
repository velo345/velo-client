package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's frame limit, two Velo rules on top:
 * <ul>
 * <li>Hidden window ({@link net.veloclient.velo.client.util.WindowOcclusion}): covered / on another desktop while
 * unfocused - {@code HIDDEN_FPS}, like vanilla does for a minimized window.</li>
 * <li>Low Latency Mode's frame cap: replaces Max Framerate while vanilla isn't throttling
 * ({@link net.veloclient.velo.client.modules.performance.LowLatencyModule#vanillaLimit}).</li>
 * </ul>
 */
//? if >=26.1 {
/*@Mixin(com.mojang.blaze3d.platform.FramerateLimitTracker.class)
public abstract class FrameCapMixin {

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$frameCap(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(net.veloclient.velo.client.util.FrameCaps.apply(cir.getReturnValue()));
	}
}
*///?} else if >=1.21.2 {
@Mixin(net.minecraft.client.option.InactivityFpsLimiter.class)
public abstract class FrameCapMixin {

	@Inject(method = "update", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$frameCap(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(net.veloclient.velo.client.util.FrameCaps.apply(cir.getReturnValue()));
	}
}
//?} else {
/*@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class FrameCapMixin {

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$frameCap(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(net.veloclient.velo.client.util.FrameCaps.apply(cir.getReturnValue()));
	}
}
*///?}
