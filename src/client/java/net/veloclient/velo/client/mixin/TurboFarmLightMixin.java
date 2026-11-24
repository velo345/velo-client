package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if >=26.2 {
/*import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Anti Farm Lag: every piston move flips a block between opaque and moving_piston, and the client's light engine
 * re-propagated sky light for each one on the render thread (16-25% in a piston farm, and the 30 ms frames). In farm
 * mode, checks inside sections that keep changing are collected instead (duplicates fall away) and released at a
 * bounded rate per tick. The integrated server's engine runs elsewhere and is left alone. 26.2+ only.
 */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.world.chunk.light.LightingProvider.class)
public abstract class TurboFarmLightMixin {

	@Unique
	private net.veloclient.velo.client.modules.performance.AntiFarmLagModule.LightFlusher velo$flusher;

	@Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$defer(net.minecraft.util.math.BlockPos pos, CallbackInfo ci) {
		if (!net.veloclient.velo.client.modules.performance.AntiFarmLagModule.active()) {
			return;
		}
		if (velo$flusher == null) {
			var self = (net.minecraft.world.chunk.light.LightingProvider) (Object) this;
			velo$flusher = key -> self.checkBlock(net.minecraft.util.math.BlockPos.fromLong(key));
		}
		if (net.veloclient.velo.client.modules.performance.AntiFarmLagModule.deferLightCheck(pos.asLong(),
				pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, velo$flusher)) {
			ci.cancel();
		}
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.world.level.lighting.LevelLightEngine.class)
public abstract class TurboFarmLightMixin {

	@Unique
	private net.veloclient.velo.client.modules.performance.AntiFarmLagModule.LightFlusher velo$flusher;

	@Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$defer(net.minecraft.core.BlockPos pos, CallbackInfo ci) {
		if (!net.veloclient.velo.client.modules.performance.AntiFarmLagModule.active()) {
			return;
		}
		if (velo$flusher == null) {
			var self = (net.minecraft.world.level.lighting.LevelLightEngine) (Object) this;
			velo$flusher = key -> self.checkBlock(net.minecraft.core.BlockPos.of(key));
		}
		if (net.veloclient.velo.client.modules.performance.AntiFarmLagModule.deferLightCheck(pos.asLong(),
				pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, velo$flusher)) {
			ci.cancel();
		}
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboFarmLightMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
*///?}
