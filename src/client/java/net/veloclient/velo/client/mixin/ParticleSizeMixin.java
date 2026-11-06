package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.ParticleSizeModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particle Size: the size every billboard particle is drawn at, scaled. */
//? if <26.1 {
@Mixin(net.minecraft.client.particle.BillboardParticle.class)
public abstract class ParticleSizeMixin {

	@Inject(method = "getSize", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$size(float tickProgress, CallbackInfoReturnable<Float> cir) {
		float f = ParticleSizeModule.factor();
		if (f != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * f);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.particle.SingleQuadParticle.class)
public abstract class ParticleSizeMixin {

	@Inject(method = "getQuadSize", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$size(float partialTick, CallbackInfoReturnable<Float> cir) {
		float f = ParticleSizeModule.factor();
		if (f != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * f);
		}
	}
}
*///?}
