package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.modules.performance.GpuHeldMeshes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo GPU item meshes, the drawing side: right after vanilla drew an item group (same phase, same targets), and
 * at the end of the feature pass for anything a group didn't draw. 26.2 (VeloMixinPlugin).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer.class)
public abstract class TurboEntityHeldDrawMixin {

	@Inject(method = "executeGroup", at = @At("TAIL"), require = 0)
	private void velo$drawHeldMeshes(net.minecraft.client.renderer.feature.FeatureFrameContext context, int index, java.util.List<?> submits,
			boolean translucent, CallbackInfo ci) {
		if ((Object) this instanceof net.minecraft.client.renderer.feature.ItemFeatureRenderer) {
			GpuHeldMeshes.flush(translucent);
		} else if ((Object) this instanceof net.minecraft.client.renderer.feature.ModelFeatureRenderer) {
			net.veloclient.velo.client.modules.performance.GpuModelMeshes.flush(translucent);
		}
	}

	@Inject(method = "finishExecute", at = @At("TAIL"), require = 0)
	private void velo$drawLeftoverHeldMeshes(net.minecraft.client.renderer.feature.FeatureFrameContext context, CallbackInfo ci) {
		if ((Object) this instanceof net.minecraft.client.renderer.feature.ItemFeatureRenderer) {
			GpuHeldMeshes.flushAll();
		} else if ((Object) this instanceof net.minecraft.client.renderer.feature.ModelFeatureRenderer) {
			net.veloclient.velo.client.modules.performance.GpuModelMeshes.flushAll();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboEntityHeldDrawMixin {
	// 26.2 only (VeloMixinPlugin skips it elsewhere).
}
//?}
