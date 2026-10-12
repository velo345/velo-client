package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.veloclient.velo.client.devtools.RenderProfiler;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Dev-only terrain draw timing for {@code RenderProfiler} (26.2; applied with the Vulkan mixins). */
//? if >=26.2 && <26.3 {
/*@Mixin(ChunkSectionsToRender.class)
public abstract class VulkanRenderProfileMixin {

	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void velo$start(ChunkSectionLayerGroup group, com.mojang.blaze3d.textures.GpuSampler sampler, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.begin();
		}
	}

	@Inject(method = "renderGroup", at = @At("RETURN"))
	private void velo$end(ChunkSectionLayerGroup group, com.mojang.blaze3d.textures.GpuSampler sampler, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			int draws = 0;
			for (var layer : group.layers()) {
				for (var list : ((ChunkSectionsToRender) (Object) this).drawGroupsPerLayer().get(layer).values()) {
					draws += list.size();
				}
			}
			RenderProfiler.endTerrain(draws);
		}
	}
}
*///?} else if >=26.3 {
/*@Mixin(net.minecraft.client.renderer.chunk.ChunkSectionsToRender.class)
public abstract class VulkanRenderProfileMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderGroup", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
	private void velo$start(net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup group, com.mojang.renderpearl.api.commands.RenderPass pass,
			com.mojang.renderpearl.api.textures.GpuSampler sampler, com.mojang.renderpearl.api.textures.GpuTextureView view, boolean flag,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			net.veloclient.velo.client.devtools.RenderProfiler.begin();
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderGroup", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
	private void velo$end(net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup group, com.mojang.renderpearl.api.commands.RenderPass pass,
			com.mojang.renderpearl.api.textures.GpuSampler sampler, com.mojang.renderpearl.api.textures.GpuTextureView view, boolean flag,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			// Draws are counted at the Vulkan render pass (VulkanDrawStateMixin) on 26.3.
			net.veloclient.velo.client.devtools.RenderProfiler.endTerrain(net.veloclient.velo.client.devtools.RenderProfiler.drawCalls);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkSectionsToRender")
public abstract class VulkanRenderProfileMixin {
}
//?}
