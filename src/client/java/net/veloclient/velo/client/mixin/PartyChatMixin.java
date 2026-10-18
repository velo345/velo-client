package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.social.PartyChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes chat to the Velo party while party chat is on, and handles {@code /party} (see {@link PartyChat}). */
//? if <26.1 {
@Mixin(net.minecraft.client.network.ClientPlayNetworkHandler.class)
public abstract class PartyChatMixin {

	@Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
	private void velo$partyChat(String message, CallbackInfo ci) {
		if (PartyChat.onOutgoingChat(message)) {
			ci.cancel();
		}
	}

	@Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
	private void velo$partyCommand(String command, CallbackInfo ci) {
		if (PartyChat.onOutgoingCommand(command) || net.veloclient.velo.client.social.VeloCommands.onOutgoingCommand(command)) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class PartyChatMixin {

	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void velo$partyChat(String message, CallbackInfo ci) {
		if (PartyChat.onOutgoingChat(message)) {
			ci.cancel();
		}
	}

	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void velo$partyCommand(String command, CallbackInfo ci) {
		if (PartyChat.onOutgoingCommand(command) || net.veloclient.velo.client.social.VeloCommands.onOutgoingCommand(command)) {
			ci.cancel();
		}
	}
}
*///?}
