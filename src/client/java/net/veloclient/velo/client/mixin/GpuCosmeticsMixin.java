package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.cosmetics.render.GpuCosmetics;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Draws the cosmetics GpuCosmetics recorded this frame right after vanilla drew its custom geometry groups (the ones
 * the cosmetics were submitted into) - same phase, same targets, so depth and ordering match the CPU path.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer.class)
public abstract class GpuCosmeticsMixin {

	@Inject(method = "executeGroup", at = @At("TAIL"), require = 0)
	private void velo$drawGpuCosmetics(net.minecraft.client.renderer.feature.FeatureFrameContext context, int index, java.util.List<?> submits,
			boolean translucent, CallbackInfo ci) {
		if ((Object) this instanceof net.minecraft.client.renderer.feature.CustomFeatureRenderer) {
			boolean blended = false;
			for (Object submit : submits) {
				if (submit instanceof net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit s && s.renderType().hasBlending()) {
					blended = true;
					break;
				}
			}
			GpuCosmetics.flush(blended);
		}
	}

	@Inject(method = "finishExecute", at = @At("TAIL"), require = 0)
	private void velo$drawLeftoverGpuCosmetics(net.minecraft.client.renderer.feature.FeatureFrameContext context, CallbackInfo ci) {
		GpuCosmetics.flushAll();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class GpuCosmeticsMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
