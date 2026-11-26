package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
		net.veloclient.velo.client.lyrics.LyricsPulse.applyIfActive();
		net.veloclient.velo.client.gui.glass.HudFrost.apply();   // last: the glass HUD frosts the finished world picture
	}
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.GameRenderer.class)
public abstract class PostEffectsFrameMixin {

	// 1.21.x: right before the HUD is drawn, in the frame itself. They used to run inside the HUD pass, and Gnetum (the
	// launcher installs it) draws the HUD into its own cached buffer - the effect landed there as an opaque layer over the
	// world: black screen until F1 (2026-11-16, reproduced with Gnetum 4.6.4 on 1.21.11).
	@Inject(method = {"render"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"),
			require = 0)
	private void velo$postEffects(CallbackInfo ci) {
		net.veloclient.velo.client.modules.rendering.ColorGradingModule.applyIfActive();
		net.veloclient.velo.client.modules.rendering.MotionBlurModule.applyIfActive();
		net.veloclient.velo.client.lyrics.LyricsPulse.applyIfActive();
		net.veloclient.velo.client.gui.glass.HudFrost.apply();
	}
}
//?} else {
/*@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class PostEffectsFrameMixin {
	// 26.1: the effects still run from HudManager.
}
*///?}
