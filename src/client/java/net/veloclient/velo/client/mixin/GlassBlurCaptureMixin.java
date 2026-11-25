package net.veloclient.velo.client.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right before the menu blur runs: keep a sharp copy of the frame, so a Velo menu blurs only behind itself (SharpFrame). */
@Mixin(GameRenderer.class)
public abstract class GlassBlurCaptureMixin {

	//? if <26.1 {
	@Inject(method = "renderBlur", at = @At("HEAD"), require = 0)
	//?} else {
	/*@Inject(method = "processBlurEffect", at = @At("HEAD"), require = 0)
	*///?}
	private void velo$keepSharpFrame(CallbackInfo ci) {
		net.veloclient.velo.client.gui.glass.SharpFrame.capture();
	}
}
