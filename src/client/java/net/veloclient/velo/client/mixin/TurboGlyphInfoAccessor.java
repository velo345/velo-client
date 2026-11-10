package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** Velo Turbo (26.2): a font glyph's atlas UVs and box, for the bulk glyph path ({@link TurboGlyphBulkMixin}). */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.gui.font.glyphs.BakedSheetGlyph.class)
public interface TurboGlyphInfoAccessor {

	@Accessor("u0")
	float velo$u0();

	@Accessor("u1")
	float velo$u1();

	@Accessor("v0")
	float velo$v0();

	@Accessor("v1")
	float velo$v1();

	@Accessor("left")
	float velo$left();

	@Accessor("right")
	float velo$right();

	@Accessor("up")
	float velo$up();

	@Accessor("down")
	float velo$down();
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface TurboGlyphInfoAccessor {
}
//?}
