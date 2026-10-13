package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.redstone.AxiomCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Axiom counts as inactive while Redstone Mode is on (its tool slot, scroll handling, keys and
 * builder tools all check this), so the two editors never fight over clicks, the scroll wheel or the
 * slot right of the hotbar. Only applies when Axiom is installed ({@link Pseudo}); Axiom itself is
 * untouched otherwise. See {@link AxiomCompat}.
 */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.AxiomClient", remap = false)
public abstract class AxiomDisableMixin {

	@Inject(method = "isAxiomActive", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void velo$offInRedstoneMode(CallbackInfoReturnable<Boolean> cir) {
		if (AxiomCompat.suppressed()) {
			cir.setReturnValue(false);
		}
	}
}
