package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders: hands the sun angle, sky colour and moon phase to the shaders, and - while the
 * shader sky is on - skips vanilla's sun, moon, stars and sunrise fan, which the pack's sky shader
 * draws itself (procedural sun, phased moon, twinkling stars, aurora).
 */
//? if >=26.2 {
/*@Mixin(SkyRenderer.class)
public abstract class VeloShadersSkyMixin {

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void velo$captureSky(net.minecraft.client.multiplayer.ClientLevel level, float partialTick, net.minecraft.client.Camera camera,
			SkyRenderState state, CallbackInfo ci) {
		VeloShadersModule.captureSky(state.sunAngle, net.veloclient.velo.client.modules.rendering.VeloShaderCompat.skyColor(state), state.moonPhase == null ? 0 : state.moonPhase.ordinal());
	}

	@Inject(method = {"renderSunMoonAndStars", "renderSunriseAndSunset"}, at = @At("HEAD"), cancellable = true)
	private void velo$shaderSkyDrawsThese(CallbackInfo ci) {
		if (VeloShadersModule.skyActive()) {
			ci.cancel();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadersSkyMixin {
	// Velo Shaders: 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
