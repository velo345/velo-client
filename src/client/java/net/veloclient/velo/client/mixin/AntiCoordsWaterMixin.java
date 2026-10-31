package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.AntiCoordsLeakModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anti Coords Leak: while coordinates are hidden, water has the same (plains) colour in every biome - swamp-green or
 * warm-ocean water on a stream would give away which biome a base is in. Vanilla's and Sodium's water tint both ask the
 * biome for its colour here.
 */
//? if <26.1 {
@Mixin(net.minecraft.world.biome.Biome.class)
//?} else {
/*@Mixin(net.minecraft.world.level.biome.Biome.class)
*///?}
public abstract class AntiCoordsWaterMixin {

	@Inject(method = "getWaterColor", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$sameWater(CallbackInfoReturnable<Integer> cir) {
		if (AntiCoordsLeakModule.uniformWater()) {
			cir.setReturnValue(AntiCoordsLeakModule.WATER_COLOR);
		}
	}
}
