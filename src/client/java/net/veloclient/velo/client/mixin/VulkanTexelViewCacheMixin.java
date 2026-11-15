package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.nio.LongBuffer;
import net.veloclient.velo.client.modules.performance.TexelViewCache;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Texel view cache, descriptor side (see {@link net.veloclient.velo.client.modules.performance.TexelViewCache}): the
 * view create in {@code pushDescriptors} is answered from the cache, and the destroy vanilla queues right after it is
 * skipped for a cached view (the cache destroys it with its buffer).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanRenderPass.class)
public abstract class VulkanTexelViewCacheMixin {

	@WrapOperation(method = "pushDescriptors", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateBufferView(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkBufferViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int velo$cachedView(VkDevice device, VkBufferViewCreateInfo info, VkAllocationCallbacks allocator, LongBuffer view,
			Operation<Integer> original) {
		TexelViewCache.lastCached = false;
		if (!TexelViewCache.ON) {
			return original.call(device, info, allocator, view);
		}
		long buffer = info.buffer(), offset = info.offset(), range = info.range();
		int format = info.format();
		long cached = TexelViewCache.find(buffer, format, offset, range);
		if (cached != 0L) {
			view.put(0, cached);
			TexelViewCache.lastCached = true;
			return VK10.VK_SUCCESS;
		}
		int result = original.call(device, info, allocator, view);
		if (result == VK10.VK_SUCCESS) {
			TexelViewCache.put(buffer, format, offset, range, view.get(0));
			TexelViewCache.lastCached = true;
		}
		return result;
	}

	@WrapOperation(method = "pushDescriptors", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;queueForDestroy(Lcom/mojang/blaze3d/vulkan/Destroyable;)V"))
	private void velo$keepCachedView(com.mojang.blaze3d.vulkan.VulkanCommandEncoder encoder, com.mojang.blaze3d.vulkan.Destroyable destroyable,
			Operation<Void> original) {
		if (TexelViewCache.lastCached) {
			TexelViewCache.lastCached = false;
			return;
		}
		original.call(encoder, destroyable);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanTexelViewCacheMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
