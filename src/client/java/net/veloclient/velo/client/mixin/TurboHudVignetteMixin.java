package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.3 {
/*import com.mojang.renderpearl.api.pipeline.RenderPipeline;
*///?} else if >=26.2 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
*///?}
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo: no vignette draw when it can't change a pixel. The vignette is a full-screen quad blended as
 * dst * (1 - texture * color); its color follows how dark it is where you stand, and in daylight it fades to black -
 * then the quad multiplies every pixel by one and still costs a full-screen pass (19 us/frame on the bench, more than
 * the sky). With a pure black color (and no world-border warning, which tints it) the draw is skipped: the picture is
 * identical, at night and underground it is drawn as always.
 */
//? if >=26.3 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class TurboHudVignetteMixin {

	@WrapOperation(method = "extractVignette", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"))
	private void velo$skipInvisibleVignette(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y,
			float u, float v, int width, int height, int texWidth, int texHeight, int color, Operation<Void> original) {
		if (PerformanceBoostModule.turbo && (color & 0xFFFFFF) == 0) {
			return;
		}
		original.call(graphics, pipeline, texture, x, y, u, v, width, height, texWidth, texHeight, color);
	}
}
*///?} else if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class TurboHudVignetteMixin {

	@WrapOperation(method = "extractVignette", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"))
	private void velo$skipInvisibleVignette(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y,
			float u, float v, int width, int height, int texWidth, int texHeight, int color, Operation<Void> original) {
		if (PerformanceBoostModule.turbo && (color & 0xFFFFFF) == 0) {
			return;
		}
		original.call(graphics, pipeline, texture, x, y, u, v, width, height, texWidth, texHeight, color);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboHudVignetteMixin {
	// Vulkan-era versions only (VeloMixinPlugin skips TurboHud* elsewhere).
}
//?}
