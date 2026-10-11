package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.OldAnimationsModule;
import net.veloclient.velo.client.pvp.PvpCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Old Animations on the local player: swing duration (the arm animation length - attack timing is
 * the server's and untouched) and the fixed pre-1.19.4 hurt camera tilt (no damage direction).
 * Other entities are never affected.
 */
//? if <26.1 {
@Mixin(net.minecraft.entity.LivingEntity.class)
public abstract class OldAnimationsEntityMixin {

	@Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$swingDuration(CallbackInfoReturnable<Integer> cir) {
		if ((Object) this == PvpCompat.localPlayer()) {
			cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValueI() * OldAnimationsModule.swingScale())));
		}
	}

	@Inject(method = "getDamageTiltYaw", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$fixedTilt(CallbackInfoReturnable<Float> cir) {
		if (OldAnimationsModule.fixedHurtTilt() && (Object) this == PvpCompat.localPlayer()) {
			cir.setReturnValue(0f);
		}
	}
}
//?} else if <26.3 {
/*@Mixin(net.minecraft.world.entity.LivingEntity.class)
public abstract class OldAnimationsEntityMixin {

	@Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$swingDuration(CallbackInfoReturnable<Integer> cir) {
		if ((Object) this == PvpCompat.localPlayer()) {
			cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValueI() * OldAnimationsModule.swingScale())));
		}
	}

	@Inject(method = "getHurtDir", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$fixedTilt(CallbackInfoReturnable<Float> cir) {
		if (OldAnimationsModule.fixedHurtTilt() && (Object) this == PvpCompat.localPlayer()) {
			cir.setReturnValue(0f);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.world.entity.LivingEntity.class)
public abstract class OldAnimationsEntityMixin {

	@Inject(method = "getModifiedSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$swingDuration(net.minecraft.world.item.component.SwingAnimation animation, CallbackInfoReturnable<Integer> cir) {
		if ((Object) this == PvpCompat.localPlayer()) {
			cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValueI() * OldAnimationsModule.swingScale())));
		}
	}

	@Inject(method = "getHurtDir", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$fixedTilt(CallbackInfoReturnable<Float> cir) {
		if (OldAnimationsModule.fixedHurtTilt() && (Object) this == PvpCompat.localPlayer()) {
			cir.setReturnValue(0f);
		}
	}
}
*///?}
