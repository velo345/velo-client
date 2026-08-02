package net.veloclient.velo.client.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.veloclient.velo.client.modules.qol.ZoomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the fully-computed render FOV while zooming, entirely separate
 * from vanilla's {@code options.getFov()} - the module previously mutated
 * that option directly, which meant zoom shared state with the player's own
 * saved FOV setting: a scroll-wheel edge case during release could leave it
 * permanently changed, and it was stuck inside vanilla's own validated
 * [30,110] range so zoom could never go past what a normal FOV slider
 * allows. This hooks the value actually used for rendering instead, so it
 * can go arbitrarily narrow and never touches the saved setting at all.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void velo$applyZoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
		if (changingFov && ZoomModule.isOverrideActive()) {
			cir.setReturnValue(ZoomModule.currentOverrideFov());
		}
	}
}
