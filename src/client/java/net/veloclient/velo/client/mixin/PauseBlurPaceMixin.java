package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The glass pause menu blurs for real at most 60 times a second (2026-11-26, owner: menus cost too many FPS): on the
 * frames in between, vanilla's background blur is skipped and {@link net.veloclient.velo.client.gui.title.PauseMenu}
 * draws the last blurred copy behind its panels instead.
 */
//? if >=1.21.2 && <26.1 {
@Mixin(net.minecraft.client.gui.screen.Screen.class)
public abstract class PauseBlurPaceMixin {

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void velo$paceBlur(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if ((Object) this instanceof net.minecraft.client.gui.screen.GameMenuScreen && net.veloclient.velo.client.gui.title.PauseMenu.skipBlur()) {
			ci.cancel();
		}
	}
}
//?} else if >=26.1 {
/*@Mixin(net.minecraft.client.gui.screens.Screen.class)
public abstract class PauseBlurPaceMixin {

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void velo$paceBlur(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if ((Object) this instanceof net.minecraft.client.gui.screens.PauseScreen && net.veloclient.velo.client.gui.title.PauseMenu.skipBlur()) {
			ci.cancel();
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.gui.screen.Screen.class)
public abstract class PauseBlurPaceMixin {
}
*///?}
