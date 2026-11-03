package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.LowFireModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low Fire: the first-person fire overlay is drawn lower (or not at all with Fire Resistance). */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.InGameOverlayRenderer.class)
public abstract class LowFireMixin {

	@Unique
	private static boolean velo$pushed;

	@Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true, require = 0)
	private static void velo$lower(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider consumers,
			net.minecraft.client.texture.Sprite sprite, CallbackInfo ci) {
		if (LowFireModule.hide()) {
			ci.cancel();
			return;
		}
		float offset = LowFireModule.offset();
		velo$pushed = offset > 0f;
		if (velo$pushed) {
			matrices.push();
			matrices.translate(0f, -offset, 0f);
		}
	}

	@Inject(method = "renderFireOverlay", at = @At("RETURN"), require = 0)
	private static void velo$restore(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider consumers,
			net.minecraft.client.texture.Sprite sprite, CallbackInfo ci) {
		if (velo$pushed) {
			velo$pushed = false;
			matrices.pop();
		}
	}

	/** Opacity: the overlay's vertex alpha (vanilla 0.9) scaled down. */
	@ModifyConstant(method = "renderFireOverlay", constant = @Constant(floatValue = 0.9f), require = 0)
	private static float velo$alpha(float alpha) {
		return alpha * LowFireModule.alpha();
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.ScreenEffectRenderer.class)
public abstract class LowFireMixin {

	@Unique
	private static boolean velo$pushed;

	@Inject(method = "renderFire", at = @At("HEAD"), cancellable = true, require = 0)
	private static void velo$lower(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		if (LowFireModule.hide()) {
			ci.cancel();
			return;
		}
		float offset = LowFireModule.offset();
		velo$pushed = offset > 0f;
		if (velo$pushed) {
			pose.pushPose();
			pose.translate(0f, -offset, 0f);
		}
	}

	@Inject(method = "renderFire", at = @At("RETURN"), require = 0)
	private static void velo$restore(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		if (velo$pushed) {
			velo$pushed = false;
			pose.popPose();
		}
	}

	// Opacity: the overlay's vertex alpha (vanilla 0.9) scaled down.
	@ModifyConstant(method = "renderFire", constant = @Constant(floatValue = 0.9f), require = 0)
	private static float velo$alpha(float alpha) {
		return alpha * LowFireModule.alpha();
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.ScreenEffectRenderer.class)
public abstract class LowFireMixin {

	@Unique
	private static boolean velo$pushed;

	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true, require = 0)
	private static void velo$lower(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		if (LowFireModule.hide()) {
			ci.cancel();
			return;
		}
		float offset = LowFireModule.offset();
		velo$pushed = offset > 0f;
		if (velo$pushed) {
			pose.pushPose();
			pose.translate(0f, -offset, 0f);
		}
	}

	@Inject(method = "submitFire", at = @At("RETURN"), require = 0)
	private static void velo$restore(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		if (velo$pushed) {
			velo$pushed = false;
			pose.popPose();
		}
	}

	// Opacity: the quad's color (vanilla 0xE5FFFFFF, alpha 0.9) gets a lower alpha.
	@ModifyConstant(method = "buildFireQuad", constant = @Constant(intValue = 0xE5FFFFFF), require = 0)
	private static int velo$alpha(int color) {
		int a = Math.round(((color >>> 24) & 0xFF) * LowFireModule.alpha());
		return (color & 0x00FFFFFF) | (a << 24);
	}
}
*///?}
