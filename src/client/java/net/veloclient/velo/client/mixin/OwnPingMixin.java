package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads the server's echo of Velo's ping request (OwnPing) - your real ping; vanilla's own handling runs as normal. */
//? if <26.1 {
@Mixin(net.minecraft.client.network.ClientPlayNetworkHandler.class)
public abstract class OwnPingMixin {

	@Inject(method = "onPingResult", at = @At("HEAD"), require = 0)
	private void velo$ownPing(net.minecraft.network.packet.s2c.query.PingResultS2CPacket packet, CallbackInfo ci) {
		if (net.minecraft.client.MinecraftClient.getInstance().isOnThread()) {
			net.veloclient.velo.client.util.OwnPing.answered(packet.startTime());
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class OwnPingMixin {

	@Inject(method = "handlePongResponse", at = @At("HEAD"), require = 0)
	private void velo$ownPing(net.minecraft.network.protocol.ping.ClientboundPongResponsePacket packet, CallbackInfo ci) {
		if (net.minecraft.client.Minecraft.getInstance().isSameThread()) {
			net.veloclient.velo.client.util.OwnPing.answered(packet.time());
		}
	}
}
*///?}
