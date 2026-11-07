package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo - particle section culling: vanilla only frustum-tests each particle, so smoke, rain splashes and torch
 * flames behind walls are still rotated, lit and written as vertices every frame. Sodium already knows which chunk
 * sections are occluded; a particle whose section it culled is skipped as if it were off screen.
 * Only with Sodium on 26.2+ (VeloMixinPlugin), switchable via PerformanceBoostModule.turboParticleCull.
 */
//? if >=26.2 {
/*@Mixin(value = net.minecraft.client.particle.QuadParticleGroup.class, priority = 1500)
public abstract class TurboSodiumParticleCullMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
	private void velo$newPass(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> cir) {
		net.veloclient.velo.client.modules.performance.SodiumVisibility.newPass();
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"), require = 0)
	private boolean velo$sectionCull(Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
		// The section lookup first: it's a few array reads, while the wrapped check may be another mod's per-particle
		// raycast (Particle Core) - culled sections then never pay for it.
		if (net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turboParticleCull
				&& net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				&& !net.veloclient.velo.client.modules.performance.SodiumVisibility.pointVisible(x, y, z)) {
			return false;
		}
		return original.call(frustum, x, y, z);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumParticleCullMixin {
	// Particle groups + Sodium's section tree: 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
//?}
