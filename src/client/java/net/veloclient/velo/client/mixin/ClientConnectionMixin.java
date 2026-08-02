package net.veloclient.velo.client.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.veloclient.velo.client.util.PacketTrafficTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only observation point for the Packet Traffic Monitor module
 * (design spec section 6.3): counts packets by type as they pass through the
 * connection. Never touches the packet, the future, or the boolean flush
 * flag, and never returns early / cancels - this cannot alter what's sent or
 * received, only count it.
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

	@Inject(method = "channelRead0", at = @At("HEAD"))
	private void velo$onPacketReceived(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		PacketTrafficTracker.recordInbound(packet.getClass());
	}

	@Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
	private void velo$onPacketSent(Packet<?> packet, CallbackInfo ci) {
		PacketTrafficTracker.recordOutbound(packet.getClass());
	}
}
