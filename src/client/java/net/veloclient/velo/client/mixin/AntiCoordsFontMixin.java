package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.AntiCoordsLeakModule;
import net.veloclient.velo.client.privacy.CoordScrambler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Anti Coords Leak: the two methods every piece of text goes through before it's drawn - GUI, HUD and world text
 * (signs, name tags), from the game and every mod (Litematica, minimaps...) - get their coordinates obfuscated while
 * hiding is on. Off, it's one flag check.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.font.TextRenderer.class)
public abstract class AntiCoordsFontMixin {

	@ModifyVariable(method = "prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
			at = @At("HEAD"), argsOnly = true, require = 0)
	private String velo$hideCoords(String text) {
		return AntiCoordsLeakModule.hiding() ? CoordScrambler.scramble(text) : text;
	}

	@ModifyVariable(method = "prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
			at = @At("HEAD"), argsOnly = true, require = 0)
	private net.minecraft.text.OrderedText velo$hideCoords(net.minecraft.text.OrderedText text) {
		return AntiCoordsLeakModule.hiding() ? CoordScrambler.scramble(text) : text;
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.Font.class)
public abstract class AntiCoordsFontMixin {

	@ModifyVariable(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
			at = @At("HEAD"), argsOnly = true, require = 0)
	private String velo$hideCoords(String text) {
		return AntiCoordsLeakModule.hiding() ? CoordScrambler.scramble(text) : text;
	}

	@ModifyVariable(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
			at = @At("HEAD"), argsOnly = true, require = 0)
	private net.minecraft.util.FormattedCharSequence velo$hideCoords(net.minecraft.util.FormattedCharSequence text) {
		return AntiCoordsLeakModule.hiding() ? CoordScrambler.scramble(text) : text;
	}
}
*///?}
