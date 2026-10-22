package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.namestyle.NameStyles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Name Styles in chat: marks the styled names in every new chat line. After Emoji Chat (850), before small caps (900),
 * which would change the letters so the names no longer match.
 */
//? if <26.1 {
@Mixin(value = ChatHud.class, priority = 860)
public abstract class NameStyleChatMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), argsOnly = true)
	private Text velo$nameStyles(Text message) {
		return NameStyles.tagChat(message);
	}
}
//?} else {
/*@Mixin(value = ChatComponent.class, priority = 860)
public abstract class NameStyleChatMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$nameStyles(Component message) {
		return NameStyles.tagChat(message);
	}
}
*///?}
