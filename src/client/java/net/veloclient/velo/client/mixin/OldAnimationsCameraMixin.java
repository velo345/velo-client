package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.OldAnimationsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Old Animations' sneak camera: the eye height snaps to its target instead of easing (1.8 sneak),
 * and optionally uses 1.8's sneaking eye height (1.54 instead of 1.27 - camera only, your hitbox
 * and what the server sees are unchanged).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.Camera.class)
public abstract class OldAnimationsCameraMixin {

	@Shadow
	private net.minecraft.entity.Entity focusedEntity;
	@Shadow
	private float cameraY;
	@Shadow
	private float lastCameraY;

	@Inject(method = "updateEyeHeight", at = @At("TAIL"), require = 0)
	private void velo$sneakCamera(CallbackInfo ci) {
		if (focusedEntity == null) {
			return;
		}
		float target = focusedEntity.getStandingEyeHeight();
		if (OldAnimationsModule.oldSneakHeight() && focusedEntity.isInSneakingPose()) {
			target = 1.54f;
		}
		if (OldAnimationsModule.instantSneak()) {
			cameraY = target;
			lastCameraY = target;
		} else if (OldAnimationsModule.oldSneakHeight() && focusedEntity.isInSneakingPose()) {
			cameraY += (target - cameraY) * 0.5f;
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.Camera.class)
public abstract class OldAnimationsCameraMixin {

	@Shadow
	private net.minecraft.world.entity.Entity entity;
	@Shadow
	private float eyeHeight;
	@Shadow
	private float eyeHeightOld;

	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void velo$sneakCamera(CallbackInfo ci) {
		if (entity == null) {
			return;
		}
		float target = entity.getEyeHeight();
		if (OldAnimationsModule.oldSneakHeight() && entity.isCrouching()) {
			target = 1.54f;
		}
		if (OldAnimationsModule.instantSneak()) {
			eyeHeight = target;
			eyeHeightOld = target;
		} else if (OldAnimationsModule.oldSneakHeight() && entity.isCrouching()) {
			eyeHeight += (target - eyeHeight) * 0.5f;
		}
	}
}
*///?}
