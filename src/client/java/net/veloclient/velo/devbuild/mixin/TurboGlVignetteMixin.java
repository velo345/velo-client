package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 && <26.2 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.injection.At;
//?}

/**
 * Velo Turbo (TurboHudVignetteMixin for the OpenGL versions): the vignette is a full-screen quad blended as
 * dst * (1 - texture * color); in daylight its color fades to black and the quad multiplies every pixel by one - a
 * full-screen pass for nothing. A pure black color (no world-border tint) skips the draw; at night it draws as always.
 */
//? if >=1.21.2 && <26.1 {
@Mixin(net.minecraft.client.gui.hud.InGameHud.class)
public abstract class TurboGlVignetteMixin {

	@WrapOperation(method = "renderVignetteOverlay", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIII)V"))
	private void velo$skipInvisibleVignette(net.minecraft.client.gui.DrawContext context, com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
			net.minecraft.util.Identifier texture, int x, int y, float u, float v, int width, int height, int texWidth, int texHeight, int color,
			Operation<Void> original) {
		if (PerformanceBoostModule.turbo && (color & 0xFFFFFF) == 0) {
			return;
		}
		original.call(context, pipeline, texture, x, y, u, v, width, height, texWidth, texHeight, color);
	}
}
//?} else if >=26.1 && <26.2 {
/*@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class TurboGlVignetteMixin {

	@WrapOperation(method = "extractVignette", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"))
	private void velo$skipInvisibleVignette(net.minecraft.client.gui.GuiGraphicsExtractor graphics, com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
			net.minecraft.resources.Identifier texture, int x, int y, float u, float v, int width, int height, int texWidth, int texHeight, int color,
			Operation<Void> original) {
		if (PerformanceBoostModule.turbo && (color & 0xFFFFFF) == 0) {
			return;
		}
		original.call(graphics, pipeline, texture, x, y, u, v, width, height, texWidth, texHeight, color);
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboGlVignetteMixin {
	// 1.21.1 draws the vignette with shader colour state; 26.2+ have TurboHudVignetteMixin.
}
*///?}
