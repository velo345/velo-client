package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.gui.VeloFonts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Drops the hard 1px drop shadow from text inside Velo menus - with the smooth Inter font that
 * offset copy is what still made labels read as "retro". Every string/Text overload funnels into
 * this OrderedText one.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.DrawContext.class)
public abstract class MenuFlatLabelMixin {

	@ModifyVariable(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private boolean velo$flatMenuText(boolean shadow) {
		return shadow && !VeloFonts.menuScope();
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.GuiGraphicsExtractor.class)
public abstract class MenuFlatLabelMixin {

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private boolean velo$flatMenuText(boolean shadow) {
		return shadow && !VeloFonts.menuScope();
	}
}
*///?}
