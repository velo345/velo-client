package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.tweaks.ChatHeadsModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chat Heads: the chat backend used while the chat is open draws the head before a line (see ChatHeadsBackendMixin). */
//? if <26.1 {
@Mixin(targets = "net.minecraft.client.gui.hud.ChatHud$Interactable")
public abstract class ChatHeadsFocusedMixin {

	@Shadow
	@Final
	private net.minecraft.client.gui.DrawContext context;

	@Inject(method = "text", at = @At("HEAD"), require = 0)
	private void velo$head(int y, float opacity, net.minecraft.text.OrderedText text, CallbackInfoReturnable<Boolean> cir) {
		ChatHeadsModule.drawHead(context, y, opacity, text);
	}
}
//?} else {
/*@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess")
public abstract class ChatHeadsFocusedMixin {

	@Shadow
	@Final
	private net.minecraft.client.gui.GuiGraphicsExtractor graphics;

	@Inject(method = "handleMessage", at = @At("HEAD"), require = 0)
	private void velo$head(int textTop, float opacity, net.minecraft.util.FormattedCharSequence message, CallbackInfoReturnable<Boolean> cir) {
		ChatHeadsModule.drawHead(graphics, textTop, opacity, message);
	}
}
*///?}
