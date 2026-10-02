package net.veloclient.velo.client.mixin;

//? if >=26.2 {
/*import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.veloclient.velo.client.modules.performance.VulkanPipelineCache;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
import org.spongepowered.asm.mixin.Mixin;

/** Saves and frees the persistent pipeline cache right before the Vulkan device is destroyed. */
//? if >=26.2 {
/*@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceCloseMixin {

	@Inject(method = "close", at = @At("HEAD"))
	private void velo$savePipelineCache(CallbackInfo ci) {
		VulkanPipelineCache.onDeviceClosing(((VulkanDevice) (Object) this).vkDevice());
	}
}
*///?} else {
@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanDevice")
public abstract class VulkanDeviceCloseMixin {
	// No Vulkan renderer before 26.2 - never applied (VeloMixinPlugin skips it).
}
//?}
