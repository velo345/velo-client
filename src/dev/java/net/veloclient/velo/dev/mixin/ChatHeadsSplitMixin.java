package net.veloclient.velo.dev.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.dev.tweaks.ChatHeadsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Chat Heads: messages with a sender are split a head narrower, and their lines carry the indent + sender. */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.ChatHud.class)
public abstract class ChatHeadsSplitMixin {

	@WrapOperation(method = "addVisibleMessage", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/ChatHudLine;breakLines(Lnet/minecraft/client/font/TextRenderer;I)Ljava/util/List;"), require = 0)
	private List<net.minecraft.text.OrderedText> velo$split(net.minecraft.client.gui.hud.ChatHudLine line, net.minecraft.client.font.TextRenderer font,
			int width, Operation<List<net.minecraft.text.OrderedText>> original) {
		return ChatHeadsModule.split(line.content(), width, w -> original.call(line, font, w));
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.ChatComponent.class)
public abstract class ChatHeadsSplitMixin {

	@WrapOperation(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"), require = 0)
	private List<net.minecraft.util.FormattedCharSequence> velo$split(net.minecraft.client.multiplayer.chat.GuiMessage message,
			net.minecraft.client.gui.Font font, int width, Operation<List<net.minecraft.util.FormattedCharSequence>> original) {
		return ChatHeadsModule.split(message.content(), width, w -> original.call(message, font, w));
	}
}
*///?}
