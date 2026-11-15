package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.modules.performance.ExplosionMerge;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Explosion merge, packet side (see {@link net.veloclient.velo.client.modules.performance.ExplosionMerge}): decides per
 * explosion packet whether its emitter particle, its explosion effects and its sound are drawn; the knockback and the
 * rest of the packet are left alone.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class TurboExplosionMergeMixin {

	@Unique
	private boolean velo$merged;

	@Inject(method = "handleExplosion", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;center()Lnet/minecraft/world/phys/Vec3;", ordinal = 0), require = 0)
	private void velo$decide(net.minecraft.network.protocol.game.ClientboundExplodePacket packet, CallbackInfo ci) {
		net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
		net.minecraft.world.phys.Vec3 c = packet.center();
		velo$merged = level != null && ExplosionMerge.merge(c.x, c.y, c.z, level.getGameTime());
	}

	@WrapOperation(method = "handleExplosion", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
			require = 0)
	private void velo$mergedSound(net.minecraft.client.multiplayer.ClientLevel level, double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
			net.minecraft.sounds.SoundSource source, float volume, float pitch, boolean distanceDelay, Operation<Void> original) {
		if (!velo$merged) {
			original.call(level, x, y, z, sound, source, volume, pitch, distanceDelay);
		}
	}

	@WrapOperation(method = "handleExplosion", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"), require = 0)
	private void velo$mergedEmitter(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.core.particles.ParticleOptions particle,
			double x, double y, double z, double vx, double vy, double vz, Operation<Void> original) {
		if (!velo$merged) {
			original.call(level, particle, x, y, z, vx, vy, vz);
		}
	}

	@WrapOperation(method = "handleExplosion", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;trackExplosionEffects(Lnet/minecraft/world/phys/Vec3;FILnet/minecraft/util/random/WeightedList;)V"),
			require = 0)
	private void velo$mergedEffects(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.phys.Vec3 center, float radius, int blockCount,
			net.minecraft.util.random.WeightedList<?> particles, Operation<Void> original) {
		if (!velo$merged) {
			original.call(level, center, radius, blockCount, particles);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboExplosionMergeMixin {
	// 26.2+ only.
}
//?}
