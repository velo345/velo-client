package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.veloclient.velo.client.modules.performance.FontPrepareMemo;
//?} else if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.veloclient.velo.client.modules.performance.FontPrepareMemo;
*///?}

/**
 * Velo Turbo: the same text object prepared again with the same arguments gets the glyph list built last time (see
 * {@link net.veloclient.velo.client.modules.performance.FontPrepareMemo}). 26.2+ (VeloMixinPlugin).
 */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.client.font.TextRenderer.class)
public abstract class TurboFontPrepareMixin {

	@WrapMethod(method = "prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;", require = 0)
	private net.minecraft.client.font.TextRenderer.GlyphDrawable velo$memo(net.minecraft.text.OrderedText text, float x, float y, int color,
			boolean shadow, boolean includeEmpty, int background, Operation<net.minecraft.client.font.TextRenderer.GlyphDrawable> original) {
		return FontPrepareMemo.get((net.minecraft.client.font.TextRenderer) (Object) this, text, x, y, color, shadow, includeEmpty, background, original);
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Font.class)
public abstract class TurboFontPrepareMixin {

	@WrapMethod(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;", require = 0)
	private net.minecraft.client.gui.Font.PreparedText velo$memo(net.minecraft.util.FormattedCharSequence text, float x, float y, int color,
			boolean shadow, boolean includeEmpty, int background, Operation<net.minecraft.client.gui.Font.PreparedText> original) {
		return FontPrepareMemo.get((net.minecraft.client.gui.Font) (Object) this, text, x, y, color, shadow, includeEmpty, background, original);
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboFontPrepareMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
*///?}
