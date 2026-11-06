package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.FullBrightModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full Bright: the lightning sky flash is never started, so the world keeps its colours (the bolt itself still shows). */
//? if <26.1 {
@Mixin(net.minecraft.client.world.ClientWorld.class)
public abstract class LightningFlashMixin {

	@Inject(method = "setLightningTicksLeft", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noFlash(int ticks, CallbackInfo ci) {
		if (ticks > 0 && FullBrightModule.hideFlash()) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public abstract class LightningFlashMixin {

	@Inject(method = "setSkyFlashTime", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noFlash(int ticks, CallbackInfo ci) {
		if (ticks > 0 && FullBrightModule.hideFlash()) {
			ci.cancel();
		}
	}
}
*///?}
