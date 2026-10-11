package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Anti Farm Lag: moving pistons further than the set distance skip their 2-tick slide in farm mode. */
//? if <26.1 {
@Mixin(net.minecraft.client.render.block.entity.PistonBlockEntityRenderer.class)
public abstract class AntiFarmLagPistonMixin {

	@Inject(method = "getRenderDistance", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$distance(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(AntiFarmLagModule.pistonDistance(cir.getReturnValueI()));
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.blockentity.PistonHeadRenderer.class)
public abstract class AntiFarmLagPistonMixin {

	@Inject(method = "getViewDistance", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$distance(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(AntiFarmLagModule.pistonDistance(cir.getReturnValueI()));
	}
}
*///?}
