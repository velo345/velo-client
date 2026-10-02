package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
//?} else {
/*import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
*///?}
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single choke point every received packet passes through on the network thread, before it's
 * handed to its listener. For a backgrounded Background Queue connection this lets {@link
 * BackgroundQueueManager} capture chat/titles, keep scoreboard and keep-alives flowing, and buffer
 * world updates for the switch back - instead of vanilla applying them to a world that isn't
 * there (which used to kill the connection) or to the HUD of whatever you're playing now. Packets
 * of every other connection are untouched.
 */
//? if <26.1 {
@Mixin(ClientConnection.class)
public abstract class GhostPacketGateMixin {

	@Inject(method = "handlePacket", at = @At("HEAD"), cancellable = true)
	private static void velo$gateGhostPackets(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
		if (BackgroundQueueManager.interceptGhostPacket(packet, listener)) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(Connection.class)
public abstract class GhostPacketGateMixin {

	@Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
	private static void velo$gateGhostPackets(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
		if (BackgroundQueueManager.interceptGhostPacket(packet, listener)) {
			ci.cancel();
		}
	}
}
*///?}
