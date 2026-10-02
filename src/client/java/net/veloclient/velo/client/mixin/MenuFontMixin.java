package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.gui.VeloFonts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every glyph lookup (measuring and drawing) passes through this one private method, so swapping
 * the font id here moves Velo menus onto Inter without touching any call site - see
 * {@link VeloFonts} for the scope and the per-GUI-scale font trick.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.font.TextRenderer.class)
public abstract class MenuFontMixin {

	@ModifyVariable(method = "getGlyphs", at = @At("HEAD"), argsOnly = true)
	private net.minecraft.text.StyleSpriteSource velo$menuFont(net.minecraft.text.StyleSpriteSource source) {
		return VeloFonts.remap(source);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.Font.class)
public abstract class MenuFontMixin {

	@ModifyVariable(method = "getGlyphSource", at = @At("HEAD"), argsOnly = true)
	private net.minecraft.network.chat.FontDescription velo$menuFont(net.minecraft.network.chat.FontDescription source) {
		return VeloFonts.remap(source);
	}
}
*///?}
