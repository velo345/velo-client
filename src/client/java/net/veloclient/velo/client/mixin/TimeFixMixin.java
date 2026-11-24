package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=26.1 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?} else {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Time, Weather &amp; Fog's time lock survives the server: servers send the time about once a second, which reset the
 * locked time ("stays for a few seconds, then reverts", 1.5.1.1).
 * <ul>
 * <li>1.21.x: right after vanilla applies the time packet, the locked time goes back on (same tick, before a frame).</li>
 * <li>26.1/26.2: the time of day is read from the client's world clocks - those reads return the locked time.</li>
 * <li>26.3: the same through the clock instances.</li>
 * </ul>
 */
//? if >=26.3 {
/*@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance")
public abstract class TimeFixMixin {

	@Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$lockedTicks(CallbackInfoReturnable<Long> cir) {
		if (net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.fixedTimeOfDay() >= 0) {
			cir.setReturnValue(net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.lockClock(cir.getReturnValue()));
		}
	}

	@Inject(method = "partialTick", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$lockedPartial(CallbackInfoReturnable<Float> cir) {
		if (net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.fixedTimeOfDay() >= 0) {
			cir.setReturnValue(0f);
		}
	}
}
*///?} else if >=26.1 {
/*@Mixin(net.minecraft.client.ClientClockManager.class)
public abstract class TimeFixMixin {

	@Inject(method = "getTotalTicks", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$lockedTicks(CallbackInfoReturnable<Long> cir) {
		if (net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.fixedTimeOfDay() >= 0) {
			cir.setReturnValue(net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.lockClock(cir.getReturnValue()));
		}
	}
}
*///?} else {
@Mixin(net.minecraft.client.network.ClientPlayNetworkHandler.class)
public abstract class TimeFixMixin {

	@Inject(method = "onWorldTimeUpdate", at = @At("TAIL"), require = 0)
	private void velo$relock(net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
		net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.afterServerTime();
	}
}
//?}
