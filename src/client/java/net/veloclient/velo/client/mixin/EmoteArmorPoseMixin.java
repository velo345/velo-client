package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.emotes.Emotes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Emotes on armour: worn armour is drawn by its own humanoid models, posed from the same render state but never
 * through the player model - so during an emote the armour stayed in the walking pose (a chestplate floating where the
 * body used to be). This applies the same emote pose to those models; the player model itself is EmotePoseMixin's.
 */
//? if <1.21.2 {
/*// 1.21.1: armour is posed from the entity, not a render state - left out there.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class EmoteArmorPoseMixin {
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.entity.model.BipedEntityModel.class)
public abstract class EmoteArmorPoseMixin {

	@Unique
	private static final float[] POSE = new float[net.veloclient.velo.client.emotes.EmoteLibrary.FLOATS_PER_FRAME];

	@Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/BipedEntityRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$emoteArmor(net.minecraft.client.render.entity.state.BipedEntityRenderState state, CallbackInfo ci) {
		if (!Emotes.any() || (Object) this instanceof net.minecraft.client.render.entity.model.PlayerEntityModel
				|| !(state instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState playerState)) {
			return;
		}
		var world = net.minecraft.client.MinecraftClient.getInstance().world;
		if (world == null || !(world.getEntityById(playerState.id) instanceof net.minecraft.entity.player.PlayerEntity player)) {
			return;
		}
		float w = Emotes.sample(player.getUuid(), POSE);
		if (w <= 0f) {
			return;
		}
		var model = (net.minecraft.client.render.entity.model.BipedEntityModel<?>) (Object) this;
		net.minecraft.client.model.ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
		for (int i = 0; i < parts.length; i++) {
			var p = parts[i];
			int o = i * 6;
			p.originX += (POSE[o] - p.originX) * w;
			p.originY += (POSE[o + 1] - p.originY) * w;
			p.originZ += (POSE[o + 2] - p.originZ) * w;
			p.pitch = Emotes.blendAngle(p.pitch, POSE[o + 3], w);
			p.yaw = Emotes.blendAngle(p.yaw, POSE[o + 4], w);
			p.roll = Emotes.blendAngle(p.roll, POSE[o + 5], w);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.model.HumanoidModel.class)
public abstract class EmoteArmorPoseMixin {

	@Unique
	private static final float[] POSE = new float[net.veloclient.velo.client.emotes.EmoteLibrary.FLOATS_PER_FRAME];

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$emoteArmor(net.minecraft.client.renderer.entity.state.HumanoidRenderState state, CallbackInfo ci) {
		if (!Emotes.any() || (Object) this instanceof net.minecraft.client.model.player.PlayerModel
				|| !(state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatarState)) {
			return;
		}
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null || !(level.getEntity(avatarState.id) instanceof net.minecraft.world.entity.player.Player player)) {
			return;
		}
		float w = Emotes.sample(player.getUUID(), POSE);
		if (w <= 0f) {
			return;
		}
		var model = (net.minecraft.client.model.HumanoidModel<?>) (Object) this;
		net.minecraft.client.model.geom.ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
		for (int i = 0; i < parts.length; i++) {
			var p = parts[i];
			int o = i * 6;
			p.x += (POSE[o] - p.x) * w;
			p.y += (POSE[o + 1] - p.y) * w;
			p.z += (POSE[o + 2] - p.z) * w;
			p.xRot = Emotes.blendAngle(p.xRot, POSE[o + 3], w);
			p.yRot = Emotes.blendAngle(p.yRot, POSE[o + 4], w);
			p.zRot = Emotes.blendAngle(p.zRot, POSE[o + 5], w);
		}
	}
}
*///?}
