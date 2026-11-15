package net.veloclient.legacy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.ItemInHandRenderer;
import net.veloclient.legacy.feature.PvpFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.7 block-hitting: the sword keeps swinging while it blocks (1.8 passes no swing to the blocking pose). */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandBlockHitMixin {

	@Inject(method = "renderInFirstPerson", at = @At("HEAD"), require = 0)
	private void velo$keepDelta(float tickDelta, CallbackInfo ci) {
		PvpFeatures.OldAnimations.tickDelta = tickDelta;
	}

	// the switch's calls in order: NONE (0), EAT/DRINK (1), BLOCK (2), BOW (3), not using an item (4)
	@ModifyArg(method = "renderInFirstPerson", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/ItemInHandRenderer;applyFirstPersonTransform(FF)V", ordinal = 2), index = 1, require = 0)
	private float velo$blockSwing(float swing) {
		if (!PvpFeatures.OldAnimations.blockHit()) {
			return swing;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? swing : mc.player.getAttackAnimationProgress(PvpFeatures.OldAnimations.tickDelta);
	}
}
