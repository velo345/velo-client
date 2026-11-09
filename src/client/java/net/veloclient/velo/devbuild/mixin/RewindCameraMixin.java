package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Velo Rewind: while a clip's camera keyframes drive the view, the camera's position and angles come from them every frame
 * (interpolated between ticks - smooth at any frame rate), with roll, and the FOV track overrides the field of view.
 */
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindCameraMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.Camera.class)
public abstract class RewindCameraMixin {

	@org.spongepowered.asm.mixin.Shadow
	protected abstract void setPosition(double x, double y, double z);

	@org.spongepowered.asm.mixin.Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@org.spongepowered.asm.mixin.injection.Inject(method = "update", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
	private void velo$rewindCamera(net.minecraft.client.DeltaTracker delta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		double[] cam = net.veloclient.velo.devbuild.rewind.RewindApply.camera(delta.getGameTimeDeltaPartialTick(false));
		if (cam != null) {
			setPosition(cam[0], cam[1], cam[2]);
			setRotation((float) cam[3], (float) cam[4]);
		}
	}

	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "setRotation", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"), index = 2)
	private float velo$rewindRoll(float roll) {
		return roll + net.veloclient.velo.devbuild.rewind.RewindApply.rollRadians();
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "calculateFov", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindFov(float partial, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		Float fov = net.veloclient.velo.devbuild.rewind.RewindApply.fov();
		if (fov != null) {
			cir.setReturnValue(fov);
		}
	}
}
*///?}
