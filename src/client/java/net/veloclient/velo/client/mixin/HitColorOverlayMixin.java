package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.HitColorModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Points a hurt entity's overlay at its Hit Color category row. {@code getOverlay(Coords)} is the
 * single static place vanilla computes it for every living entity (and the armor layer reads it
 * back through {@code HitColorArmorMixin}).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.LivingEntityRenderer.class)
public abstract class HitColorOverlayMixin {

	@Inject(method = "getOverlay", at = @At("RETURN"), cancellable = true, require = 0)
	private static void velo$hitColor(net.minecraft.client.render.entity.state.LivingEntityRenderState state, float whiteOverlay,
			CallbackInfoReturnable<Integer> cir) {
		if (!state.hurt) {
			return;
		}
		int row = HitColorModule.rowFor(net.veloclient.velo.client.pvp.HitColorEquipment.category(state));
		if (row >= 0) {
			cir.setReturnValue(HitColorModule.withRow(cir.getReturnValueI(), row));
		}
	}

}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public abstract class HitColorOverlayMixin {

	@Inject(method = "getOverlayCoords", at = @At("RETURN"), cancellable = true, require = 0)
	private static void velo$hitColor(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, float whiteOverlay,
			CallbackInfoReturnable<Integer> cir) {
		if (!state.hasRedOverlay) {
			return;
		}
		int row = HitColorModule.rowFor(net.veloclient.velo.client.pvp.HitColorEquipment.category(state));
		if (row >= 0) {
			cir.setReturnValue(HitColorModule.withRow(cir.getReturnValueI(), row));
		}
	}

}
*///?}
