package net.veloclient.legacy.mixin;

import net.minecraft.client.render.entity.layer.AbstractArmorLayer;
import net.veloclient.legacy.feature.PvpFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 1.7 armour: worn armour flashes with the hit colour like the body does. */
@Mixin(AbstractArmorLayer.class)
public abstract class ArmorLayerTintMixin {

	@Inject(method = "colorsWhenDamaged", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$tintArmor(CallbackInfoReturnable<Boolean> cir) {
		if (PvpFeatures.HitColor.tintArmor()) {
			cir.setReturnValue(true);
		}
	}
}
