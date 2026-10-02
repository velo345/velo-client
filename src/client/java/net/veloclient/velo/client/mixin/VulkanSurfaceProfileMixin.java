package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.Minecraft;
import net.veloclient.velo.client.devtools.RenderProfiler;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Dev-only: how long the render thread waits on the GPU (swapchain acquire, submit, present) - see RenderProfiler. */
//? if >=26.2 {
/*@Mixin(Minecraft.class)
public abstract class VulkanSurfaceProfileMixin {

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
	private void velo$acquireStart(boolean advanceGameTime, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.waitBegin();
		}
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V", shift = At.Shift.AFTER))
	private void velo$acquireEnd(boolean advanceGameTime, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.waitEnd("acquire");
		}
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"))
	private void velo$submitStart(boolean advanceGameTime, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.waitBegin();
		}
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V", shift = At.Shift.AFTER))
	private void velo$presentEnd(boolean advanceGameTime, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.waitEnd("submit+present");
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanSurfaceProfileMixin {
}
//?}
