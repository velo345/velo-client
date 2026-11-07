package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.veloclient.velo.client.modules.performance.DeferredSky;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo deferred sky, draw side (see {@link net.veloclient.velo.client.modules.performance.DeferredSky}): while
 * the held-back sky runs, each sky draw uses a twin of its pipeline that only passes on pixels nothing was drawn on.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.SkyRenderer.class)
public abstract class TurboSkyPipelineMixin {

	@WrapOperation(method = {"renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon", "renderStars", "renderSunriseAndSunset"},
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void velo$farPlaneTwin(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
		original.call(pass, DeferredSky.twin(pipeline));
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboSkyPipelineMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
