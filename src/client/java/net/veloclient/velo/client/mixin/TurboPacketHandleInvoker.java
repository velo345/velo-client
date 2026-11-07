package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

/** Lets TurboPacketPacingMixin handle a queued packet (the record type is package-private). 26.2+ only. */
//? if >=26.2 {
/*@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public interface TurboPacketHandleInvoker {

	@Invoker("handle")
	void velo$handle();
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface TurboPacketHandleInvoker {
	// PacketProcessor: 26.2+ (VeloMixinPlugin skips it elsewhere).
}
//?}
