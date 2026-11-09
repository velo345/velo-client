package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Velo Rewind: which protocol (configuration / play, bound to the server's registries) the next packets are encoded with. */
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindProtocolMixin {
}
//?} else {
/*@Mixin(net.minecraft.network.Connection.class)
public abstract class RewindProtocolMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "setupInboundProtocol", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
	private void velo$rewindProtocol(net.minecraft.network.ProtocolInfo<?> info, net.minecraft.network.PacketListener listener,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.veloclient.velo.devbuild.rewind.RewindRecorder.onInbound(info);
	}
}
*///?}
