package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.RenderCullingModule;
import net.veloclient.velo.client.modules.performance.RenderCullingModule.EntityKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/**
 * Distance-culls decorative entities for {@link RenderCullingModule}. A return-value modifier, not a cancellable HEAD
 * inject: that allocated a CallbackInfoReturnable for every entity every frame (~2% of the render thread). {@code shouldRender} is the
 * per-entity, per-frame gate vanilla already uses for frustum culling, and it's handed the camera
 * position directly - so skipping here costs one type check and a distance, and the entity never
 * gets a render state extracted at all. Armor stands showing a name are left alone - servers draw player names
 * above heads that way, and culling them made names vanish from 48 blocks on.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullFarDecorations(boolean visible, net.minecraft.entity.Entity entity, net.minecraft.client.render.Frustum frustum,
			double cameraX, double cameraY, double cameraZ) {
		if (!visible || RenderCullingModule.active() == null) {
			return visible; // nothing to cull - no work beyond the vanilla answer
		}
		net.minecraft.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.entity.EntityType.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.entity.EntityType.ITEM_FRAME || type == net.minecraft.entity.EntityType.GLOW_ITEM_FRAME
						|| type == net.minecraft.entity.EntityType.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.entity.EntityType.ITEM ? EntityKind.ITEM
				: type == net.minecraft.entity.EntityType.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null || kind == EntityKind.ARMOR_STAND && entity.hasCustomName() && entity.isCustomNameVisible()) {
			// Named armor stands are how servers draw player names and holograms - text, not decoration: vanilla's distance.
			return true;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		return !RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz);
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullFarDecorations(boolean visible, net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ) {
		if (!visible || RenderCullingModule.active() == null) {
			return visible; // nothing to cull - no work beyond the vanilla answer
		}
		net.minecraft.world.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.world.entity.EntityType.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.world.entity.EntityType.ITEM_FRAME || type == net.minecraft.world.entity.EntityType.GLOW_ITEM_FRAME
						|| type == net.minecraft.world.entity.EntityType.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.world.entity.EntityType.ITEM ? EntityKind.ITEM
				: type == net.minecraft.world.entity.EntityType.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null || kind == EntityKind.ARMOR_STAND && entity.hasCustomName() && entity.isCustomNameVisible()) {
			// Named armor stands are how servers draw player names and holograms - text, not decoration: vanilla's distance.
			return true;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		return !RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz);
	}
}
*///?} else if >=26.3 {
/*// 26.3 passes the partial tick as well.
@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullFarDecorations(boolean visible, net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ, float partialTick) {
		if (!visible || RenderCullingModule.active() == null) {
			return visible; // nothing to cull - no work beyond the vanilla answer
		}
		net.minecraft.world.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.world.entity.EntityTypes.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.world.entity.EntityTypes.ITEM_FRAME || type == net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME
						|| type == net.minecraft.world.entity.EntityTypes.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.world.entity.EntityTypes.ITEM ? EntityKind.ITEM
				: type == net.minecraft.world.entity.EntityTypes.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null || kind == EntityKind.ARMOR_STAND && entity.hasCustomName() && entity.isCustomNameVisible()) {
			// Named armor stands are how servers draw player names and holograms - text, not decoration: vanilla's distance.
			return true;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		return !RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz);
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RenderCullingEntityMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullFarDecorations(boolean visible, net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ) {
		if (!visible || RenderCullingModule.active() == null) {
			return visible; // nothing to cull - no work beyond the vanilla answer
		}
		net.minecraft.world.entity.EntityType<?> type = entity.getType();
		EntityKind kind = type == net.minecraft.world.entity.EntityTypes.ARMOR_STAND ? EntityKind.ARMOR_STAND
				: type == net.minecraft.world.entity.EntityTypes.ITEM_FRAME || type == net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME
						|| type == net.minecraft.world.entity.EntityTypes.PAINTING ? EntityKind.FRAME
				: type == net.minecraft.world.entity.EntityTypes.ITEM ? EntityKind.ITEM
				: type == net.minecraft.world.entity.EntityTypes.EXPERIENCE_ORB ? EntityKind.XP
				: null;
		if (kind == null || kind == EntityKind.ARMOR_STAND && entity.hasCustomName() && entity.isCustomNameVisible()) {
			// Named armor stands are how servers draw player names and holograms - text, not decoration: vanilla's distance.
			return true;
		}
		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		return !RenderCullingModule.cullEntity(kind, dx * dx + dy * dy + dz * dz);
	}
}
*///?}
