package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.veloclient.velo.client.modules.performance.SodiumTreePool;
*///?}

/**
 * Velo Turbo: after Sodium picked this frame's trees (render thread), cull-pass arrays nobody uses any more go back to
 * {@link net.veloclient.velo.client.modules.performance.SodiumTreePool}. Only with Sodium on 26.2+ (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class TurboSodiumTreeSweepMixin {

	@Shadow
	@Final
	private java.util.Map<CullType, SectionTree> cullResults;

	@Shadow
	private SectionTree renderTree;

	@Inject(method = "prepareRenderTrees", at = @At("TAIL"), require = 0)
	private void velo$sweepTrees(CallbackInfo ci) {
		if (SodiumTreePool.ON) {
			SodiumTreePool.sweep(this, this.cullResults.values(), this.renderTree);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumTreeSweepMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
