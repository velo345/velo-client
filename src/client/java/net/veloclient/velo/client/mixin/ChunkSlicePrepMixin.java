package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Skips pointless GPU-buffer lookups while vanilla builds the terrain draw list (26.x).
 *
 * For every visible chunk section and every block layer, LevelRenderer#prepareChunkRenders looks
 * up the section's vertex AND index buffer slices (two hash lookups into maps of thousands of
 * meshes - cache misses) and only afterwards checks whether the section even has geometry in that
 * layer, discarding the slices when it doesn't. Most sections only have one or two of the three
 * layers. Returning null straight away when the layer has no draw gives the identical draw list.
 *
 * It also reads the system clock once per visible section (for the chunk fade-in), which alone was
 * ~4% of the render thread; one reading per frame is used for all sections instead.
 */
//? if >=26.1 {
/*@Mixin(LevelRenderer.class)
public abstract class ChunkSlicePrepMixin {

	@Unique
	private long velo$prepareMillis;

	@Inject(method = "prepareChunkRenders", at = @At("HEAD"), require = 0)
	private void velo$readClockOnce(Matrix4fc modelViewMatrix,
			CallbackInfoReturnable<net.minecraft.client.renderer.chunk.ChunkSectionsToRender> cir) {
		velo$prepareMillis = Util.getMillis();
	}

	@WrapOperation(method = "prepareChunkRenders", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"), require = 0)
	private long velo$frameClock(Operation<Long> original) {
		return PerformanceBoostModule.fastDrawPath ? velo$prepareMillis : original.call();
	}

	@WrapOperation(method = "prepareChunkRenders", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"),
			require = 0)
	private SectionRenderDispatcher.RenderSectionBufferSlice velo$skipEmptyLayer(SectionRenderDispatcher dispatcher,
			SectionMesh mesh, ChunkSectionLayer layer, Operation<SectionRenderDispatcher.RenderSectionBufferSlice> original) {
		if (PerformanceBoostModule.fastDrawPath && mesh.getSectionDraw(layer) == null) {
			return null;
		}
		return original.call(dispatcher, mesh, layer);
	}
}
*///?} else {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class ChunkSlicePrepMixin {
}
//?}
