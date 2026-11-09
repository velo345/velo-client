package net.veloclient.velo.dev.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Velo Bridge camera shake: only the rendered view moves, after the camera is set up - the player's real rotation
 * (and so their aim) is never touched.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.Camera.class)
public abstract class BridgeShakeMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	public abstract float getYaw();

	@Shadow
	public abstract float getPitch();

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$shake(CallbackInfo ci) {
		float[] offset = net.veloclient.velo.dev.bridge.BridgeShake.offset();
		if (offset != null) {
			setRotation(getYaw() + offset[0], getPitch() + offset[1]);
		}
		double[] cam = net.veloclient.velo.dev.bridge.BridgeCutscene.camera();
		if (cam != null) {
			setPos(cam[0], cam[1], cam[2]);
			setRotation((float) cam[3], (float) cam[4]);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.Camera.class)
public abstract class BridgeShakeMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	public abstract float yRot();

	@Shadow
	public abstract float xRot();

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$shake(CallbackInfo ci) {
		float[] offset = net.veloclient.velo.dev.bridge.BridgeShake.offset();
		if (offset != null) {
			setRotation(yRot() + offset[0], xRot() + offset[1]);
		}
		double[] cam = net.veloclient.velo.dev.bridge.BridgeCutscene.camera();
		if (cam != null) {
			setPosition(cam[0], cam[1], cam[2]);
			setRotation((float) cam[3], (float) cam[4]);
		}
	}
}
*///?}
