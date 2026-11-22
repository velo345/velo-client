package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.renderer.GameRenderer;
import net.veloclient.velo.client.modules.rendering.SkyboxModule;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Custom Skybox ({@link net.veloclient.velo.client.modules.rendering.SkyboxModule}): its screen pass runs right after
 * the level, while the depth buffer still holds the world (it's cleared before the hand). Same spot as Velo Shaders'
 * light chain. 26.2+ only (VeloMixinPlugin skips it elsewhere).
 */
//? if >=26.3 {
/*@Mixin(GameRenderer.class)
public abstract class SkyboxFrameMixin {

	@Inject(method = "renderLevel()V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"),
			require = 0)
	private void velo$skybox(CallbackInfo ci) {
		SkyboxModule.afterLevel();
	}
}
*///?} else if >=26.2 {
/*@Mixin(GameRenderer.class)
public abstract class SkyboxFrameMixin {

	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
			require = 0)
	private void velo$skybox(CallbackInfo ci) {
		SkyboxModule.afterLevel();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class SkyboxFrameMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
//?}
