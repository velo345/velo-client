package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.modules.performance.ThreadedPresent;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Threaded present, queue side: the graphics queue must only be used by one thread at a time, so waiting for the queue
 * to go idle first lets the present thread finish what it was handed (see
 * {@link net.veloclient.velo.client.modules.performance.ThreadedPresent}).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanQueue.class)
public abstract class VulkanThreadedQueueMixin {

	@Inject(method = "waitIdle", at = @At("HEAD"))
	private void velo$drainFirst(CallbackInfo ci) {
		ThreadedPresent.drain();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanThreadedQueueMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
