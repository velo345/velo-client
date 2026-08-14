package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.LogoDrawer;
//?} else {
/*import net.minecraft.client.gui.components.LogoRenderer;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's own "MINECRAFT" title logo everywhere it draws (in
 * practice, only ever the title screen - this class has no other use in
 * vanilla) - {@link net.veloclient.velo.client.gui.title.TitleScreenTheme#drawBranding}
 * draws the Velo logo/wordmark in its place instead, and having both meant
 * they visibly overlapped.
 */
//? if <26.1 {
@Mixin(LogoDrawer.class)
public abstract class TitleLogoMixin {

	@Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;IF)V", at = @At("HEAD"), cancellable = true)
	private void velo$cancel(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;IFI)V", at = @At("HEAD"), cancellable = true)
	private void velo$cancelWithOffset(CallbackInfo ci) {
		ci.cancel();
	}
}
//?} else {
/*@Mixin(LogoRenderer.class)
public abstract class TitleLogoMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V", at = @At("HEAD"), cancellable = true)
	private void velo$cancel(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V", at = @At("HEAD"), cancellable = true)
	private void velo$cancelWithOffset(CallbackInfo ci) {
		ci.cancel();
	}
}
*///?}
