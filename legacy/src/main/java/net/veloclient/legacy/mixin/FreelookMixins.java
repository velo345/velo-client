package net.veloclient.legacy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.veloclient.legacy.feature.PvpFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook: while it's on, mouse movement turns the camera instead of the player. */
@Mixin(Entity.class)
public abstract class FreelookMixins {

	@Inject(method = "updateLocalPlayerCamera", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$freelookTurn(float yaw, float pitch, CallbackInfo ci) {
		if (PvpFeatures.Freelook.active && (Object) this == Minecraft.getInstance().player) {
			PvpFeatures.Freelook.turn(yaw, pitch);
			ci.cancel();
		}
	}
}
