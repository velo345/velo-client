package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import net.veloclient.velo.client.modules.performance.EntityVisCache;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/** Reuses an entity's visibility answer while nothing moved - see {@link net.veloclient.velo.client.modules.performance.EntityVisCache}. */
//? if >=26.3 {
/*@Mixin(net.minecraft.client.renderer.extract.LevelExtractor.class)
public abstract class TurboEntityVisibilityMixin {

	// Once per frame: did the camera move since the last frame? Then no answer can be reused this frame, and the
	// per-entity compare + store (1.4% of every frame while moving) is skipped altogether.
	@org.spongepowered.asm.mixin.Unique
	private static double velo$lastX, velo$lastY, velo$lastZ;
	@org.spongepowered.asm.mixin.Unique
	private static float velo$lastXRot, velo$lastYRot;
	@org.spongepowered.asm.mixin.Unique
	private static boolean velo$camMoved = true;
	@org.spongepowered.asm.mixin.Unique
	private static long velo$frameNanos;

	@org.spongepowered.asm.mixin.injection.Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void velo$newItemFrame(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		AntiFarmLagModule.beginItemFrame();
		net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 pos = camera.position();
		velo$camMoved = pos.x != velo$lastX || pos.y != velo$lastY || pos.z != velo$lastZ
				|| camera.xRot() != velo$lastXRot || camera.yRot() != velo$lastYRot;
		velo$lastX = pos.x;
		velo$lastY = pos.y;
		velo$lastZ = pos.z;
		velo$lastXRot = camera.xRot();
		velo$lastYRot = camera.yRot();
		velo$frameNanos = System.nanoTime();
	}

	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z"))
	private boolean velo$reuseVisibility(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher, net.minecraft.world.entity.Entity entity,
			net.minecraft.client.renderer.culling.Frustum frustum, double camX, double camY, double camZ, float partialTick, Operation<Boolean> original) {
		if ((entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb)
				&& AntiFarmLagModule.itemFarm()) {
			double dist = entity.distanceToSqr(camX, camY, camZ);
			if (dist > 16) {
				// Anti Farm Lag item farms: beyond 40 blocks an item is a pixel or two - skipped; closer ones are grouped, one
				// drawn per group with a count label (AntiFarmLagModule.itemCluster).
				if (dist > 40 * 40) {
					return false;
				}
				int stack = entity instanceof net.minecraft.world.entity.item.ItemEntity item ? item.getItem().getCount() : 1;
				boolean[] first = {false};
				AntiFarmLagModule.ItemCluster cluster = AntiFarmLagModule.itemCluster(entity.getX(), entity.getY(), entity.getZ(), dist, stack, first);
				if (!first[0] || !AntiFarmLagModule.takeItemBudget(dist)) {
					return false;
				}
				// the drawn one gets the full check (frustum, EntityCulling's occlusion), so no label shows through walls
				boolean shown = original.call(dispatcher, entity, frustum, camX, camY, camZ, partialTick);
				cluster.visible = shown;
				return shown;
			}
		}
		if (!PerformanceBoostModule.turbo || velo$camMoved || !(entity instanceof EntityVisCache cache)) {
			return original.call(dispatcher, entity, frustum, camX, camY, camZ, partialTick);
		}
		net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
		float xRot = camera.xRot();
		float yRot = camera.yRot();
		float fov = 0f;   // the field of view (zoom) isn't in the key: the 5 ms limit covers it
		double x = entity.getX();
		double y = entity.getY();
		double z = entity.getZ();
		long now = velo$frameNanos;
		long at = cache.velo$visTime();
		if (at != 0 && now - at < EntityVisCache.MAX_AGE_NANOS && cache.velo$visMatches(camX, camY, camZ, xRot, yRot, fov, x, y, z)) {
			return cache.velo$visResult();
		}
		boolean visible = original.call(dispatcher, entity, frustum, camX, camY, camZ, partialTick);
		cache.velo$visStore(now, camX, camY, camZ, xRot, yRot, fov, x, y, z, visible);
		return visible;
	}
}
*///?} else if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.extract.LevelExtractor.class)
public abstract class TurboEntityVisibilityMixin {

