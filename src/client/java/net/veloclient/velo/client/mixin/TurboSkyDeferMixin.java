package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.veloclient.velo.client.modules.performance.DeferredSky;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo deferred sky, frame side (see {@link net.veloclient.velo.client.modules.performance.DeferredSky}): the
 * overworld sky pass keeps its place in the frame graph but does nothing; its work runs inside the main pass right
 * after the solid entities, before the translucent features and translucent terrain.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class TurboSkyDeferMixin {

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Unique
	private Runnable velo$heldSky;

	@WrapOperation(method = "addSkyPass", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V"))
	private void velo$holdSky(FramePass pass, Runnable sky, Operation<Void> original) {
		velo$heldSky = null;
		if (DeferredSky.enabled() && levelRenderState.skyRenderState.skybox == net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD) {
			velo$heldSky = sky;
			original.call(pass, (Runnable) () -> { });
			return;
		}
		original.call(pass, sky);
	}

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V", shift = At.Shift.AFTER))
	private void velo$skyAfterOpaque(CallbackInfo ci) {
		Runnable sky = velo$heldSky;
		if (sky != null) {
			velo$heldSky = null;
			DeferredSky.run(levelRenderState.cameraRenderState.projectionMatrix, sky);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboSkyDeferMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
