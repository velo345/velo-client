package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.util.LastConnectTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers which server every connection attempt goes to. A kick during login (invalid session,
 * whitelist...) happens before a play connection exists, so "the current server" is already gone
 * by the time the disconnect screen opens - Auto Reconnect and the Session Auto-Fixer need this.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.multiplayer.ConnectScreen.class)
public abstract class ConnectTargetMixin {

	@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
			at = @At("HEAD"))
	private static void velo$rememberTarget(net.minecraft.client.gui.screen.Screen parent, net.minecraft.client.MinecraftClient client,
			net.minecraft.client.network.ServerAddress address, net.minecraft.client.network.ServerInfo info, boolean quickPlay,
			net.minecraft.client.network.CookieStorage cookies, CallbackInfo ci) {
		LastConnectTarget.remember(info, parent);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.ConnectScreen.class)
public abstract class ConnectTargetMixin {

	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void velo$rememberTarget(net.minecraft.client.gui.screens.Screen parent, net.minecraft.client.Minecraft client,
			net.minecraft.client.multiplayer.resolver.ServerAddress address, net.minecraft.client.multiplayer.ServerData info, boolean quickPlay,
			net.minecraft.client.multiplayer.TransferState transfer, CallbackInfo ci) {
		LastConnectTarget.remember(info, parent);
	}
}
*///?}
