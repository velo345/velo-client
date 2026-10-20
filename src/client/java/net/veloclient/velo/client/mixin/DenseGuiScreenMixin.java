package net.veloclient.velo.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.velo.client.gui.window.DenseGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws a dense Velo window (and its tooltips) at its finer scale (see {@link DenseGui}). */
@Mixin(Screen.class)
public abstract class DenseGuiScreenMixin {

	@Unique
	private boolean velo$densePushed;

	//? if <26.1 {
	@Inject(method = "renderWithTooltip", at = @At("HEAD"))
	//?} else {
	/*@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
	*///?}
	private void velo$denseBegin(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		velo$densePushed = DenseGui.appliesTo(this);
		if (velo$densePushed) {
			float s = 1f / DenseGui.factor();
			context.getMatrices().pushMatrix();
			context.getMatrices().scale(s, s);
		}
	}

	//? if <26.1 {
	@Inject(method = "renderWithTooltip", at = @At("RETURN"))
	//?} else {
	/*@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
	*///?}
	private void velo$denseEnd(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$densePushed) {
			velo$densePushed = false;
			context.getMatrices().popMatrix();
		}
	}
}
