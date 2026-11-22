package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Dev probe ({@code -Dvelo.quadProbe=true}): which item submits cost the most - submits and quads per display context,
 * how many quads are cutout vs translucent / glint / outline / emissive, and whether a submit's quad list is the same
 * object as last frame (mesh-cache key stability). Logged every 5 seconds. Nothing without the flag.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.feature.ItemFeatureRenderer.class)
public abstract class TurboEntityQuadProbeMixin {

	@Inject(method = "prepareMainSubmit", at = @At("HEAD"))
	private void velo$count(net.minecraft.client.renderer.feature.ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		if (!net.veloclient.velo.client.devtools.QuadProbe.ON) {
			return;
		}
		var quads = submit.quads();
		boolean[] blended = new boolean[quads.size()];
		for (int i = 0; i < blended.length; i++) {
			blended[i] = quads.get(i).materialInfo().itemRenderType().hasBlending();
		}
		net.veloclient.velo.client.devtools.QuadProbe.submit(String.valueOf(submit.displayContext()), quads, blended,
				submit.foilType() != null && submit.foilType() != net.minecraft.client.renderer.item.ItemStackRenderState.FoilType.NONE);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboEntityQuadProbeMixin {
	// 26.2 dev probe only (VeloMixinPlugin skips it elsewhere).
}
//?}
