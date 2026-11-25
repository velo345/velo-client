package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.option.GameOptions;
//?} else {
/*import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.PauseScreen;
*///?}
import net.veloclient.velo.client.gui.title.ScreenLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Velo+ Customize: the pause menu's own blur strength - only while the pause menu is open, never written to the
 * player's options (their menu blur everywhere else stays what they set).
 */
//? if <26.1 {
@Mixin(GameOptions.class)
public abstract class PauseBlurMixin {

	@Inject(method = "getMenuBackgroundBlurrinessValue()I", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$pauseBlur(CallbackInfoReturnable<Integer> cir) {
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() == null && (net.veloclient.velo.client.hud.HudLook.glassBlur()
				|| net.veloclient.velo.client.redstone.RedstoneMode.active() && net.veloclient.velo.client.gui.widget.VeloStyle.glass())) {
			cir.setReturnValue(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(9));   // HUD Style Glass: blur behind the HUD panels
			return;
		}
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.drawer.VeloDrawerScreen drawer) {
			cir.setReturnValue(drawer.blurRadius());   // the full R-Shift menu: strong glass blur, faded in and out
			return;
		}
		if (net.veloclient.velo.client.gui.widget.VeloStyle.glass()
				&& net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.window.VeloWindow) {
			cir.setReturnValue(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(12));   // glass windows: strong - only behind the window (SharpFrame keeps the rest sharp)
			return;
		}
		if (net.veloclient.velo.client.gui.widget.VeloStyle.glass() && net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof GameMenuScreen) {
			cir.setReturnValue(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(12));   // glass: strong, only behind the panels
			return;
		}
		int blur = ScreenLook.pause().blur;
		if (blur >= 0 && net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof GameMenuScreen) {
			cir.setReturnValue(blur);
		}
	}
}
//?} else {
/*@Mixin(Options.class)
public abstract class PauseBlurMixin {

	@Inject(method = "getMenuBackgroundBlurriness()I", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$pauseBlur(CallbackInfoReturnable<Integer> cir) {
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() == null && (net.veloclient.velo.client.hud.HudLook.glassBlur()
				|| net.veloclient.velo.client.redstone.RedstoneMode.active() && net.veloclient.velo.client.gui.widget.VeloStyle.glass())) {
			cir.setReturnValue(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(9));   // HUD Style Glass: blur behind the HUD panels
			return;
		}
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.drawer.VeloDrawerScreen drawer) {
			cir.setReturnValue(drawer.blurRadius());   // the full R-Shift menu: strong glass blur, faded in and out
			return;
		}
		if (net.veloclient.velo.client.gui.widget.VeloStyle.glass()
				&& net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.window.VeloWindow) {
			cir.setReturnValue(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(12));   // glass windows: strong - only behind the window (SharpFrame keeps the rest sharp)
			return;
		}
		if (net.veloclient.velo.client.gui.widget.VeloStyle.glass() && net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof PauseScreen) {
			cir.setReturnValue(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(12));   // glass: strong, only behind the panels
			return;
		}
		int blur = ScreenLook.pause().blur;
		if (blur >= 0 && net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof PauseScreen) {
			cir.setReturnValue(blur);
		}
	}
}
*///?}