	// Once per frame: did the camera move since the last frame? Then no answer can be reused this frame, and the
	// per-entity compare + store (1.4% of every frame while moving) is skipped altogether.
	@org.spongepowered.asm.mixin.Unique
	private static double velo$lastX, velo$lastY, velo$lastZ;
	@org.spongepowered.asm.mixin.Unique
	private static float velo$lastXRot, velo$lastYRot;
	@org.spongepowered.asm.mixin.Unique
	private static boolean velo$camMoved = true;
	@org.spongepowered.asm.mixin.Unique
	private static long velo$frameNanos;

	@org.spongepowered.asm.mixin.injection.Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void velo$newItemFrame(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		AntiFarmLagModule.beginItemFrame();
		net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 pos = camera.position();
		velo$camMoved = pos.x != velo$lastX || pos.y != velo$lastY || pos.z != velo$lastZ
				|| camera.xRot() != velo$lastXRot || camera.yRot() != velo$lastYRot;
		velo$lastX = pos.x;
		velo$lastY = pos.y;
		velo$lastZ = pos.z;
		velo$lastXRot = camera.xRot();
		velo$lastYRot = camera.yRot();
		velo$frameNanos = System.nanoTime();
	}

	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
	private boolean velo$reuseVisibility(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher, net.minecraft.world.entity.Entity entity,
			net.minecraft.client.renderer.culling.Frustum frustum, double camX, double camY, double camZ, Operation<Boolean> original) {
		if ((entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb)
				&& AntiFarmLagModule.itemFarm()) {
			double dist = entity.distanceToSqr(camX, camY, camZ);
			if (dist > 16) {
				// Anti Farm Lag item farms: beyond 40 blocks an item is a pixel or two - skipped; closer ones are grouped, one
				// drawn per group with a count label (AntiFarmLagModule.itemCluster).
				if (dist > 40 * 40) {
					return false;
				}
				int stack = entity instanceof net.minecraft.world.entity.item.ItemEntity item ? item.getItem().getCount() : 1;
				boolean[] first = {false};
				AntiFarmLagModule.ItemCluster cluster = AntiFarmLagModule.itemCluster(entity.getX(), entity.getY(), entity.getZ(), dist, stack, first);
				if (!first[0] || !AntiFarmLagModule.takeItemBudget(dist)) {
					return false;
				}
				// the drawn one gets the full check (frustum, EntityCulling's occlusion), so no label shows through walls
				boolean shown = original.call(dispatcher, entity, frustum, camX, camY, camZ);
				cluster.visible = shown;
				return shown;
			}
		}
		if (!PerformanceBoostModule.turbo || velo$camMoved || !(entity instanceof EntityVisCache cache)) {
			return original.call(dispatcher, entity, frustum, camX, camY, camZ);
		}
		net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
		float xRot = camera.xRot();
		float yRot = camera.yRot();
		float fov = 0f;   // the field of view (zoom) isn't in the key: the 5 ms limit covers it
		double x = entity.getX();
		double y = entity.getY();
		double z = entity.getZ();
		long now = velo$frameNanos;
		long at = cache.velo$visTime();
		if (at != 0 && now - at < EntityVisCache.MAX_AGE_NANOS && cache.velo$visMatches(camX, camY, camZ, xRot, yRot, fov, x, y, z)) {
			return cache.velo$visResult();
		}
		boolean visible = original.call(dispatcher, entity, frustum, camX, camY, camZ);
		cache.velo$visStore(now, camX, camY, camZ, xRot, yRot, fov, x, y, z, visible);
		return visible;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboEntityVisibilityMixin {
	// Vulkan-era versions only (VeloMixinPlugin skips TurboEntity* elsewhere).
}
//?}
