package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeStorage;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import net.veloclient.velo.client.modules.rendering.VeloShadowMap;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders: vanilla's round blob under each entity steps aside while real entity shadows are
 * in the sun shadow map (it showed as a dark disc inside your own shadow).
 */
//? if >=26.2 {
/*@Mixin(SubmitNodeStorage.class)
public abstract class VeloShadersBlobShadowMixin {

	@Inject(method = "submitShadow", at = @At("HEAD"), cancellable = true)
	private void velo$noBlobShadow(CallbackInfo ci) {
		if (VeloShadowMap.valid && VeloShadersModule.entityShadowsActive()) {
			ci.cancel();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadersBlobShadowMixin {
	// Velo Shaders: 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
