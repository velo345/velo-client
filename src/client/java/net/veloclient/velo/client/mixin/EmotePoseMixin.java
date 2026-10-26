package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.emotes.Emotes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Emotes: after vanilla posed the player model (walking, swinging...), a playing emote's pose is blended over the six
 * main parts - pivots and rotations. The hat, jacket, sleeves and trouser layers are their children and follow.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.model.PlayerEntityModel.class)
public abstract class EmotePoseMixin {

	@Unique
	private static final float[] POSE = new float[net.veloclient.velo.client.emotes.EmoteLibrary.FLOATS_PER_FRAME];

	@Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$emote(net.minecraft.client.render.entity.state.PlayerEntityRenderState state, CallbackInfo ci) {
		if (!Emotes.any()) {
			return;
		}
		float w;
		if (net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id)) {
			// the menu avatar (previews without a world) shows the preview
			w = Emotes.samplePreview(POSE);
		} else {
			var world = net.minecraft.client.MinecraftClient.getInstance().world;
			if (world == null || !(world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity player)) {
				return;
			}
			w = Emotes.sample(player.getUuid(), POSE);
		}
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
/*@Mixin(net.minecraft.client.model.player.PlayerModel.class)
public abstract class EmotePoseMixin {

	@Unique
	private static final float[] POSE = new float[net.veloclient.velo.client.emotes.EmoteLibrary.FLOATS_PER_FRAME];

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"), require = 0)
	private void velo$emote(net.minecraft.client.renderer.entity.state.AvatarRenderState state, CallbackInfo ci) {
		if (!Emotes.any()) {
			return;
		}
		float w;
		if (net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id)) {
			// the menu avatar (previews without a world) shows the preview
			w = Emotes.samplePreview(POSE);
		} else {
			var level = net.minecraft.client.Minecraft.getInstance().level;
			if (level == null || !(level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) {
				return;
			}
			w = Emotes.sample(player.getUUID(), POSE);
		}
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
