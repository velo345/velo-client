package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.veloclient.velo.client.modules.performance.DecoupledPresent;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;
*///?}

/**
 * Decoupled present, part 1: the swapchain acquire no longer waits (see {@link
 * net.veloclient.velo.client.modules.performance.DecoupledPresent}). Vanilla passes a 5 s timeout
 * and blocks the render thread until the compositor returns an image; with a 0 timeout the driver
 * answers VK_NOT_READY straight away instead. Vanilla already treats that as "no image" (the index
 * stays -1 and it isn't an error), and {@code VulkanDecoupledSurfaceMixin} then marks the surface
 * as not acquired, so this frame is rendered but not shown. VK_TIMEOUT is reported as NOT_READY,
 * since vanilla crashes on a timeout (it only expects one after 5 s, as a GPU hang).
 *
 * <p>Vanilla steps to the next of its two acquire semaphores on every call. A miss signals nothing,
 * so the step is undone - otherwise a run of misses would cycle back to a semaphore whose wait the
 * GPU may not have executed yet.
 *
 * <p>With decoupled present the swapchain also gets one image more than vanilla's three (within the
 * driver's limit): the compositor keeps one or two on screen, so a fourth means a finished frame
 * finds a free image - and gets shown - more often.
 */
//? if >=26.3 {
/*@Mixin(com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface.class)
*///?} else if >=26.2 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanGpuSurface.class)
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
//?}
public abstract class VulkanDecoupledAcquireMixin {
	//? if >=26.2 {
	/*@Shadow
	@Final
	private long[] acquireSemaphores;
	@Shadow
	private int currentAcquireSemaphore;

	@Redirect(method = "acquireNextTexture", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int velo$acquireWithoutWaiting(VkDevice device, long swapchain, long timeout, long semaphore, long fence, IntBuffer imageIndex) {
		if (!DecoupledPresent.active()) {
			DecoupledPresent.missed = false;
			return KHRSwapchain.vkAcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, imageIndex);
		}
		int result = KHRSwapchain.vkAcquireNextImageKHR(device, swapchain, 0L, semaphore, fence, imageIndex);
		if (result == VK10.VK_NOT_READY || result == VK10.VK_TIMEOUT) {
			DecoupledPresent.missed = true;
			imageIndex.put(0, -1);
			currentAcquireSemaphore = (currentAcquireSemaphore + acquireSemaphores.length - 1) % acquireSemaphores.length;
			return VK10.VK_NOT_READY;
		}
		DecoupledPresent.missed = false;
		return result;
	}

	@WrapOperation(method = "configure", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;minImageCount(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
	private VkSwapchainCreateInfoKHR velo$oneMoreImage(VkSwapchainCreateInfoKHR info, int count, Operation<VkSwapchainCreateInfoKHR> original,
			@Local VkSurfaceCapabilitiesKHR capabilities) {
		if (DecoupledPresent.active()) {
			int max = capabilities.maxImageCount();
			int wanted = Math.max(count, 4);
			count = max == 0 ? wanted : Math.min(wanted, max);
		}
		return original.call(info, count);
	}
	*///?}
}
