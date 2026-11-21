package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.NoTotemPopModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Totem Pop: the totem particle burst around whoever popped one. */
//? if <26.1 {
@Mixin(net.minecraft.client.particle.ParticleManager.class)
public abstract class NoTotemPopParticlesMixin {

	@Inject(method = "addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;I)V", at = @At("HEAD"),
			cancellable = true, require = 0)
	private void velo$noTotemParticles(net.minecraft.entity.Entity entity, net.minecraft.particle.ParticleEffect effect, int ticks, CallbackInfo ci) {
		if (NoTotemPopModule.hideParticles() && effect != null && effect.getType() == net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.particle.ParticleEngine.class)
public abstract class NoTotemPopParticlesMixin {

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noTotemParticles(net.minecraft.world.entity.Entity entity, net.minecraft.core.particles.ParticleOptions effect, int ticks,
			CallbackInfo ci) {
		if (NoTotemPopModule.hideParticles() && effect != null && effect.getType() == net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING) {
			ci.cancel();
		}
	}
}
*///?}
