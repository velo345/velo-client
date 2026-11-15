package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** Field access for {@link TurboBlockModelCacheMixin} (26.2+). */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.block.BlockModelRenderState.class)
public interface TurboBlockModelStateAccessor {

	@Accessor("modelParts")
	java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> velo$modelParts();

	@Accessor("modelParts")
	void velo$setModelParts(java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts);

	@Accessor("transformation")
	org.joml.Matrix4fc velo$transformation();

	@Accessor("transformation")
	void velo$setTransformation(org.joml.Matrix4fc transformation);

	@Accessor("renderType")
	net.minecraft.client.renderer.rendertype.RenderType velo$renderType();

	@Accessor("renderType")
	void velo$setRenderType(net.minecraft.client.renderer.rendertype.RenderType renderType);

	@Accessor("specialRenderer")
	net.minecraft.client.renderer.special.SpecialModelRenderer<?> velo$specialRenderer();

	@Accessor("specialRenderer")
	void velo$setSpecialRenderer(net.minecraft.client.renderer.special.SpecialModelRenderer<?> renderer);

	@Accessor("specialRendererTransformation")
	org.joml.Matrix4fc velo$specialTransformation();

	@Accessor("specialRendererTransformation")
	void velo$setSpecialTransformation(org.joml.Matrix4fc transformation);
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface TurboBlockModelStateAccessor {
	// 26.2+ only.
}
//?}
