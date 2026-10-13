package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.PhantomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Phantom: pressure plates / tripwires ("ignores block triggers") and sculk ("dampens vibrations") skip you. */
//? if <26.1 {
@Mixin(net.minecraft.entity.Entity.class)
public abstract class PhantomEntityMixin {

	@Inject(method = "canAvoidTraps", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$phantomTraps(CallbackInfoReturnable<Boolean> cir) {
		if (PhantomModule.ignoresRedstone(((net.minecraft.entity.Entity) (Object) this).getUuid())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "occludeVibrationSignals", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$phantomVibrations(CallbackInfoReturnable<Boolean> cir) {
		if (PhantomModule.ignoresRedstone(((net.minecraft.entity.Entity) (Object) this).getUuid())) {
			cir.setReturnValue(true);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class PhantomEntityMixin {

	@Inject(method = "isIgnoringBlockTriggers", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$phantomTraps(CallbackInfoReturnable<Boolean> cir) {
		if (PhantomModule.ignoresRedstone(((net.minecraft.world.entity.Entity) (Object) this).getUUID())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "dampensVibrations", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$phantomVibrations(CallbackInfoReturnable<Boolean> cir) {
		if (PhantomModule.ignoresRedstone(((net.minecraft.world.entity.Entity) (Object) this).getUUID())) {
			cir.setReturnValue(true);
		}
	}
}
*///?}
