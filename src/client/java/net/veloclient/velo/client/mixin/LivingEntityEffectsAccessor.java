package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The synced list of effect particles every living entity carries - what other players' potion
 * swirls are drawn from. {@code PvpCompat#visibleEffects} maps it back to the effects.
 */
//? if <26.1 {
@Mixin(net.minecraft.entity.LivingEntity.class)
public interface LivingEntityEffectsAccessor {

	@Accessor("POTION_SWIRLS")
	static net.minecraft.entity.data.TrackedData<java.util.List<net.minecraft.particle.ParticleEffect>> velo$effectParticles() {
		throw new AssertionError();
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.LivingEntity.class)
public interface LivingEntityEffectsAccessor {

	@Accessor("DATA_EFFECT_PARTICLES")
	static net.minecraft.network.syncher.EntityDataAccessor<java.util.List<net.minecraft.core.particles.ParticleOptions>> velo$effectParticles() {
		throw new AssertionError();
	}
}
*///?}
