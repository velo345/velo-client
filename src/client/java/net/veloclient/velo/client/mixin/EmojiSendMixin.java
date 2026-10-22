package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.EmojiChatModule;
import net.veloclient.velo.client.util.EmojiData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Emoji Chat: {@code :shortcodes:} in a sent chat message become real emoji, so every player sees them. */
//? if <26.1 {
@Mixin(net.minecraft.client.network.ClientPlayNetworkHandler.class)
public abstract class EmojiSendMixin {

	@ModifyVariable(method = "sendChatMessage", at = @At("HEAD"), argsOnly = true, require = 0)
	private String velo$emoji(String message) {
		return EmojiChatModule.convertOnSend() ? EmojiData.replaceShortcodes(message) : message;
	}
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class EmojiSendMixin {

	@ModifyVariable(method = "sendChat", at = @At("HEAD"), argsOnly = true, require = 0)
	private String velo$emoji(String message) {
		return EmojiChatModule.convertOnSend() ? EmojiData.replaceShortcodes(message) : message;
	}
}
*///?}
