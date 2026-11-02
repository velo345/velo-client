package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.pets.PetsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pets: while you ride your pet the view sits up there with your (lifted) player model - first person and F5 alike.
 * Only the rendered camera moves; the real player, its hitbox and aim stay on the ground.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.Camera.class)
public abstract class PetRideCameraMixin {

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	public abstract net.minecraft.util.math.Vec3d getCameraPos();

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$rideCamera(CallbackInfo ci) {
		if (PetsModule.riding()) {
			var pos = getCameraPos();
			setPos(pos.x, pos.y + PetsModule.seatHeight(), pos.z);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.Camera.class)
public abstract class PetRideCameraMixin {

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	public abstract net.minecraft.world.phys.Vec3 position();

	@Inject(method = "update", at = @At("TAIL"))
	private void velo$rideCamera(CallbackInfo ci) {
		if (PetsModule.riding()) {
			var pos = position();
			setPosition(pos.x, pos.y + PetsModule.seatHeight(), pos.z);
		}
	}
}
*///?}
