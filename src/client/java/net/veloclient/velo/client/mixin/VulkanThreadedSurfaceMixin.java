package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import net.veloclient.velo.client.modules.performance.ThreadedPresent;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Threaded present, swapchain side (see {@link net.veloclient.velo.client.modules.performance.ThreadedPresent}):
 * vkQueuePresentKHR runs on the present thread right after the frame's submit. Its result (suboptimal / out of
 * date) is applied on the render thread before the swapchain is used again, exactly as vanilla would have applied it
 * right away: acquire, configure and close first wait for the worker; the per-frame "suboptimal?" check takes a
 * finished result without waiting (one frame late at worst, then the swapchain is rebuilt as usual).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanGpuSurface.class)
public abstract class VulkanThreadedSurfaceMixin {

	@Shadow
	@Final
	private VulkanDevice device;
	@Shadow
	private boolean swapchainSuboptimal;
	@Shadow
	private boolean swapchainOutOfDate;
	@Shadow
	private SurfaceException eatenException;

	@Redirect(method = "present", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int velo$presentOnPresentThread(VkQueue queue, VkPresentInfoKHR info) {
		if (!ThreadedPresent.enabled()) {
			ThreadedPresent.drain();
			return KHRSwapchain.vkQueuePresentKHR(queue, info);
		}
		long waitSemaphore = info.pWaitSemaphores().get(0);
		long swapchain = info.pSwapchains().get(0);
		int image = info.pImageIndices().get(0);
		ThreadedPresent.handOff(() -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
				present.pWaitSemaphores(stack.longs(waitSemaphore));
				present.swapchainCount(1);
				present.pSwapchains(stack.longs(swapchain));
				present.pImageIndices(stack.ints(image));
				ThreadedPresent.presentDone(KHRSwapchain.vkQueuePresentKHR(queue, present));
			}
		});
		return 0;
	}

	@Unique
	private void velo$applyPresentResult() {
		int result = ThreadedPresent.takePresentResult();
		if (result == ThreadedPresent.NO_RESULT || result == 0) {
			return;
		}
		if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
			swapchainSuboptimal = true;
			swapchainOutOfDate = true;
			eatenException = new SurfaceException("Failed to present image, swapchain out of date");
		} else if (result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
			swapchainSuboptimal = true;
		} else {
			VulkanUtils.crashIfFailure(device, result, "Failed to present image");
		}
	}

	@Unique
	private void velo$drainAndApply() {
		ThreadedPresent.drain();
		velo$applyPresentResult();
	}

	@Inject(method = "acquireNextTexture", at = @At("HEAD"))
	private void velo$drainBeforeAcquire(CallbackInfo ci) {
		velo$drainAndApply();
	}

	@Inject(method = "configure", at = @At("HEAD"))
	private void velo$drainBeforeConfigure(CallbackInfo ci) {
		velo$drainAndApply();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void velo$drainBeforeClose(CallbackInfo ci) {
		velo$drainAndApply();
	}

	@Inject(method = "isSuboptimal", at = @At("HEAD"))
	private void velo$finishedResult(CallbackInfoReturnable<Boolean> cir) {
		if (!ThreadedPresent.busy()) {
			velo$applyPresentResult();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanThreadedSurfaceMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
