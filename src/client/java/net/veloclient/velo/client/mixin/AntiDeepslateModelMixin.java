package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.AntiDeepslateModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anti Deepslate: the block-state -> model lookup the chunk builder (vanilla's and Sodium's) and falling blocks use
 * answers with the replacement block's model for deepslate, ores and bedrock - so their rotation and patterns never
 * reach the screen. The blocks themselves are untouched.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.block.BlockModels.class)
public abstract class AntiDeepslateModelMixin {

	@Inject(method = "getModel", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$antiDeepslate(net.minecraft.block.BlockState state, CallbackInfoReturnable<net.minecraft.client.render.model.BlockStateModel> cir) {
		Object to = AntiDeepslateModule.replace(state);
		if (to != null) {
			cir.setReturnValue(((net.minecraft.client.render.block.BlockModels) (Object) this).getModel((net.minecraft.block.BlockState) to));
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.block.BlockStateModelSet.class)
public abstract class AntiDeepslateModelMixin {

	@Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$antiDeepslate(net.minecraft.world.level.block.state.BlockState state,
			CallbackInfoReturnable<net.minecraft.client.renderer.block.dispatch.BlockStateModel> cir) {
		Object to = AntiDeepslateModule.replace(state);
		if (to != null) {
			cir.setReturnValue(((net.minecraft.client.renderer.block.BlockStateModelSet) (Object) this).get((net.minecraft.world.level.block.state.BlockState) to));
		}
	}
}
*///?}
