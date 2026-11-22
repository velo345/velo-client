package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Dev probe ({@code -Dvelo.quadProbe=true}): model submits per frame by model class and render type (QuadProbe). */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.feature.ModelFeatureRenderer.class)
public abstract class TurboEntityModelProbeMixin {

	@Inject(method = "prepareModel", at = @At("HEAD"))
	private void velo$count(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (!net.veloclient.velo.client.devtools.QuadProbe.ON) {
			return;
		}
		var type = submit.renderType();
		String key = "M " + submit.model().getClass().getSimpleName() + " / " + type + (type.hasBlending() ? " BLEND" : "")
				+ (type.sortOnUpload() ? " SORT" : "") + (submit.sprite() != null ? " SPRITE" : "") + (submit.sheetedDecalPose() != null ? " DECAL" : "");
		net.veloclient.velo.client.devtools.QuadProbe.submit(key, java.util.List.of(), new boolean[0], false);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboEntityModelProbeMixin {
	// 26.2 dev probe only (VeloMixinPlugin skips it elsewhere).
}
//?}
