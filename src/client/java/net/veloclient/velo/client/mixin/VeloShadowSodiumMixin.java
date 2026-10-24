package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import net.veloclient.velo.client.modules.rendering.VeloShadowLists;
import net.veloclient.velo.client.modules.rendering.VeloShadowMap;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders' shadow pass: right before Sodium draws the frame's solid terrain, the solid and
 * cutout layers are drawn once more from the sun into {@link VeloShadowMap} - same meshes, same
 * draw batches, same pipeline, only the matrices (Sodium's globals) and the target change
 * ({@code VeloShadowTargetMixin}). Sodium writes its globals once per frame, so its "already
 * updated" flag is reset around the extra write.
 */
@Pseudo
//? if >=26.3 {
/*@Mixin(value = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.class, remap = false)
public abstract class VeloShadowSodiumMixin implements net.veloclient.velo.client.modules.rendering.VeloShadowPassHook {

	@org.spongepowered.asm.mixin.Shadow(remap = false)
	private net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager renderSectionManager;
	@org.spongepowered.asm.mixin.Shadow(remap = false)
	private net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager uniformBufferManager;
	@org.spongepowered.asm.mixin.Shadow(remap = false)
	private boolean useTranslucencySorting;
	@org.spongepowered.asm.mixin.Shadow(remap = false)
	private net.caffeinemc.mods.sodium.client.util.FogParameters lastFogParameters;

	@org.spongepowered.asm.mixin.Unique
	private static boolean velo$failed;

	// 26.3: called before the frame graph runs (Sodium has prepared the camera's batches; the terrain pass isn't open yet).
	@Override
	public void velo$shadowPass(double x, double y, double z) {
		if (net.veloclient.velo.client.modules.rendering.VeloShadowMap.rendering || velo$failed
				|| !net.veloclient.velo.client.modules.rendering.VeloShadersModule.shadowMapActive()
				|| !net.veloclient.velo.client.modules.rendering.VeloShadowMap.valid
				|| !net.veloclient.velo.client.modules.rendering.VeloShadowMap.renderThisFrame
				|| renderSectionManager == null || uniformBufferManager == null || lastFogParameters == null) {
			return;
		}
		var renderer = renderSectionManager.getChunkRenderer();
		boolean[] cameraFlags = renderer instanceof net.veloclient.velo.client.modules.rendering.VeloShouldDrawAccess access
				? access.velo$shouldDraw().clone() : null;
		net.veloclient.velo.client.modules.rendering.VeloShadowMap.rendering = true;
		try {
			var target = net.veloclient.velo.client.modules.rendering.VeloShadowMap.target();
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			device.createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new org.joml.Vector4f(0f, 0f, 0f, 0f),
					target.getDepthTexture(), 0.0); // reversed Z: 0 = far
			var shadow = new net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices(
					net.veloclient.velo.client.modules.rendering.VeloShadowMap.PROJECTION, net.veloclient.velo.client.modules.rendering.VeloShadowMap.VIEW);
			((VeloSodiumUniformsAccessor) uniformBufferManager).velo$setUpdatedThisFrame(false);
			uniformBufferManager.update(shadow, lastFogParameters);
			var camera = new net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform(x, y, z);
			// Its own sections: everything around the player, not just what the camera sees.
			net.veloclient.velo.client.modules.rendering.VeloShadowLists.INSTANCE.rebuild(renderSectionManager.regions, x, y, z,
					net.veloclient.velo.client.modules.rendering.VeloShadowMap.RADIUS + 8f);
			renderer.prepare(net.veloclient.velo.client.modules.rendering.VeloShadowLists.INSTANCE, camera, useTranslucencySorting);
			var sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
					.getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST);
			var pass = device.createCommandEncoder().createRenderPass(() -> "Velo shadow map", target.getColorTextureView(),
					java.util.Optional.empty(), target.getDepthTextureView(), java.util.OptionalDouble.empty());
			try {
				for (var layer : new net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass[] {
						net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.SOLID,
						net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.CUTOUT}) {
					renderer.render(shadow, net.veloclient.velo.client.modules.rendering.VeloShadowLists.INSTANCE, layer, camera, lastFogParameters,
							useTranslucencySorting, pass, sampler, uniformBufferManager.getUniformBuffer(), uniformBufferManager.getSectionTimeInfo(), null);
				}
			} finally {
				pass.close();
			}
		} catch (Throwable t) {
			velo$failed = true;
			net.veloclient.velo.VeloClient.LOGGER.error("Velo Shaders: shadow pass failed - shadow map off for this session", t);
		} finally {
			net.veloclient.velo.client.modules.rendering.VeloShadowMap.rendering = false;
			((VeloSodiumUniformsAccessor) uniformBufferManager).velo$setUpdatedThisFrame(false);
			if (cameraFlags != null) {
				System.arraycopy(cameraFlags, 0, ((net.veloclient.velo.client.modules.rendering.VeloShouldDrawAccess) renderer).velo$shouldDraw(), 0,
						cameraFlags.length);
			}
		}
	}
}
*///?} else if >=26.2 {
/*@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class VeloShadowSodiumMixin {

	@Shadow
	private RenderSectionManager renderSectionManager;
	@Shadow
	private UniformBufferManager uniformBufferManager;
	@Shadow
	private boolean useTranslucencySorting;

	@Unique
	private static boolean velo$failed;

	@Inject(method = "renderLayer", at = @At("HEAD"), require = 0)
	private void velo$shadowPass(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z,
			FogParameters fog, GpuSampler sampler, CallbackInfo ci) {
		if (pass != DefaultTerrainRenderPasses.SOLID || VeloShadowMap.rendering || velo$failed
				|| !VeloShadersModule.shadowMapActive() || !VeloShadowMap.valid || !VeloShadowMap.renderThisFrame || renderSectionManager == null) {
			return;
		}
		VeloShadowMap.rendering = true;
		try {
			var target = VeloShadowMap.target();
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(),
					new Vector4f(0f, 0f, 0f, 0f), target.getDepthTexture(), 0.0); // reversed Z: 0 = far
			var shadow = new ChunkRenderMatrices(VeloShadowMap.PROJECTION, VeloShadowMap.VIEW);
			var uniforms = (VeloSodiumUniformsAccessor) uniformBufferManager;
			uniforms.velo$setUpdatedThisFrame(false);
			uniformBufferManager.update(shadow, fog);
			var renderer = renderSectionManager.getChunkRenderer();
			var camera = new CameraTransform(x, y, z);
			// Its own sections: everything around the player, not just what the camera sees.
			VeloShadowLists.INSTANCE.rebuild(renderSectionManager.regions, x, y, z, VeloShadowMap.RADIUS + 8f);
			for (TerrainRenderPass layer : new TerrainRenderPass[] {DefaultTerrainRenderPasses.SOLID, DefaultTerrainRenderPasses.CUTOUT}) {
				renderer.render(shadow, VeloShadowLists.INSTANCE, layer, camera, fog, useTranslucencySorting, sampler,
						uniformBufferManager.getUniformBuffer(), uniformBufferManager.getSectionTimeInfo());
			}
		} catch (Throwable t) {
			velo$failed = true;
			VeloClient.LOGGER.error("Velo Shaders: shadow pass failed - shadow map off for this session", t);
		} finally {
			VeloShadowMap.rendering = false;
			((VeloSodiumUniformsAccessor) uniformBufferManager).velo$setUpdatedThisFrame(false);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadowSodiumMixin {
	// Velo Shaders' shadow pass: 26.2 with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
