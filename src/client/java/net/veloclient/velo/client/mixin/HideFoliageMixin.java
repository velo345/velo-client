package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.HideFoliageModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Foliage: hidden plants report an invisible render shape, which both vanilla's and Sodium's chunk builders skip
 * (no model, no faces) - the block itself, its hitbox and any water in it are untouched.
 */
//? if <26.1 {
@Mixin(net.minecraft.block.AbstractBlock.AbstractBlockState.class)
public abstract class HideFoliageMixin {

	@Shadow
	public abstract net.minecraft.block.Block getBlock();

	@Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hideFoliage(CallbackInfoReturnable<net.minecraft.block.BlockRenderType> cir) {
		if (HideFoliageModule.hidden(getBlock())) {
			cir.setReturnValue(net.minecraft.block.BlockRenderType.INVISIBLE);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class)
public abstract class HideFoliageMixin {

	@Shadow
	public abstract net.minecraft.world.level.block.Block getBlock();

	@Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hideFoliage(CallbackInfoReturnable<net.minecraft.world.level.block.RenderShape> cir) {
		if (HideFoliageModule.hidden(getBlock())) {
			cir.setReturnValue(net.minecraft.world.level.block.RenderShape.INVISIBLE);
		}
	}
}
*///?}
