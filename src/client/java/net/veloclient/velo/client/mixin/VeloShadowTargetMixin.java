package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import net.veloclient.velo.client.modules.rendering.VeloShadowMap;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
*///?}

/**
 * Velo Shaders' shadow pass, inside Sodium's chunk renderer: its draws go to the shadow map while
 * {@link VeloShadowMap#rendering}, and block-face culling (which drops the sides facing away from
 * the camera - exactly what the sun sees) stays off while the shadow map is in use. The draw
 * batches are cached, and the shadow pass fills them first, so the camera pass shares them.
 */
@Pseudo
//? if >=26.3 {
/*@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer.class, remap = false)
public abstract class VeloShadowTargetMixin implements net.veloclient.velo.client.modules.rendering.VeloShouldDrawAccess {

	@org.spongepowered.asm.mixin.Shadow(remap = false)
	@org.spongepowered.asm.mixin.Final
	private boolean[] shouldDraw;

	@Override
	public boolean[] velo$shouldDraw() {
		return shouldDraw;
	}

	// The shadow pass prepares and draws its own section list, so it must not use (or refill) the batches Sodium
	// caches for the camera. 26.3 fills them in prepare() and draws them in render().
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = {"prepare", "render"}, at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;getCachedBatch(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;"),
			require = 0)
	private net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch velo$shadowBatch(
			net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion region,
			net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass pass,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch> original) {
		if (net.veloclient.velo.client.modules.rendering.VeloShadowMap.rendering) {
			return net.veloclient.velo.client.modules.rendering.VeloShadowLists.INSTANCE.batch(region, pass);
		}
		return original.call(region, pass);
	}

	// Block-face culling drops the sides facing away from the camera - exactly what the sun sees.
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "prepare", at = @org.spongepowered.asm.mixin.injection.At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"), require = 0)
	private boolean velo$allFacesForShadows(boolean original) {
		return original && !net.veloclient.velo.client.modules.rendering.VeloShadowMap.rendering;
	}
}
*///?} else if >=26.2 {
/*@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class VeloShadowTargetMixin {

	// The shadow pass draws its own section list, so it must not use (or refill) the batches Sodium caches for the camera.
	@WrapOperation(method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;ZLcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;getCachedBatch(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;"),
			require = 1)
	private net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch velo$shadowBatch(
			net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion region,
			net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass pass,
			Operation<net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch> original) {
		if (VeloShadowMap.rendering) {
			return net.veloclient.velo.client.modules.rendering.VeloShadowLists.INSTANCE.batch(region, pass);
		}
		return original.call(region, pass);
	}

	@ModifyExpressionValue(method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;ZLcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V", at = @At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"), require = 1)
	private boolean velo$allFacesForShadows(boolean original) {
		return original && !VeloShadersModule.shadowMapActive();
	}

	@WrapOperation(method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;ZLcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"),
			require = 1)
	private RenderPass velo$intoShadowMap(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<?> clearColor,
			GpuTextureView depth, OptionalDouble clearDepth, Operation<RenderPass> original) {
		if (VeloShadowMap.rendering) {
			var target = VeloShadowMap.target();
			return original.call(encoder, (Supplier<String>) () -> "Velo shadow map", target.getColorTextureView(), clearColor,
					target.getDepthTextureView(), clearDepth);
		}
		return original.call(encoder, label, color, clearColor, depth, clearDepth);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadowTargetMixin {
	// Velo Shaders' shadow pass: 26.2 with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
