package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** Vanilla's private GLOBALS_SNIPPET, the base of every world pipeline - for GpuCosmetics' own entity pipeline. */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.RenderPipelines.class)
public interface GpuCosmeticsPipelinesAccessor {

	@Accessor("GLOBALS_SNIPPET")
	static com.mojang.blaze3d.pipeline.RenderPipeline.Snippet velo$globals() {
		throw new AssertionError();
	}
}
*///?} else if >=26.3 {
/*@Mixin(net.minecraft.client.renderer.RenderPipelines.class)
public interface GpuCosmeticsPipelinesAccessor {

	@Accessor("ENTITY_SNIPPET")
	static com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet velo$entity() {
		throw new AssertionError();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public interface GpuCosmeticsPipelinesAccessor {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
