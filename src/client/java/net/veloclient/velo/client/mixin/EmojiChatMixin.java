package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.modules.qol.EmojiChatModule;
import net.veloclient.velo.client.util.EmojiStyler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Emoji Chat: every chat line (player chat, server messages, command feedback) gets its emoji drawn from the colour
 * emoji font. Same entry point as SmallCapsChatMixin; runs BEFORE it (850 < 900): small caps would turn ":fire:" into small-caps letters that no longer match a shortcode.
 */
//? if <26.1 {
@Mixin(value = ChatHud.class, priority = 850)
public abstract class EmojiChatMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), argsOnly = true)
	private Text velo$emoji(Text message) {
		return EmojiChatModule.showInChat() ? EmojiStyler.emojify(message) : message;
	}
}
//?} else {
/*@Mixin(value = ChatComponent.class, priority = 850)
public abstract class EmojiChatMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$emoji(Component message) {
		return EmojiChatModule.showInChat() ? EmojiStyler.emojify(message) : message;
	}
}
*///?}
