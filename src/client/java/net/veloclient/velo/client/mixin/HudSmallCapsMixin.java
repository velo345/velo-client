package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.modules.qol.SmallCapsModule;
import net.veloclient.velo.client.util.SmallCapsConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Renders small caps across Velo's own HUD text (FPS counter, coordinates,
 * clock, scoreboard, waypoints, minimap labels, ...) when {@link
 * SmallCapsModule}'s HUD toggle is on. Targets the shared text-drawing
 * entry points every HUD module already calls ({@code DrawContext#drawText}/
 * {@code #drawTextWithShadow} pre-26.1, consolidated into a single
 * overloaded {@code GuiGraphicsExtractor#text(...)} on 26.1+, verified via
 * javap against the real 26.1 AND 26.2 client jars) rather than editing
 * every HUD module individually - works retroactively for all of them,
 * including any added later, with zero per-module changes.
 *
 * <p>Gated by {@link SmallCapsModule#isHudRenderActive()}, a flag {@code
 * HudManager} sets only for the exact duration of each Velo HUD module's own
 * {@code render(...)} call - {@code drawText}/{@code text} is called
 * constantly by vanilla itself (menus, tooltips, chat, the F3 screen, every
 * other mod's own HUD/GUI) and this must never touch any of that, only
 * Velo's own HUD elements while they're actually being drawn. The
 * {@code String} and {@code Text}/{@code Component} overloads are covered
 * (what every current HUD module actually calls); the rarer pre-resolved
 * {@code OrderedText}/{@code FormattedCharSequence} overload is left alone -
 * transforming an already-glyph-resolved sequence isn't a simple character
 * swap the way a plain string is.
 */
//? if <26.1 {
@Mixin(DrawContext.class)
public abstract class HudSmallCapsMixin {

	@ModifyVariable(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V",
			at = @At("HEAD"), argsOnly = true)
	private String velo$smallCapsShadowString(String text) {
		return SmallCapsModule.isHudRenderActive() ? SmallCapsConverter.toSmallCaps(text) : text;
	}

	@ModifyVariable(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private String velo$smallCapsString(String text) {
		return SmallCapsModule.isHudRenderActive() ? SmallCapsConverter.toSmallCaps(text) : text;
	}

	@ModifyVariable(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
			at = @At("HEAD"), argsOnly = true)
	private Text velo$smallCapsShadowText(Text text) {
		if (!SmallCapsModule.isHudRenderActive()) {
			return text;
		}
		return Text.literal(SmallCapsConverter.toSmallCaps(text.getString())).setStyle(text.getStyle());
	}

	@ModifyVariable(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private Text velo$smallCapsText(Text text) {
		if (!SmallCapsModule.isHudRenderActive()) {
			return text;
		}
		return Text.literal(SmallCapsConverter.toSmallCaps(text.getString())).setStyle(text.getStyle());
	}
}
//?} else {
/*@Mixin(GuiGraphicsExtractor.class)
public abstract class HudSmallCapsMixin {

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
			at = @At("HEAD"), argsOnly = true)
	private String velo$smallCapsShadowString(String text) {
		return SmallCapsModule.isHudRenderActive() ? SmallCapsConverter.toSmallCaps(text) : text;
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private String velo$smallCapsString(String text) {
		return SmallCapsModule.isHudRenderActive() ? SmallCapsConverter.toSmallCaps(text) : text;
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$smallCapsShadowText(Component text) {
		if (!SmallCapsModule.isHudRenderActive()) {
			return text;
		}
		return Component.literal(SmallCapsConverter.toSmallCaps(text.getString())).setStyle(text.getStyle());
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$smallCapsText(Component text) {
		if (!SmallCapsModule.isHudRenderActive()) {
			return text;
		}
		return Component.literal(SmallCapsConverter.toSmallCaps(text.getString())).setStyle(text.getStyle());
	}
}
*///?}
