package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anti Farm Lag's particle budget: past the per-tick limit in farm mode, new particles are skipped. */
//? if <26.1 {
@Mixin(net.minecraft.client.particle.ParticleManager.class)
public abstract class AntiFarmLagParticleMixin {

	@Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$budget(net.minecraft.client.particle.Particle particle, CallbackInfo ci) {
		if (AntiFarmLagModule.dropParticle()) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.particle.ParticleEngine.class)
public abstract class AntiFarmLagParticleMixin {

	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$budget(net.minecraft.client.particle.Particle particle, CallbackInfo ci) {
		if (AntiFarmLagModule.dropParticle()) {
			ci.cancel();
		}
	}
}
*///?}
