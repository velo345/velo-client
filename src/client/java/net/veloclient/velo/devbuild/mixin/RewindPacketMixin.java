package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Velo Rewind: every packet the game thread is about to handle (queued by the network thread through PacketProcessor), in
 * the exact order it is applied - so a recording and the snapshot taken on the game thread line up.
 */
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindPacketMixin {
}
//?} else {
/*@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class RewindPacketMixin {

	@org.spongepowered.asm.mixin.Shadow
	@org.spongepowered.asm.mixin.Final
	private net.minecraft.network.PacketListener listener;

	@org.spongepowered.asm.mixin.Shadow
	@org.spongepowered.asm.mixin.Final
	private net.minecraft.network.protocol.Packet<?> packet;

	@org.spongepowered.asm.mixin.injection.Inject(method = "handle", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
	private void velo$rewindPacket(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (listener.shouldHandleMessage(packet)) {
			net.veloclient.velo.devbuild.rewind.RewindRecorder.onHandle(listener, packet);
		}
	}
}
*///?}
