package net.veloclient.velo.client.mixin;

import net.minecraft.client.Mouse;
import net.veloclient.velo.client.addon.ScrollHandlers;
import net.veloclient.velo.client.modules.qol.ZoomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes scroll-wheel input away from vanilla's own hotbar-slot-change handling while
 * {@link ZoomModule}'s zoom key is held (adjusting zoom level) or a module registered with
 * {@link ScrollHandlers} is active (e.g. an add-on's speed setting) -
 * otherwise scrolling in any of those would also silently change whatever's in the hotbar
 * underneath.
 */
// Runs before other mods' scroll hooks (Axiom) so Redstone Mode's tool slot gets the wheel first.
@Mixin(value = Mouse.class, priority = 500)
public abstract class MouseScrollMixin {

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void velo$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		net.veloclient.velo.client.modules.hud.MouseButtonsModule.noteScroll(vertical);
		if (ZoomModule.isZoomKeyHeld()) {
			ZoomModule.adjustZoomOnScroll(vertical);
			ci.cancel();
		} else if (ScrollHandlers.dispatch(vertical)) {
			ci.cancel();
		}
	}
}
