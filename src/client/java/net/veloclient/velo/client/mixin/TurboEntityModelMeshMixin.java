package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.modules.performance.GpuModelMeshes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo GPU model meshes, the recording side: a model GpuModelMeshes takes is posed (setupAnim) but not written by
 * vanilla's part walk this frame. 26.2 (VeloMixinPlugin).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.feature.ModelFeatureRenderer.class)
public abstract class TurboEntityModelMeshMixin {

	@Inject(method = "prepareModel", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$keepOnGpu(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (GpuModelMeshes.record(submit)) {
			ci.cancel();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboEntityModelMeshMixin {
	// 26.2 only (VeloMixinPlugin skips it elsewhere).
}
//?}
