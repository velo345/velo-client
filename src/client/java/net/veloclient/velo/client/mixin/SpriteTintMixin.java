package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.hud.SpriteTints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recolors vanilla HUD sprites (hotbar, XP / locator bar) for the Hotbar and XP Bar modules: when a
 * sprite is drawn untinted (color -1) and a module wants a tint for it, the draw is re-issued with
 * that color instead. The re-issued call has a different color, so it passes straight through.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.DrawContext.class)
public abstract class SpriteTintMixin {

	@Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIII)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$tint(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.util.Identifier sprite,
			int x, int y, int width, int height, int color, CallbackInfo ci) {
		if (color != -1) {
			return;
		}
		int tint = SpriteTints.tintFor(sprite.getNamespace(), sprite.getPath());
		if (tint != 0 && tint != -1) {
			ci.cancel();
			((net.minecraft.client.gui.DrawContext) (Object) this).drawGuiTexture(pipeline, sprite, x, y, width, height, tint);
		}
	}

	@Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIIII)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$tintPart(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.util.Identifier sprite,
			int textureWidth, int textureHeight, int u, int v, int x, int y, int width, int height, int color, CallbackInfo ci) {
		if (color != -1) {
			return;
		}
		int tint = SpriteTints.tintFor(sprite.getNamespace(), sprite.getPath());
		if (tint != 0 && tint != -1) {
			ci.cancel();
			((net.minecraft.client.gui.DrawContext) (Object) this).drawGuiTexture(pipeline, sprite, textureWidth, textureHeight, u, v, x, y,
					width, height, tint);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.GuiGraphicsExtractor.class)
public abstract class SpriteTintMixin {

	@Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$tint(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.resources.Identifier sprite,
			int x, int y, int width, int height, int color, CallbackInfo ci) {
		if (color != -1) {
			return;
		}
		int tint = SpriteTints.tintFor(sprite.getNamespace(), sprite.getPath());
		if (tint != 0 && tint != -1) {
			ci.cancel();
			((net.minecraft.client.gui.GuiGraphicsExtractor) (Object) this).blitSprite(pipeline, sprite, x, y, width, height, tint);
		}
	}

	@Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIIII)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$tintPart(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.resources.Identifier sprite,
			int textureWidth, int textureHeight, int u, int v, int x, int y, int width, int height, int color, CallbackInfo ci) {
		if (color != -1) {
			return;
		}
		int tint = SpriteTints.tintFor(sprite.getNamespace(), sprite.getPath());
		if (tint != 0 && tint != -1) {
			ci.cancel();
			((net.minecraft.client.gui.GuiGraphicsExtractor) (Object) this).blitSprite(pipeline, sprite, textureWidth, textureHeight, u, v, x, y,
					width, height, tint);
		}
	}
}
*///?}
