package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.veloclient.velo.client.modules.performance.TexelViewCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Texel view cache, buffer side (see {@link net.veloclient.velo.client.modules.performance.TexelViewCache}): a buffer's
 * cached views are destroyed right before the buffer itself, in the destruction queue's own (GPU-safe) slot.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanGpuBuffer.Direct.class)
public abstract class VulkanTexelViewBufferMixin {

	@Shadow
	@Final
	protected VulkanDevice device;

	@Inject(method = "destroy", at = @At("HEAD"))
	private void velo$dropViews(CallbackInfo ci) {
		org.lwjgl.vulkan.VkDevice vk = device.vkDevice();
		TexelViewCache.drop(((com.mojang.blaze3d.vulkan.VulkanGpuBuffer) (Object) this).vkBuffer(),
				view -> org.lwjgl.vulkan.VK10.vkDestroyBufferView(vk, view, null));
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanTexelViewBufferMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
