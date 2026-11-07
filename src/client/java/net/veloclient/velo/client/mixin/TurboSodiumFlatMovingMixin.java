package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
*///?}

/**
 * Anti Farm Lag: blocks a piston is moving are re-tesselated every frame, and smooth lighting for them was 45% of the
 * render thread in a piston farm. In farm mode they use flat lighting (see TurboFarmMovingMixin for the scope); a block
 * sliding for two ticks doesn't show the difference. Only with Sodium on 26.2+ (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(value = net.caffeinemc.mods.sodium.client.render.frapi.render.NonTerrainBlockRenderContext.class, remap = false)
public abstract class TurboSodiumFlatMovingMixin {

	@Shadow
	@Final
	private boolean allowAO;

	@Redirect(method = "processQuad", at = @At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/render/frapi/render/NonTerrainBlockRenderContext;allowAO:Z"), require = 0)
	private boolean velo$flatWhileMoving(net.caffeinemc.mods.sodium.client.render.frapi.render.NonTerrainBlockRenderContext self) {
		return allowAO && !net.veloclient.velo.client.modules.performance.AntiFarmLagModule.flatMovingBlocks;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumFlatMovingMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
