package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.redstone.AxiomCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Axiom's own tool slot never counts as selected while Redstone Mode is on - ours replaces it. See {@link AxiomCompat}. */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.buildertools.BuilderToolManager", remap = false)
public abstract class AxiomToolSlotMixin {

	@Inject(method = "isToolSlotActive", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void velo$noAxiomSlot(CallbackInfoReturnable<Boolean> cir) {
		if (AxiomCompat.suppressed()) {
			cir.setReturnValue(false);
		}
	}
}
