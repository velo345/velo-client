package net.veloclient.velo.client.mixin;

import net.minecraft.client.Mouse;
import net.veloclient.velo.client.modules.qol.ZoomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes scroll-wheel input to {@link ZoomModule}'s zoom-level adjustment
 * while its zoom key is held, instead of letting vanilla change the selected
 * hotbar slot underneath the player at the same time.
 */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void velo$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (ZoomModule.isZoomKeyHeld()) {
			ZoomModule.adjustZoomOnScroll(vertical);
			ci.cancel();
		}
	}
}
