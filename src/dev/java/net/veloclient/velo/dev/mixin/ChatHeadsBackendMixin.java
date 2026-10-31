package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.tweaks.ChatHeadsModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Heads: the in-game chat backend draws the head before a line ({@link ChatHeadsFocusedMixin}: with the chat open).
 * One mixin per backend - their {@code context} fields have different intermediary names on 1.21.11.
 */
//? if <26.1 {
@Mixin(targets = "net.minecraft.client.gui.hud.ChatHud$Hud")
public abstract class ChatHeadsBackendMixin {

	@Shadow
	@Final
	private net.minecraft.client.gui.DrawContext context;

	@Inject(method = "text", at = @At("HEAD"), require = 0)
	private void velo$head(int y, float opacity, net.minecraft.text.OrderedText text, CallbackInfoReturnable<Boolean> cir) {
		ChatHeadsModule.drawHead(context, y, opacity, text);
	}
}
//?} else {
/*@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess")
public abstract class ChatHeadsBackendMixin {

	@Shadow
	@Final
	private net.minecraft.client.gui.GuiGraphicsExtractor graphics;

	@Inject(method = "handleMessage", at = @At("HEAD"), require = 0)
	private void velo$head(int textTop, float opacity, net.minecraft.util.FormattedCharSequence message, CallbackInfoReturnable<Boolean> cir) {
		ChatHeadsModule.drawHead(graphics, textTop, opacity, message);
	}
}
*///?}
