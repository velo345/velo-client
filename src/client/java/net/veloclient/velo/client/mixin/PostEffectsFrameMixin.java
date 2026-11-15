package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Full-screen post effects on the world picture (Color Grading, Motion Blur), 26.2+. A frame there runs
 * update -> extract -> render, and render() clears the main target before it draws the level. The effects used to run
 * in Hud.extractRenderState (extract phase): they graded a picture that was about to be cleared, and on Vulkan the
 * screen went black (2026-11-15). Here they run inside render(), after the level and before the GUI is drawn - every
 * frame, no HUD cache in the way, and the HUD itself stays ungraded.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class PostEffectsFrameMixin {

	// method = {"render"} on purpose: a Stonecutter rule rewrites the plain `method = "render", at = @At(value = "INVOKE",` form to extractRenderState
	@Inject(method = {"render"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"), require = 0)
	private void velo$postEffects(CallbackInfo ci) {
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
			return;
		}
		net.veloclient.velo.client.modules.rendering.ColorGradingModule.applyIfActive();
		net.veloclient.velo.client.modules.rendering.MotionBlurModule.applyIfActive();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class PostEffectsFrameMixin {
	// Before 26.2 the effects run from HudManager (immediate-mode HUD, drawn right after the level).
}
//?}
