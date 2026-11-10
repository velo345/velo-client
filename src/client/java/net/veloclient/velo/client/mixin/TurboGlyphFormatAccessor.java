package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** Velo Turbo (26.2): the vertex format a BufferBuilder writes, for the bulk glyph path ({@link TurboGlyphBulkMixin}). */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vertex.BufferBuilder.class)
public interface TurboGlyphFormatAccessor {

	@Accessor("format")
	com.mojang.blaze3d.vertex.VertexFormat velo$format();
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface TurboGlyphFormatAccessor {
}
//?}
