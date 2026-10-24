package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import net.veloclient.velo.client.modules.rendering.VeloShadowEntities;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders: keeps this frame's entity render states for the entity shadow pass - vanilla clears
 * the list right after submitting them, before the shadow pass runs.
 */
//? if >=26.2 {
/*@Mixin(LevelRenderer.class)
public abstract class VeloShadowEntityStatesMixin {

	@Inject(method = "submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
			at = @At("HEAD"), require = 1)
	private void velo$keepEntityStates(LevelRenderState state, net.minecraft.client.renderer.SubmitNodeCollector collector, boolean outlines, CallbackInfo ci) {
		VeloShadowEntities.FRAME_STATES.clear();
		if (VeloShadersModule.entityShadowsActive()) {
			VeloShadowEntities.FRAME_STATES.addAll(state.entityRenderStates);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadowEntityStatesMixin {
	// Velo Shaders: 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
