package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo packet pacing. The client handles every queued packet once per frame, and the server sends chunks in
 * batches: a batch of chunk packets (parse, light sources, light engine, rebuild scheduling) all landed in one frame
 * - that was where the 1% lows went while moving (22% of slow-frame time vs 1.5% on average).
 * On the render thread, packets are now handled until a per-frame time budget is used up (3/4 of a recent frame,
 * 1 to 8 ms); the rest waits for the next frame, in order. Anything left waiting 50 ms is drained in full, so chunks,
 * teleports and keep-alives are never held back noticeably. The integrated server's own processor is untouched.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.network.PacketProcessor.class)
public abstract class TurboPacketPacingMixin {

	@Shadow
	@Final
	private java.util.Queue<?> packetsToBeHandled;

	@Shadow
	@Final
	private Thread runningThread;

	@Shadow
	private boolean closed;

	@org.spongepowered.asm.mixin.Unique
	private long velo$lastCall;

	@org.spongepowered.asm.mixin.Unique
	private long velo$frameAvg = 4_000_000L;

	@org.spongepowered.asm.mixin.Unique
	private long velo$waitingSince;

	@Inject(method = "processQueuedPackets", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$paced(CallbackInfo ci) {
		if (!net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				|| !net.veloclient.velo.client.modules.performance.PerformanceBoostModule.packetPacing
				|| !"Render thread".equals(runningThread.getName())) {
			return;
		}
		ci.cancel();
		long now = System.nanoTime();
		if (velo$lastCall != 0) {
			long frame = Math.min(100_000_000L, now - velo$lastCall);
			velo$frameAvg += (frame - velo$frameAvg) / 16;
		}
		velo$lastCall = now;
		if (packetsToBeHandled.isEmpty()) {
			velo$waitingSince = 0;
			return;
		}
		boolean drainAll = velo$waitingSince != 0 && now - velo$waitingSince > 50_000_000L;
		long budget = Math.max(1_000_000L, Math.min(8_000_000L, velo$frameAvg * 3 / 4));
		long deadline = now + budget;
		while (!closed && !packetsToBeHandled.isEmpty()) {
			((TurboPacketHandleInvoker) packetsToBeHandled.poll()).velo$handle();
			if (!drainAll && System.nanoTime() > deadline) {
				break;
			}
		}
		if (packetsToBeHandled.isEmpty()) {
			velo$waitingSince = 0;
		} else if (velo$waitingSince == 0 || drainAll) {
			velo$waitingSince = now;
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboPacketPacingMixin {
	// PacketProcessor: 26.2+ (VeloMixinPlugin skips it elsewhere).
}
//?}
