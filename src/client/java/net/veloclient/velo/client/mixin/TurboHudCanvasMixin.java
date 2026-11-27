package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.hud.HudCanvas;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.hud.HudCanvas;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * HUD canvas (see {@link net.veloclient.velo.client.hud.HudCanvas}): draws a pending HUD recording into its texture right
 * before the real GUI pass, and gives the canvas' own GuiRenderer the texture as its "main render target".
 */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.client.gui.render.GuiRenderer.class)
public abstract class TurboHudCanvasMixin {

	@Shadow
	@Final
	private net.minecraft.client.render.VertexConsumerProvider.Immediate vertexConsumers;
	@Shadow
	@Final
	private net.minecraft.client.render.command.OrderedRenderCommandQueue commandQueue;
	@Shadow
	@Final
	private net.minecraft.client.render.command.RenderDispatcher dispatcher;

	@Inject(method = {"render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"}, at = @At("HEAD"), require = 0)
	private void velo$canvasFirst(com.mojang.blaze3d.buffers.GpuBufferSlice fogBuffer, CallbackInfo ci) {
		HudCanvas.renderBefore((net.minecraft.client.gui.render.GuiRenderer) (Object) this, vertexConsumers, commandQueue, dispatcher, fogBuffer);
	}

	@WrapOperation(method = {"renderPreparedDraws"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/MinecraftClient;getFramebuffer()Lnet/minecraft/client/gl/Framebuffer;"), require = 0)
	private net.minecraft.client.gl.Framebuffer velo$canvasTarget(net.minecraft.client.MinecraftClient client,
			Operation<net.minecraft.client.gl.Framebuffer> original) {
		return HudCanvas.isCanvasRenderer(this) ? HudCanvas.target() : original.call(client);
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.client.gui.render.GuiRenderer.class)
public abstract class TurboHudCanvasMixin {

	@Shadow
	@Final
	private net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureRenderDispatcher;

	@Inject(method = {"render"}, at = @At("HEAD"), require = 0)
	private void velo$canvasFirst(CallbackInfo ci) {
		HudCanvas.renderBefore((net.minecraft.client.gui.render.GuiRenderer) (Object) this, featureRenderDispatcher);
	}

	@WrapOperation(method = {"draw"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"), require = 0)
	private com.mojang.blaze3d.pipeline.RenderTarget velo$canvasTarget(net.minecraft.client.renderer.GameRenderer renderer,
			Operation<com.mojang.blaze3d.pipeline.RenderTarget> original) {
		return HudCanvas.isCanvasRenderer(this) ? HudCanvas.target() : original.call(renderer);
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboHudCanvasMixin {
	// HUD canvas: 26.2+ only (VeloMixinPlugin skips TurboHud* before that).
}
*///?}
