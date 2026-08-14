package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.screen.SplashTextRenderer;
//?} else {
/*import net.minecraft.client.gui.components.SplashRenderer;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's rotating yellow/gold splash-text tagline (this class has
 * no other use in vanilla besides the title screen) - it read as visual
 * noise next to Velo's own logo/wordmark and had nothing to do with this
 * mod.
 */
//? if <26.1 {
@Mixin(SplashTextRenderer.class)
public abstract class TitleSplashMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void velo$cancel(CallbackInfo ci) {
		ci.cancel();
	}
}
//?} else {
/*@Mixin(SplashRenderer.class)
public abstract class TitleSplashMixin {

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void velo$cancel(CallbackInfo ci) {
		ci.cancel();
	}
}
*///?}
