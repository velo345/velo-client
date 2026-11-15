package net.veloclient.legacy.mixin;

import net.minecraft.client.render.GameRenderer;
import net.veloclient.legacy.feature.ZoomHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom on 1.8.9: divides the field of view while the zoom key is held. */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void velo$zoom(float tickDelta, boolean fovChanged, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(ZoomHook.fov(cir.getReturnValueF()));
	}
}
