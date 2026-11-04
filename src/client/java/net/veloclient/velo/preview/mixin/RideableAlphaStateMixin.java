package net.veloclient.velo.preview.mixin;

import net.veloclient.velo.preview.tweaks.MountAlpha;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives every living-entity render state the alpha Rideable Opacity wants it drawn with (1 = normal). */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.state.LivingEntityRenderState.class)
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.state.LivingEntityRenderState.class)
*///?}
public abstract class RideableAlphaStateMixin implements MountAlpha {

	@Unique
	private float velo$alpha = 1f;

	@Override
	public float velo$mountAlpha() {
		return velo$alpha;
	}

	@Override
	public void velo$setMountAlpha(float alpha) {
		velo$alpha = alpha;
	}
}
