package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Full Bright on 26.x (2026-11-26, owner: "es ist einfach Nacht"): 26.x's lightmap shader multiplies brightness into
 * what's there - at light level 0 that's black, so no gamma value can lift it. A white ambient colour fills every light
 * level up to full brightness in the world's real colours (no Night Vision tint). 1.21.x keeps the gamma route
 * (its shader has a minimum light the gamma can stretch) - there this is an empty stub.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.renderer.LightmapRenderStateExtractor.class)
public abstract class LightmapFullBrightMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "extract", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
	private void velo$fullBright(net.minecraft.client.renderer.state.LightmapRenderState state, float partialTick,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.client.modules.performance.FullBrightModule.isActive()) {
			state.ambientColor = net.minecraft.client.renderer.LightmapRenderStateExtractor.WHITE;
			state.darknessEffectScale = 0f;
			state.bossOverlayWorldDarkening = 0f;
			state.nightVisionEffectIntensity = 0f;
			state.needsUpdate = true;
		}
	}
}
*///?} else {
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "net.veloclient.velo.client.mixin.NoLightmapExtractorBefore26")
public abstract class LightmapFullBrightMixin {
}
//?}
