package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
*///?}
//? if >=26.3 {
/*import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;
*///?}

/**
 * Turns off 26.2's GPU crash breadcrumbs: whenever the GPU offers VK_AMD_buffer_marker (NVIDIA
 * drivers do too) or VK_NV_device_diagnostic_checkpoints, vanilla writes a marker into the command
 * stream at every render pass and records it on the CPU, every frame, only so that a lost-device
 * crash can say which pass was running. That bookkeeping showed up at ~2% of the render thread.
 * {@code -Dvelo.gpuCheckpoints=true} brings it back when chasing a GPU crash. 26.3 picks the
 * checkpoint flavour from its enabled FeatureSets instead; there the two lookups answer "no", so
 * the no-op checkpoints are used (the extension itself may stay enabled, unused).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(VulkanBackend.class)
public abstract class VulkanCheckpointMixin {

	@Redirect(method = "createDevice", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;hasDeviceExtension(Ljava/lang/String;)Z"), require = 0)
	private boolean velo$noCheckpoints(VulkanPhysicalDevice device, String extension) {
		if (!Boolean.getBoolean("velo.gpuCheckpoints")
				&& ("VK_AMD_buffer_marker".equals(extension) || "VK_NV_device_diagnostic_checkpoints".equals(extension))) {
			return false;
		}
		return device.hasDeviceExtension(extension);
	}
}
*///?} else if >=26.3 {
/*@Mixin(com.mojang.renderpearl.backend.vulkan.VulkanBackend.class)
public abstract class VulkanCheckpointMixin {

	@Redirect(method = "createDevice", at = @At(value = "INVOKE", target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"), require = 0)
	private boolean velo$noCheckpoints(Set<?> enabled, Object featureSet) {
		if (!Boolean.getBoolean("velo.gpuCheckpoints")
				&& (featureSet == VulkanFeatureSets.AMD_BUFFER_MARKER_FEATURESET || featureSet == VulkanFeatureSets.NV_DIAGNOSTIC_CHECKPOINT_FEATURESET)) {
			return false;
		}
		return enabled.contains(featureSet);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanCheckpointMixin {
	// No Vulkan renderer before 26.2 - never applied (VeloMixinPlugin skips it).
}
//?}
