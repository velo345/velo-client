package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.HoldUpItemsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hold Up Items: after vanilla posed the player model, the item arm (or both) is held straight out in front. */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.model.PlayerEntityModel.class)
public abstract class HoldUpItemsPoseMixin {

	@Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$holdUp(net.minecraft.client.render.entity.state.PlayerEntityRenderState state, CallbackInfo ci) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world == null || !(client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity player)) {
			return;
		}
		boolean self = player == client.player;
		boolean rightMain = player.getMainArm() == net.minecraft.util.Arm.RIGHT;
		boolean mainItem = !player.getMainHandStack().isEmpty();
		boolean offItem = !player.getOffHandStack().isEmpty();
		float head = (float) Math.toRadians(player.getPitch());
		var model = (net.minecraft.client.render.entity.model.BipedEntityModel<?>) (Object) this;
		float r = HoldUpItemsModule.armPitch(self, rightMain, rightMain ? mainItem : offItem, mainItem || offItem, head, player.handSwinging);
		float l = HoldUpItemsModule.armPitch(self, !rightMain, rightMain ? offItem : mainItem, mainItem || offItem, head, player.handSwinging);
		if (!Float.isNaN(r)) {
			model.rightArm.pitch = r;
			model.rightArm.yaw = -0.08f;
			model.rightArm.roll = 0f;
		}
		if (!Float.isNaN(l)) {
			model.leftArm.pitch = l;
			model.leftArm.yaw = 0.08f;
			model.leftArm.roll = 0f;
		}
	}
}
//?} else if <26.3 {
/*@Mixin(net.minecraft.client.model.player.PlayerModel.class)
public abstract class HoldUpItemsPoseMixin {

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$holdUp(net.minecraft.client.renderer.entity.state.AvatarRenderState state, CallbackInfo ci) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null || !(client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) {
			return;
		}
		boolean self = player == client.player;
		boolean rightMain = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		boolean mainItem = !player.getMainHandItem().isEmpty();
		boolean offItem = !player.getOffhandItem().isEmpty();
		float head = (float) Math.toRadians(player.getXRot());
		var model = (net.minecraft.client.model.HumanoidModel<?>) (Object) this;
		boolean swinging = player.swinging;
		float r = HoldUpItemsModule.armPitch(self, rightMain, rightMain ? mainItem : offItem, mainItem || offItem, head, swinging);
		float l = HoldUpItemsModule.armPitch(self, !rightMain, rightMain ? offItem : mainItem, mainItem || offItem, head, swinging);
		if (!Float.isNaN(r)) {
			model.rightArm.xRot = r;
			model.rightArm.yRot = -0.08f;
			model.rightArm.zRot = 0f;
		}
		if (!Float.isNaN(l)) {
			model.leftArm.xRot = l;
			model.leftArm.yRot = 0.08f;
			model.leftArm.zRot = 0f;
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.model.player.PlayerModel.class)
public abstract class HoldUpItemsPoseMixin {

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$holdUp(net.minecraft.client.renderer.entity.state.AvatarRenderState state, CallbackInfo ci) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null || !(client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) {
			return;
		}
		boolean self = player == client.player;
		boolean rightMain = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		boolean mainItem = !player.getMainHandItem().isEmpty();
		boolean offItem = !player.getOffhandItem().isEmpty();
		float head = (float) Math.toRadians(player.getXRot());
		var model = (net.minecraft.client.model.HumanoidModel<?>) (Object) this;
		boolean swinging = player.isSwinging();
		float r = HoldUpItemsModule.armPitch(self, rightMain, rightMain ? mainItem : offItem, mainItem || offItem, head, swinging);
		float l = HoldUpItemsModule.armPitch(self, !rightMain, rightMain ? offItem : mainItem, mainItem || offItem, head, swinging);
		if (!Float.isNaN(r)) {
			model.rightArm.xRot = r;
			model.rightArm.yRot = -0.08f;
			model.rightArm.zRot = 0f;
		}
		if (!Float.isNaN(l)) {
			model.leftArm.xRot = l;
			model.leftArm.yRot = 0.08f;
			model.leftArm.zRot = 0f;
		}
	}
}
*///?}
