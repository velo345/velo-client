package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo - particle extract reuse: every visible particle, every frame, allocated a fresh rotation quaternion (only
 * its four floats are handed on, nothing keeps it) - a render-thread scratch is reused instead. Off with -Dvelo.particleReuse=false.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.particle.SingleQuadParticle.class)
public abstract class TurboParticleReuseMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.particleReuse"));
	@Unique
	private static final Quaternionf VELO$SCRATCH = new Quaternionf();

	@WrapOperation(method = "extract", at = @At(value = "NEW", target = "org/joml/Quaternionf"), require = 0)
	private Quaternionf velo$scratchRotation(Operation<Quaternionf> original) {
		// extraction runs on the render thread only; anything else (another thread, a mod calling in) gets a fresh one
		if (!ON || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			return original.call();
		}
		return VELO$SCRATCH.identity();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboParticleReuseMixin {
	// 26.2+ only.
}
//?}
