package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turbo Block Entities: block entities (chests, signs, banners, skulls, shulker boxes...) further away than their
 * renderer's view distance x the Performance Boost slider aren't extracted for drawing at all - the big win on
 * servers with thousands of chests and signs. Every block entity passes through this one method (Sodium too).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.block.entity.BlockEntityRenderManager.class)
public abstract class TurboBlockEntitiesMixin {

	@Shadow
	private net.minecraft.util.math.Vec3d cameraPos;

	@Shadow
	public abstract <E extends net.minecraft.block.entity.BlockEntity, S extends net.minecraft.client.render.block.entity.state.BlockEntityRenderState>
			net.minecraft.client.render.block.entity.BlockEntityRenderer<E, S> get(E blockEntity);

	@Inject(method = "getRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends net.minecraft.block.entity.BlockEntity> void velo$turbo(E blockEntity, float tickDelta,
			net.minecraft.client.render.command.ModelCommandRenderer.CrumblingOverlayCommand crumbling, CallbackInfoReturnable<Object> cir) {
		double share = PerformanceBoostModule.turboBlockEntities;
		if (share >= 0.999 || cameraPos == null) {
			return;
		}
		var renderer = get(blockEntity);
		if (renderer == null) {
			return;
		}
		double limit = renderer.getRenderDistance() * share;
		if (blockEntity.getPos().toCenterPos().squaredDistanceTo(cameraPos) > limit * limit) {
			cir.setReturnValue(null);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public abstract class TurboBlockEntitiesMixin {

	@Shadow
	private net.minecraft.world.phys.Vec3 cameraPos;

	@Shadow
	public abstract <E extends net.minecraft.world.level.block.entity.BlockEntity, S extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState>
			net.minecraft.client.renderer.blockentity.BlockEntityRenderer<E, S> getRenderer(E blockEntity);

	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends net.minecraft.world.level.block.entity.BlockEntity> void velo$turbo(E blockEntity, float partialTick,
			net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfoReturnable<Object> cir) {
		double share = PerformanceBoostModule.turboBlockEntities;
		if (share >= 0.999 || cameraPos == null) {
			return;
		}
		var renderer = getRenderer(blockEntity);
		if (renderer == null) {
			return;
		}
		// "getViewDistance( )" with a space: a Stonecutter options rule rewrites the plain call to renderDistance().
		double limit = renderer.getViewDistance( ) * share;
		net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
		if (cameraPos.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > limit * limit) {
			cir.setReturnValue(null);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public abstract class TurboBlockEntitiesMixin {

	@Shadow
	private net.minecraft.world.phys.Vec3 cameraPos;

	@Shadow
	public abstract <E extends net.minecraft.world.level.block.entity.BlockEntity, S extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState>
			net.minecraft.client.renderer.blockentity.BlockEntityRenderer<E, S> getRenderer(E blockEntity);

	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends net.minecraft.world.level.block.entity.BlockEntity> void velo$turbo(E blockEntity, float partialTick,
			net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling, boolean flag, CallbackInfoReturnable<Object> cir) {
		double share = PerformanceBoostModule.turboBlockEntities;
		if (share >= 0.999 || cameraPos == null) {
			return;
		}
		var renderer = getRenderer(blockEntity);
		if (renderer == null) {
			return;
		}
		// "getViewDistance( )" with a space: a Stonecutter options rule rewrites the plain call to renderDistance().
		double limit = renderer.getViewDistance( ) * share;
		net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
		if (cameraPos.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > limit * limit) {
			cir.setReturnValue(null);
		}
	}
}
*///?}
