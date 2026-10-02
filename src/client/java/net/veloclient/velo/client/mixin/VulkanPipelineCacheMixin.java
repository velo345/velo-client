package net.veloclient.velo.client.mixin;

//? if >=26.2 {
/*import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.veloclient.velo.client.modules.performance.VulkanPipelineCache;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.LongBuffer;
*///?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Shader Cache, part 2 (Vulkan, 26.2+): vanilla creates every graphics pipeline with no
 * {@code VkPipelineCache} ({@code 0L}), so the driver recompiles them all every launch. This hands
 * it the persistent cache from {@code VulkanPipelineCache} instead.
 */
//? if >=26.2 {
/*@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanPipelineCacheMixin {

	@Redirect(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
			require = 1)
	private static int velo$usePipelineCache(VkDevice device, long cache, VkGraphicsPipelineCreateInfo.Buffer info,
			VkAllocationCallbacks allocator, LongBuffer pipelines) {
		return VulkanPipelineCache.createGraphicsPipelines(device, cache, info, allocator, pipelines);
	}
}
*///?} else {
@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanRenderPipeline")
public abstract class VulkanPipelineCacheMixin {
	// No Vulkan renderer before 26.2 - never applied (VeloMixinPlugin skips it).
}
//?}
