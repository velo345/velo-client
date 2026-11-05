package net.veloclient.velo.preview.mixin;

import net.veloclient.velo.preview.tweaks.MountAlpha;
import net.veloclient.velo.preview.tweaks.RideableOpacityModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the mount you ride see-through: the state remembers the alpha (filled in with the rest of the state), and while
 * that entity is submitted its model asks for vanilla's translucent layer and gets the alpha in its color - exactly how
 * vanilla shows invisible mobs to spectators. Layers (saddle, armor) keep drawing normally.
 */
//? if <1.21.2 {
/*// 1.21.1: this feature hooks a render system that came later - left out on 1.21.1.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RideableAlphaRenderMixin {
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.entity.LivingEntityRenderer.class)
public abstract class RideableAlphaRenderMixin {

	@Unique
	private static float velo$current = 1f;

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void velo$rememberAlpha(net.minecraft.entity.LivingEntity entity, net.minecraft.client.render.entity.state.LivingEntityRenderState state,
			float tickDelta, CallbackInfo ci) {
		((MountAlpha) state).velo$setMountAlpha(RideableOpacityModule.alphaFor(entity));
	}

	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("HEAD"), require = 0)
	private void velo$begin(net.minecraft.client.render.entity.state.LivingEntityRenderState state, net.minecraft.client.util.math.MatrixStack matrices,
			net.minecraft.client.render.command.OrderedRenderCommandQueue queue, net.minecraft.client.render.state.CameraRenderState camera, CallbackInfo ci) {
		velo$current = ((MountAlpha) state).velo$mountAlpha();
	}

	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.render.entity.state.LivingEntityRenderState state, net.minecraft.client.util.math.MatrixStack matrices,
			net.minecraft.client.render.command.OrderedRenderCommandQueue queue, net.minecraft.client.render.state.CameraRenderState camera, CallbackInfo ci) {
		velo$current = 1f;
	}

	@ModifyArg(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/LivingEntityRenderer;getRenderLayer"), index = 2, require = 0)
	private boolean velo$translucent(boolean translucent) {
		return translucent || velo$current < 0.999f;
	}

	@ModifyArg(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel"), index = 6, require = 0)
	private int velo$alpha(int color) {
		return velo$withAlpha(color, velo$current);
	}

	@Unique
	private static int velo$withAlpha(int color, float alpha) {
		if (alpha >= 0.999f) {
			return color;
		}
		int a = Math.round(((color >>> 24) & 0xFF) * alpha);
		return (color & 0x00FFFFFF) | (a << 24);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public abstract class RideableAlphaRenderMixin {

	@Unique
	private static float velo$current = 1f;

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void velo$rememberAlpha(net.minecraft.world.entity.LivingEntity entity, net.minecraft.client.renderer.entity.state.LivingEntityRenderState state,
			float partialTick, CallbackInfo ci) {
		((MountAlpha) state).velo$setMountAlpha(RideableOpacityModule.alphaFor(entity));
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"), require = 0)
	private void velo$begin(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack,
			net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		velo$current = ((MountAlpha) state).velo$mountAlpha();
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack,
			net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		velo$current = 1f;
	}

	@ModifyArg(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType"), index = 2, require = 0)
	private boolean velo$translucent(boolean translucent) {
		return translucent || velo$current < 0.999f;
	}

	@ModifyArg(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel"), index = 6, require = 0)
	private int velo$alpha(int color) {
		return velo$withAlpha(color, velo$current);
	}

	@Unique
	private static int velo$withAlpha(int color, float alpha) {
		if (alpha >= 0.999f) {
			return color;
		}
		int a = Math.round(((color >>> 24) & 0xFF) * alpha);
		return (color & 0x00FFFFFF) | (a << 24);
	}
}
*///?}
