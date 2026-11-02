package net.veloclient.velo.dev.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Velo Bridge teammate's outline takes the team color the server picked. */
//? if <26.1 {
@Mixin(net.minecraft.entity.Entity.class)
public abstract class BridgeGlowColorMixin {

	@Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
	private void velo$teammateColor(CallbackInfoReturnable<Integer> cir) {
		Integer color = net.veloclient.velo.dev.bridge.VeloBridgeClient.teammateColor(((net.minecraft.entity.Entity) (Object) this).getUuid());
		if (color != null) {
			cir.setReturnValue(color);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class BridgeGlowColorMixin {

	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void velo$teammateColor(CallbackInfoReturnable<Integer> cir) {
		Integer color = net.veloclient.velo.dev.bridge.VeloBridgeClient.teammateColor(((net.minecraft.world.entity.Entity) (Object) this).getUUID());
		if (color != null) {
			cir.setReturnValue(color);
		}
	}
}
*///?}
