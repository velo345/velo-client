package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
//?} else {
/*import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
*///?}
import net.veloclient.velo.client.modules.performance.EntityDetailModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity Detail Distance: skips a living entity's extra render layers past the configured distance. */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityDetailLodMixin {

	//? if <26.1 {
	@Inject(method = "shouldRenderFeatures", at = @At("RETURN"), cancellable = true)
	private void velo$skipFarLayers(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && EntityDetailModule.skipLayers(
				state.entityType == net.minecraft.entity.EntityType.PLAYER, state.squaredDistanceToCamera)) {
			cir.setReturnValue(false);
		}
	}
	//?} else if <26.2 {
	/*@Inject(method = "shouldRenderLayers", at = @At("RETURN"), cancellable = true)
	private void velo$skipFarLayers(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && EntityDetailModule.skipLayers(
				state.entityType == net.minecraft.world.entity.EntityType.PLAYER, state.distanceToCameraSq)) {
			cir.setReturnValue(false);
		}
	}
	*///?} else {
	/*@Inject(method = "shouldRenderLayers", at = @At("RETURN"), cancellable = true)
	private void velo$skipFarLayers(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && EntityDetailModule.skipLayers(
				state.entityType == net.minecraft.world.entity.EntityTypes.PLAYER, state.distanceToCameraSq)) {
			cir.setReturnValue(false);
		}
	}
	*///?}
}
