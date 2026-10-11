package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anti Farm Lag: dropped item stacks draw a single model instead of up to five while in farm mode. */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.ItemEntityRenderer.class)
public abstract class AntiFarmLagItemMixin {

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void velo$single(net.minecraft.entity.ItemEntity entity, net.minecraft.client.render.entity.state.ItemEntityRenderState state,
			float tickProgress, CallbackInfo ci) {
		if (AntiFarmLagModule.simplifyItems()) {
			state.renderedAmount = Math.min(state.renderedAmount, 1);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.ItemEntityRenderer.class)
public abstract class AntiFarmLagItemMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void velo$single(net.minecraft.world.entity.item.ItemEntity entity, net.minecraft.client.renderer.entity.state.ItemEntityRenderState state,
			float partialTick, CallbackInfo ci) {
		if (AntiFarmLagModule.simplifyItems()) {
			state.count = Math.min(state.count, 1);
		}
	}
}
*///?}
