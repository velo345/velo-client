package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
*///?}
import net.veloclient.velo.client.modules.qol.FreeLookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link FreeLookModule} is active, redirects the local player's mouse
 * look input into the module's own camera-only offset instead of letting it
 * reach the player entity's real yaw/pitch - the same method vanilla's own
 * {@code Mouse}/{@code MouseHandler} calls every frame with the
 * sensitivity-scaled cursor delta (Yarn: {@code Entity#changeLookDirection},
 * real Mojmap name: {@code Entity#turn}, verified via javap; both apply the
 * same 0.15-per-pixel scale and clamp pitch to [-90,90], reproduced in
 * {@link FreeLookModule#accumulateLookDelta}). Cancelling this here - rather
 * than, say, reverting the rotation afterward - means the player's real
 * rotation is simply never touched in the first place, with nothing to
 * fight and no rounding drift to undo.
 */
//? if <26.1 {
@Mixin(Entity.class)
public abstract class FreeLookInputMixin {

	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	private void velo$onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (!FreeLookModule.isActive() || (Object) this != MinecraftClient.getInstance().player) {
			return;
		}
		FreeLookModule.accumulateLookDelta(cursorDeltaX, cursorDeltaY);
		ci.cancel();
	}
}
//?} else {
/*@Mixin(Entity.class)
public abstract class FreeLookInputMixin {

	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void velo$onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (!FreeLookModule.isActive() || (Object) this != Minecraft.getInstance().player) {
			return;
		}
		FreeLookModule.accumulateLookDelta(cursorDeltaX, cursorDeltaY);
		ci.cancel();
	}
}
*///?}
