package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Scope of TurboSodiumFlatMovingMixin: true only while moving blocks are built, and only in farm mode. 26.2+. */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer.class)
public abstract class TurboFarmMovingMixin {

	@Inject(method = "buildGroup", at = @At("HEAD"), require = 0)
	private void velo$begin(CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.AntiFarmLagModule.flatMovingBlocks =
				net.veloclient.velo.client.modules.performance.AntiFarmLagModule.active();
	}

	@Inject(method = "buildGroup", at = @At("RETURN"), require = 0)
	private void velo$end(CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.AntiFarmLagModule.flatMovingBlocks = false;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboFarmMovingMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
//?}
