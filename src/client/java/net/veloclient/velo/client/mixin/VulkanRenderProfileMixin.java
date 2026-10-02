package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.veloclient.velo.client.devtools.RenderProfiler;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Dev-only terrain draw timing for {@code RenderProfiler} (26.2; applied with the Vulkan mixins). */
//? if >=26.2 {
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
*///?} else {
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkSectionsToRender")
public abstract class VulkanRenderProfileMixin {
}
//?}
