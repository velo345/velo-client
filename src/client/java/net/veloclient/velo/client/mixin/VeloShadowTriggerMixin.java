package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Velo Shaders on 26.3: runs the Sodium shadow pass ({@link net.veloclient.velo.client.modules.rendering.VeloShadowPassHook})
 * right before the level's frame graph executes - Sodium has prepared the camera's batches by then, and the terrain
 * pass (which 26.3 opens itself) isn't open yet. 26.2 runs it from Sodium's renderLayer instead.
 */
//? if >=26.3 {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class VeloShadowTriggerMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
			at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"),
			require = 0)
	private void velo$shadowPass(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator, boolean outline,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
			org.joml.Vector4f fogColor, boolean sky, boolean consistentDepth, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (camera == null || camera.pos == null || !net.veloclient.velo.client.modules.rendering.VeloShadersModule.shadowMapActive()) {
			return;
		}
		if (net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable() instanceof net.veloclient.velo.client.modules.rendering.VeloShadowPassHook hook) {
			hook.velo$shadowPass(camera.pos.x, camera.pos.y, camera.pos.z);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadowTriggerMixin {
	// Velo Shaders: 26.3 only - 26.2 draws the shadow map from Sodium's renderLayer (VeloShadowSodiumMixin).
}
//?}
