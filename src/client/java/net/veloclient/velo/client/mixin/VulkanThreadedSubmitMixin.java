package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.modules.performance.ThreadedPresent;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Threaded present, submit side: the end-of-frame submit's queue call (vkQueueSubmit2) runs on the Velo present
 * thread (see {@link net.veloclient.velo.client.modules.performance.ThreadedPresent}). Only the submit the frame
 * hook marked is handed off; vanilla's own bookkeeping after it (submit index, waiting for the frame before last,
 * command pool reset) stays on the render thread and is unchanged - waiting on a timeline value whose submit is
 * still queued on the worker is allowed and simply lasts until the GPU signals it.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanCommandEncoder.class)
public abstract class VulkanThreadedSubmitMixin {

	@WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;close()V"))
	private void velo$submitOnPresentThread(com.mojang.blaze3d.vulkan.VulkanQueue.Submission submission, Operation<Void> original) {
		if (ThreadedPresent.frameSubmit && ThreadedPresent.enabled()) {
			ThreadedPresent.frameSubmit = false;
			ThreadedPresent.handOff(() -> original.call(submission));
			return;
		}
		original.call(submission);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanThreadedSubmitMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
