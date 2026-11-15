package net.veloclient.legacy.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.living.LivingEntity;
import net.veloclient.legacy.feature.PvpFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.Buffer;
import java.nio.FloatBuffer;

/** Hit Color: rewrites the hurt flash colour right before the game hands it to OpenGL. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererHitColorMixin {

	@Shadow
	protected FloatBuffer tintBuffer;

	@Inject(method = "setupOverlayColor(Lnet/minecraft/entity/living/LivingEntity;FZ)Z",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexEnv(IILjava/nio/FloatBuffer;)V"), require = 0)
	private void velo$hitColor(LivingEntity entity, float tickDelta, boolean alwaysRender, CallbackInfoReturnable<Boolean> cir) {
		float[] c = PvpFeatures.HitColor.color();
		if (c == null || !(entity.damagedTimer > 0 || entity.deathTicks > 0)) {
			return;
		}
		((Buffer) tintBuffer).position(0);
		tintBuffer.put(c[0]).put(c[1]).put(c[2]).put(c[3]);
		((Buffer) tintBuffer).flip();
	}
}
