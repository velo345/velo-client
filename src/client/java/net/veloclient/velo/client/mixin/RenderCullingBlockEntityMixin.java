package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.RenderCullingModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distance-culls block entities (signs, chests, banners, heads...) for {@link RenderCullingModule}
 * at the dispatcher's render-state extraction step - vanilla itself returns null there for block
 * entities it won't draw (no renderer / out of range), so returning null early is the same "skip
 * it" signal every caller already handles. Uses the dispatcher's own {@code cameraPos} (same
 * field name in every supported version), so it follows a detached/free camera correctly.
 * Beacons and end gateways are left alone - their beams are meant to be seen from afar.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.block.entity.BlockEntityRenderManager.class)
public abstract class RenderCullingBlockEntityMixin {

	@Shadow
	private net.minecraft.util.math.Vec3d cameraPos;

	@Inject(method = "getRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$cullFarBlockEntities(net.minecraft.block.entity.BlockEntity blockEntity, float tickDelta,
			net.minecraft.client.render.command.ModelCommandRenderer.CrumblingOverlayCommand crumbling,
			CallbackInfoReturnable<Object> cir) {
		if (cameraPos == null) {
			return;
		}
		net.minecraft.block.entity.BlockEntityType<?> type = blockEntity.getType();
		if (type == net.minecraft.block.entity.BlockEntityType.BEACON || type == net.minecraft.block.entity.BlockEntityType.END_GATEWAY) {
			return;
		}
		boolean sign = type == net.minecraft.block.entity.BlockEntityType.SIGN || type == net.minecraft.block.entity.BlockEntityType.HANGING_SIGN;
		net.minecraft.util.math.BlockPos pos = blockEntity.getPos();
		double dx = pos.getX() + 0.5 - cameraPos.x;
		double dy = pos.getY() + 0.5 - cameraPos.y;
		double dz = pos.getZ() + 0.5 - cameraPos.z;
		if (RenderCullingModule.cullBlockEntity(sign, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(null);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public abstract class RenderCullingBlockEntityMixin {

	@Shadow
	private net.minecraft.world.phys.Vec3 cameraPos;

	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$cullFarBlockEntities(net.minecraft.world.level.block.entity.BlockEntity blockEntity, float tickDelta,
			net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling,
			CallbackInfoReturnable<Object> cir) {
		velo$cull(blockEntity, cir);
	}

	private void velo$cull(net.minecraft.world.level.block.entity.BlockEntity blockEntity, CallbackInfoReturnable<Object> cir) {
		if (cameraPos == null) {
			return;
		}
		net.minecraft.world.level.block.entity.BlockEntityType<?> type = blockEntity.getType();
		if (type == net.minecraft.world.level.block.entity.BlockEntityType.BEACON || type == net.minecraft.world.level.block.entity.BlockEntityType.END_GATEWAY) {
			return;
		}
		boolean sign = type == net.minecraft.world.level.block.entity.BlockEntityType.SIGN || type == net.minecraft.world.level.block.entity.BlockEntityType.HANGING_SIGN;
		net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
		double dx = pos.getX() + 0.5 - cameraPos.x;
		double dy = pos.getY() + 0.5 - cameraPos.y;
		double dz = pos.getZ() + 0.5 - cameraPos.z;
		if (RenderCullingModule.cullBlockEntity(sign, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(null);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public abstract class RenderCullingBlockEntityMixin {

	@Shadow
	private net.minecraft.world.phys.Vec3 cameraPos;

	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$cullFarBlockEntities(net.minecraft.world.level.block.entity.BlockEntity blockEntity, float tickDelta,
			net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling, boolean extra,
			CallbackInfoReturnable<Object> cir) {
		if (cameraPos == null) {
			return;
		}
		net.minecraft.world.level.block.entity.BlockEntityType<?> type = blockEntity.getType();
		if (type == net.minecraft.world.level.block.entity.BlockEntityTypes.BEACON || type == net.minecraft.world.level.block.entity.BlockEntityTypes.END_GATEWAY) {
			return;
		}
		boolean sign = type == net.minecraft.world.level.block.entity.BlockEntityTypes.SIGN || type == net.minecraft.world.level.block.entity.BlockEntityTypes.HANGING_SIGN;
		net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
		double dx = pos.getX() + 0.5 - cameraPos.x;
		double dy = pos.getY() + 0.5 - cameraPos.y;
		double dz = pos.getZ() + 0.5 - cameraPos.z;
		if (RenderCullingModule.cullBlockEntity(sign, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(null);
		}
	}
}
*///?}
