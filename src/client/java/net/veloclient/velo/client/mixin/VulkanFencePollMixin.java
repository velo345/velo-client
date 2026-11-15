package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Velo Turbo fence poll (Vulkan 26.2): every GPU fence vanilla checks (staged vertex buffer recycling, readbacks, the
 * submit throttle) ends in {@code awaitSubmitCompletion(index, timeout)}, which only short-cuts when the cached
 * "completed" index is already high enough and otherwise calls vkWaitSemaphores - a driver call even with a zero
 * timeout, made for every pending recycle on every buffer acquire (profiled 2026-11-11: the render thread sat in it
 * ~3% of its native time). The submit semaphore is a timeline whose value is the last finished submit index, so one
 * cheap counter read answers the question for every earlier submit at once and refreshes the cache; only a real
 * wait (timeout > 0) that isn't done yet still goes to vkWaitSemaphores.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanCommandEncoder.class)
public abstract class VulkanFencePollMixin {

	@Shadow
	@Final
	private VulkanDevice device;
	@Shadow
	@Final
	private long submitSemaphore;
	@Shadow
	private long currentSubmitIndex;
	@Shadow
	private long completedSubmitIndex;

	@Inject(method = "awaitSubmitCompletion", at = @At("HEAD"), cancellable = true)
	private void velo$readCounterFirst(long index, long timeout, CallbackInfoReturnable<Boolean> cir) {
		if (completedSubmitIndex >= index || index >= currentSubmitIndex) {
			return; // vanilla's own short-cut / "can't wait on the current submit" handling
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			java.nio.LongBuffer value = stack.mallocLong(1);
			if (VK12.vkGetSemaphoreCounterValue(device.vkDevice(), submitSemaphore, value) != VK10.VK_SUCCESS) {
				return; // let vanilla wait and report the error its own way
			}
			long done = value.get(0);
			if (done > completedSubmitIndex) {
				completedSubmitIndex = done;
			}
			if (done >= index) {
				cir.setReturnValue(true);
			} else if (timeout == 0) {
				cir.setReturnValue(false);
			}
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanFencePollMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
