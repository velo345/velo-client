package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Velo Turbo "Leaf Culling Depth" (Sodium 0.9, 26.2+): a forest is mostly leaf quads, and every leaf block draws its faces
 * towards neighbouring leaf blocks too - layers deep inside the canopy that are only seen through the holes of the layers
 * in front. With the slider at N a leaf face is only built when a non-leaf block is within N blocks in the face's
 * direction, so only the N outermost layers of a canopy keep their faces. Runs on Sodium's chunk build threads; 0 = off
 * (vanilla), changing it rebuilds the chunks.
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class TurboSodiumLeafCullMixin {

	@Shadow
	protected net.minecraft.world.level.block.state.BlockState state;
	@Shadow
	protected net.minecraft.core.BlockPos pos;
	@Shadow
	protected net.minecraft.client.renderer.block.BlockAndTintGetter level;

	@Unique
	private final net.minecraft.core.BlockPos.MutableBlockPos velo$scratch = new net.minecraft.core.BlockPos.MutableBlockPos();

	@Inject(method = "shouldDrawSide", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$leafDepth(net.minecraft.core.Direction facing, CallbackInfoReturnable<Boolean> cir) {
		int depth = PerformanceBoostModule.leafCullDepth;
		if (depth <= 0 || !cir.getReturnValueZ() || !state.is(net.minecraft.tags.BlockTags.LEAVES)) {
			return;
		}
		for (int k = 1; k <= depth; k++) {
			velo$scratch.set(pos.getX() + facing.getStepX() * k, pos.getY() + facing.getStepY() * k, pos.getZ() + facing.getStepZ() * k);
			if (!level.getBlockState(velo$scratch).is(net.minecraft.tags.BlockTags.LEAVES)) {
				return;   // open air (or any other block) close enough: the face is visible
			}
		}
		cir.setReturnValue(false);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumLeafCullMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
