package net.veloclient.velo.addons.mixin;

//? if <26.1 {
import net.minecraft.client.render.Camera;
//?} else {
/*import net.minecraft.client.Camera;
*///?}
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link CinematicCameraModule} is active, replaces the camera's normal placement entirely
 * with the module's own free-floating position/rotation - unlike {@code CameraFreeLookMixin}
 * (which orbits a target and raycasts to avoid clipping through walls), this camera isn't
 * anchored to any entity and is deliberately allowed through terrain, matching a real free/
 * cinematic camera rather than a third-person view. {@link CinematicCameraModule#updateFrame()}
 * is called first so movement integrates against this frame's real elapsed time before the
 * (now possibly moved) position is read.
 *
 * <p>Same 26.1 method/field renames as documented on {@code CameraFreeLookMixin} - {@code
 * setPos}/{@code setRotation} keep the same shapes in both mappings.
 */
//? if <26.1 {
@Mixin(Camera.class)
public abstract class CinematicCameraMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$applyCinematicCamera(net.minecraft.world.World area, net.minecraft.entity.Entity focusedEntity,
			boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (!CinematicCameraModule.isActive()) {
			return;
		}
		CinematicCameraModule.updateFrame();
		this.setPos(CinematicCameraModule.x(), CinematicCameraModule.y(), CinematicCameraModule.z());
		this.setRotation(CinematicCameraModule.yaw(), CinematicCameraModule.pitch());
	}
}
//?} else {
/*@Mixin(Camera.class)
public abstract class CinematicCameraMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$applyCinematicCamera(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!CinematicCameraModule.isActive()) {
			return;
		}
		CinematicCameraModule.updateFrame();
		this.setPosition(CinematicCameraModule.x(), CinematicCameraModule.y(), CinematicCameraModule.z());
		this.setRotation(CinematicCameraModule.yaw(), CinematicCameraModule.pitch());
	}
}
*///?}
