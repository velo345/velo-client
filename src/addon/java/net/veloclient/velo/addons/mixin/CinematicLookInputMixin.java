package net.veloclient.velo.addons.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
*///?}
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same technique as {@code FreeLookInputMixin} (see there for the full mapping-divergence
 * rationale), redirecting mouse look into {@link CinematicCameraModule} instead while it's active
 * - a second, independent {@code @Inject} at the same point, guarded by its own module's own
 * {@code isActive()} check, so the two coexist without interfering (only one of the two modules
 * is ever actually active at a time in practice).
 */
//? if <26.1 {
@Mixin(Entity.class)
public abstract class CinematicLookInputMixin {

	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	private void velo$onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (!CinematicCameraModule.isActive() || (Object) this != MinecraftClient.getInstance().player) {
			return;
		}
		CinematicCameraModule.accumulateLookDelta(cursorDeltaX, cursorDeltaY);
		ci.cancel();
	}
}
//?} else {
/*@Mixin(Entity.class)
public abstract class CinematicLookInputMixin {

	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void velo$onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (!CinematicCameraModule.isActive() || (Object) this != Minecraft.getInstance().player) {
			return;
		}
		CinematicCameraModule.accumulateLookDelta(cursorDeltaX, cursorDeltaY);
		ci.cancel();
	}
}
*///?}
