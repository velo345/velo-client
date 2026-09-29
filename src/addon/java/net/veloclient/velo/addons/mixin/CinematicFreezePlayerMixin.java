package net.veloclient.velo.addons.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientPlayerEntity;
//?} else {
/*import net.minecraft.client.player.LocalPlayer;
*///?}
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the local player's own per-tick movement/physics update outright while {@link
 * CinematicCameraModule} is active, so the player stays frozen exactly where they were when the
 * camera detached - no gravity, no drift, no input reaching the entity at all (the movement keys
 * are read separately, straight from {@code GameOptions}/{@code Options}, to fly the camera
 * instead - see {@link CinematicCameraModule#updateFrame()}).
 *
 * <p>Yarn's {@code ClientPlayerEntity#tickMovement()} and Mojmap's {@code LocalPlayer#aiStep()}
 * are each that class's own override of the per-tick movement/physics step (verified via javap -
 * neither mapping's class file declares the other name), so cancelling the whole method is the
 * simplest complete freeze rather than trying to zero out individual input fields beforehand.
 */
//? if <26.1 {
@Mixin(ClientPlayerEntity.class)
public abstract class CinematicFreezePlayerMixin {

	@Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
	private void velo$freezeWhileCinematic(CallbackInfo ci) {
		if (CinematicCameraModule.isActive()) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(LocalPlayer.class)
public abstract class CinematicFreezePlayerMixin {

	@Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
	private void velo$freezeWhileCinematic(CallbackInfo ci) {
		if (CinematicCameraModule.isActive()) {
			ci.cancel();
		}
	}
}
*///?}
