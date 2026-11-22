package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.modules.performance.GpuHeldMeshes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo GPU item meshes, the recording side: a big item model GpuHeldMeshes takes is not written by vanilla's quad
 * writer this frame (its glint and outline still are - those items never qualify). 26.2 (VeloMixinPlugin).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.feature.ItemFeatureRenderer.class)
public abstract class TurboEntityHeldMeshMixin {

	@Inject(method = "prepareMainSubmit", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$keepOnGpu(net.minecraft.client.renderer.feature.ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		if (GpuHeldMeshes.record(submit)) {
			ci.cancel();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboEntityHeldMeshMixin {
	// 26.2 only (VeloMixinPlugin skips it elsewhere).
}
//?}
