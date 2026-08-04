package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
//?} else {
/*import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
*///?}
import net.veloclient.velo.client.modules.qol.FreeLookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link FreeLookModule} is active, replaces the camera's normal
 * "sit at/behind the player looking however they face" placement with an
 * orbit: positioned on a sphere of {@link FreeLookModule#orbitDistance()}
 * around the player's (interpolated) eye position at the module's own
 * (yaw, pitch) angle, always looking back toward that point - the player's
 * real position/rotation (already read by vanilla into {@code this.yaw}/
 * {@code this.pitch}/the camera's base position before this runs) is never
 * touched, only where the render camera itself ends up for this frame.
 *
 * <p>Same 26.1 restructuring as documented in the previous version of this
 * file: {@code Camera#update(World, Entity, boolean, boolean, float)}
 * became {@code update(DeltaTracker)} with the focused entity tracked
 * internally, and {@code pitch}/{@code yaw} were renamed {@code xRot}/
 * {@code yRot} - both verified against the real 26.1/26.2 client jars.
 * {@code setRotation(float, float)} and {@code setPos(double, double,
 * double)} keep the same shapes in both.
 */
//? if <26.1 {
@Mixin(Camera.class)
public abstract class CameraFreeLookMixin {

	@Shadow private float pitch;
	@Shadow private float yaw;

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$applyFreeLook(net.minecraft.world.World area, net.minecraft.entity.Entity focusedEntity,
			boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (!FreeLookModule.isActive() || focusedEntity == null) {
			return;
		}
		FreeLookModule.captureBaseIfNeeded(this.yaw, this.pitch);
		float orbitYaw = FreeLookModule.effectiveYaw();
		float orbitPitch = FreeLookModule.effectivePitch();
		double[] offset = FreeLookModule.cameraOffset(orbitYaw, orbitPitch, FreeLookModule.orbitDistance());
		Vec3d target = focusedEntity.getCameraPosVec(tickDelta);
		this.setPos(target.x + offset[0], target.y + offset[1], target.z + offset[2]);
		this.setRotation(orbitYaw, orbitPitch);
	}
}
//?} else {
/*@Mixin(Camera.class)
public abstract class CameraFreeLookMixin {

	@Shadow private float xRot;
	@Shadow private float yRot;

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$applyFreeLook(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!FreeLookModule.isActive()) {
			return;
		}
		net.minecraft.world.entity.Entity focusedEntity = ((Camera) (Object) this).entity();
		if (focusedEntity == null) {
			return;
		}
		float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(true);
		FreeLookModule.captureBaseIfNeeded(this.yRot, this.xRot);
		float orbitYaw = FreeLookModule.effectiveYaw();
		float orbitPitch = FreeLookModule.effectivePitch();
		double[] offset = FreeLookModule.cameraOffset(orbitYaw, orbitPitch, FreeLookModule.orbitDistance());
		Vec3 target = focusedEntity.getEyePosition(tickDelta);
		this.setPosition(target.x + offset[0], target.y + offset[1], target.z + offset[2]);
		this.setRotation(orbitYaw, orbitPitch);
	}
}
*///?}
