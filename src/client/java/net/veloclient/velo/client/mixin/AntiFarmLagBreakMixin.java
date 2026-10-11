package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anti Farm Lag: block-break effects (64 particles each) over the per-tick budget are skipped, except right next to you. */
//? if <26.1 {
@Mixin(net.minecraft.client.world.ClientWorld.class)
public abstract class AntiFarmLagBreakMixin {

	@Inject(method = "addBlockBreakParticles", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$budget(net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state, CallbackInfo ci) {
		if (AntiFarmLagModule.dropBreakEffect(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public abstract class AntiFarmLagBreakMixin {

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$budget(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, CallbackInfo ci) {
		if (AntiFarmLagModule.dropBreakEffect(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
			ci.cancel();
		}
	}
}
*///?}
