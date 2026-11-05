package net.veloclient.velo.dev.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.dev.tweaks.ItemPhysicsModule;
import net.veloclient.velo.dev.tweaks.ItemPhysicsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics: remembers whether a dropped item is on the ground, and swaps vanilla's bob + spin for the module's pose
 * right before the item is drawn (the method pushed the pose stack first, so pop + push is back to the entity origin).
 */
//? if <1.21.2 {
/*// 1.21.1: this feature hooks a render system that came later - left out on 1.21.1.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class ItemPhysicsRendererMixin {
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.entity.ItemEntityRenderer.class)
public abstract class ItemPhysicsRendererMixin {

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void velo$grounded(net.minecraft.entity.ItemEntity entity, net.minecraft.client.render.entity.state.ItemEntityRenderState state,
			float tickDelta, CallbackInfo ci) {
		((ItemPhysicsState) state).velo$setGrounded(entity.isOnGround() || entity.isTouchingWater());
	}

	@WrapOperation(method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/ItemEntityRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/ItemStackEntityRenderState;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/Box;)V"),
			require = 0)
	private void velo$physics(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			int light, net.minecraft.client.render.entity.state.ItemStackEntityRenderState state, net.minecraft.util.math.random.Random random,
			net.minecraft.util.math.Box box, Operation<Void> original) {
		if (ItemPhysicsModule.active() && state instanceof net.minecraft.client.render.entity.state.ItemEntityRenderState item) {
			matrices.pop();
			matrices.push();
			matrices.multiplyPositionMatrix(ItemPhysicsModule.pose(((ItemPhysicsState) item).velo$grounded(), item.age, item.uniqueOffset, box,
					item.renderedAmount));
		}
		original.call(matrices, queue, light, state, random, box);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.ItemEntityRenderer.class)
public abstract class ItemPhysicsRendererMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void velo$grounded(net.minecraft.world.entity.item.ItemEntity entity, net.minecraft.client.renderer.entity.state.ItemEntityRenderState state,
			float partialTicks, CallbackInfo ci) {
		((ItemPhysicsState) state).velo$setGrounded(entity.onGround() || entity.isInWater());
	}

	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"),
			require = 0)
	private void velo$physics(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			int light, net.minecraft.client.renderer.entity.state.ItemClusterRenderState state, net.minecraft.util.RandomSource random,
			net.minecraft.world.phys.AABB box, Operation<Void> original) {
		if (ItemPhysicsModule.active() && state instanceof net.minecraft.client.renderer.entity.state.ItemEntityRenderState item) {
			pose.popPose();
			pose.pushPose();
			pose.mulPose(ItemPhysicsModule.pose(((ItemPhysicsState) item).velo$grounded(), item.ageInTicks, item.bobOffset, box, item.count));
		}
		original.call(pose, collector, light, state, random, box);
	}
}
*///?}
