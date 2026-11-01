package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Faster launch: FontManager#finalizeProviderLoading (26.x; insertFont on 1.21.11) puts the new provider in front of
 * the font's list - and then gathers every codepoint all of the font's providers support (with a reference to
 * minecraft:default that's Unifont, ~60,000, rebuilt as a new set per call) and, for each one, looks the glyph up
 * provider by provider and throws the answer away (on 1.21.11 it doesn't even look). Nothing reads any of it. Velo's
 * ~110 fonts each reference the default font, so that dead walk was ~38% of the whole resource load at every launch
 * (measured with JFR, 26.2). The provider still goes in; the codepoint sets it walked come back empty.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.gui.font.FontManager.class)
public abstract class FontProviderLoadingMixin {

	@Redirect(method = "finalizeProviderLoading", require = 0, at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/font/GlyphProvider;getSupportedGlyphs()Lit/unimi/dsi/fastutil/ints/IntSet;"))
	private it.unimi.dsi.fastutil.ints.IntSet velo$skipDeadGlyphWalk(com.mojang.blaze3d.font.GlyphProvider provider) {
		return it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET;
	}
}
*///?} else {
@Mixin(net.minecraft.client.font.FontManager.class)
public abstract class FontProviderLoadingMixin {

	@Redirect(method = "insertFont", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/font/Font;getProvidedGlyphs()Lit/unimi/dsi/fastutil/ints/IntSet;"))
	private it.unimi.dsi.fastutil.ints.IntSet velo$skipDeadGlyphWalk(net.minecraft.client.font.Font provider) {
		return it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET;
	}
}
//?}
