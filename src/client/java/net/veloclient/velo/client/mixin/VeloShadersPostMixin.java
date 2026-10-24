package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import net.veloclient.velo.client.modules.rendering.VeloShaderPasses;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders' screen passes ({@link net.veloclient.velo.client.modules.rendering.VeloShaderPasses}): the light chain
 * right after the world (its depth is cleared before the hand), the grade chain after the hand, before the entity
 * outline and the GUI. 26.3 renders the level from {@code renderLevel()} and blits the outline instead of drawing it.
 */
//? if >=26.3 {
/*@Mixin(GameRenderer.class)
public abstract class VeloShadersPostMixin {

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private CrossFrameResourcePool resourcePool;

	@Shadow
	@Final
	private net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureRenderDispatcher;

	@Shadow
	@Final
	private net.minecraft.client.renderer.state.GameRenderState gameRenderState;

	@Inject(method = "renderLevel()V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
	private void velo$shaderLight(CallbackInfo ci) {
		VeloShaderPasses.light(featureRenderDispatcher, gameRenderState, mainRenderTarget, resourcePool);
	}

	@Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;blitEntityOutline()V"))
	private void velo$shaderPost(CallbackInfo ci) {
		VeloShaderPasses.grade(mainRenderTarget, resourcePool);
	}
}
*///?} else if >=26.2 {
/*@Mixin(GameRenderer.class)
public abstract class VeloShadersPostMixin {

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private CrossFrameResourcePool resourcePool;

	@Shadow
	@Final
	private net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureRenderDispatcher;

	@Shadow
	@Final
	private net.minecraft.client.renderer.state.GameRenderState gameRenderState;

	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void velo$shaderLight(CallbackInfo ci) {
		VeloShaderPasses.light(featureRenderDispatcher, gameRenderState, mainRenderTarget, resourcePool);
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V"))
	private void velo$shaderPost(CallbackInfo ci) {
		VeloShaderPasses.grade(mainRenderTarget, resourcePool);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadersPostMixin {
	// Velo Shaders: Vulkan versions only (VeloMixinPlugin skips it elsewhere).
}
//?}
