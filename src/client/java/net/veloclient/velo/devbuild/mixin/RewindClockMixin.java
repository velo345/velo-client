package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind exact export: the game clock moves exactly one video frame per captured frame (RewindExport.clock).
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindClockMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.Minecraft.class)
public abstract class RewindClockMixin {

	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "runTick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"))
	private long velo$rewindExportClock(long realMs) {
		return net.veloclient.velo.devbuild.rewind.RewindExport.clock(realMs);
	}
}
*///?}
