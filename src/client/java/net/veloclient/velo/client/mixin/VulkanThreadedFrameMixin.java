package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import net.minecraft.client.Minecraft;
import net.veloclient.velo.client.modules.performance.ThreadedPresent;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Threaded present, frame side (see {@link net.veloclient.velo.client.modules.performance.ThreadedPresent}): marks
 * the end-of-frame submit so its queue call may go to the present thread, and skips the swapchain acquire at the
 * start of a frame while the previous frame is still being presented - decoupled present's late acquire before the
 * blit takes the image then, so this frame's CPU work runs while the last one is presented.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(Minecraft.class)
public abstract class VulkanThreadedFrameMixin {

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"))
	private void velo$markFrameSubmit(CommandEncoder encoder, Operation<Void> original) {
		ThreadedPresent.frameSubmit = true;
		try {
			original.call(encoder);
		} finally {
			ThreadedPresent.frameSubmit = false;
		}
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
	private void velo$acquireLateWhilePresenting(GpuSurface surface, Operation<Void> original) {
		if (ThreadedPresent.enabled() && ThreadedPresent.busy()) {
			return;
		}
		original.call(surface);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanThreadedFrameMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
