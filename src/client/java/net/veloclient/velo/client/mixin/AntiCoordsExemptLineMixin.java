package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.privacy.CoordScrambler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti Coords Leak: every line the GUI is handed while the scoreboard or the tab list draws ({@link AntiCoordsExemptMixin})
 * is remembered as exempt, so the font later leaves it alone.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.DrawContext.class)
public abstract class AntiCoordsExemptLineMixin {

	@Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), require = 0)
	private void velo$exemptString(net.minecraft.client.font.TextRenderer font, String line, int x, int y, int color, boolean shadow, CallbackInfo ci) {
		if (CoordScrambler.exemptDepth > 0 && line != null) {
			CoordScrambler.exempt(line);
		}
	}

	@Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V", at = @At("HEAD"), require = 0)
	private void velo$exemptOrdered(net.minecraft.client.font.TextRenderer font, net.minecraft.text.OrderedText line, int x, int y, int color, boolean shadow,
			CallbackInfo ci) {
		if (CoordScrambler.exemptDepth > 0 && line != null) {
			CoordScrambler.exempt(CoordScrambler.plain(line));
		}
	}

	@Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V", at = @At("HEAD"), require = 0)
	private void velo$exemptStyled(net.minecraft.client.font.TextRenderer font, net.minecraft.text.Text line, int x, int y, int color, boolean shadow, CallbackInfo ci) {
		if (CoordScrambler.exemptDepth > 0 && line != null) {
			CoordScrambler.exempt(line.getString());
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.GuiGraphicsExtractor.class)
public abstract class AntiCoordsExemptLineMixin {

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), require = 0)
	private void velo$exemptString(net.minecraft.client.gui.Font font, String line, int x, int y, int color, boolean shadow, CallbackInfo ci) {
		if (CoordScrambler.exemptDepth > 0 && line != null) {
			CoordScrambler.exempt(line);
		}
	}

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), require = 0)
	private void velo$exemptOrdered(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence line, int x, int y, int color, boolean shadow,
			CallbackInfo ci) {
		if (CoordScrambler.exemptDepth > 0 && line != null) {
			CoordScrambler.exempt(CoordScrambler.plain(line));
		}
	}

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), require = 0)
	private void velo$exemptStyled(net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component line, int x, int y, int color, boolean shadow,
			CallbackInfo ci) {
		if (CoordScrambler.exemptDepth > 0 && line != null) {
			CoordScrambler.exempt(line.getString());
		}
	}
}
*///?}
