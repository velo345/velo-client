package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.renderer.CloudRenderer;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Velo Shaders: vanilla's block clouds step aside while the shader sky draws its own (setting "Vanilla Block Clouds"). */
//? if >=26.3 {
/*@Mixin(CloudRenderer.class)
public abstract class VeloShadersCloudMixin {

	// 26.3 draws clouds into the open main pass, or through the improved-transparency (OIT) path.
	@Inject(method = {"render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			"renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;)V"},
			at = @At("HEAD"), cancellable = true)
	private void velo$shaderClouds(CallbackInfo ci) {
		if (VeloShadersModule.hideVanillaClouds()) {
			ci.cancel();
		}
	}
}
*///?} else if >=26.2 {
/*@Mixin(CloudRenderer.class)
public abstract class VeloShadersCloudMixin {

	@Inject(method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), cancellable = true)
	private void velo$shaderClouds(CallbackInfo ci) {
		if (VeloShadersModule.hideVanillaClouds()) {
			ci.cancel();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadersCloudMixin {
	// Velo Shaders: Vulkan versions only (VeloMixinPlugin skips it elsewhere).
}
//?}
