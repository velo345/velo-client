package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.RenderCullingModule;
import net.veloclient.velo.client.modules.performance.RenderCullingModule.EntityKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distance-culls decorative entities for {@link RenderCullingModule}. {@code shouldRender} is the
 * per-entity, per-frame gate vanilla already uses for frustum culling, and it's handed the camera
 * position directly - so skipping here costs one type check and a distance, and the entity never
 * gets a render state extracted at all.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$cullFarDecorations(net.minecraft.entity.Entity entity, net.minecraft.client.render.Frustum frustum,
			double cameraX, double cameraY, double cameraZ, CallbackInfoReturnable<Boolean> cir) {
		net.minecraft.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.entity.EntityType.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.entity.EntityType.ITEM_FRAME || type == net.minecraft.entity.EntityType.GLOW_ITEM_FRAME
						|| type == net.minecraft.entity.EntityType.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.entity.EntityType.ITEM ? EntityKind.ITEM
				: type == net.minecraft.entity.EntityType.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null) {
			return;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		if (RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(false);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$cullFarDecorations(net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ, CallbackInfoReturnable<Boolean> cir) {
		net.minecraft.world.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.world.entity.EntityType.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.world.entity.EntityType.ITEM_FRAME || type == net.minecraft.world.entity.EntityType.GLOW_ITEM_FRAME
						|| type == net.minecraft.world.entity.EntityType.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.world.entity.EntityType.ITEM ? EntityKind.ITEM
				: type == net.minecraft.world.entity.EntityType.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null) {
			return;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		if (RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(false);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$cullFarDecorations(net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ, CallbackInfoReturnable<Boolean> cir) {
		net.minecraft.world.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.world.entity.EntityTypes.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.world.entity.EntityTypes.ITEM_FRAME || type == net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME
						|| type == net.minecraft.world.entity.EntityTypes.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.world.entity.EntityTypes.ITEM ? EntityKind.ITEM
				: type == net.minecraft.world.entity.EntityTypes.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null) {
			return;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		if (RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(false);
		}
	}
}
*///?}
