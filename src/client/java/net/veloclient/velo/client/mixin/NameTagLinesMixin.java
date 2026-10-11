package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.pvp.NameTagLines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Draws {@link NameTagLines} (Health Indicator, Enemy Effects, Enemy Hunger) above other players'
 * name tags, each as its own vanilla-style tag. Hooked at the end of the player renderer's own
 * name tag method, where the pose is back at the tag anchor; only runs when the name tag itself is
 * shown, so invisible/hidden players stay hidden.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.PlayerEntityRenderer.class)
public abstract class NameTagLinesMixin {

	@Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("TAIL"), require = 0)
	private void velo$extraLines(net.minecraft.client.render.entity.state.PlayerEntityRenderState state,
			net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			net.minecraft.client.render.state.CameraRenderState camera, CallbackInfo ci) {
		if (state.displayName == null || state.nameLabelPos == null) {
			return;
		}
		net.veloclient.velo.client.pvp.ServerHealth.observe(state.id, state.displayName.getString(),
				state.playerName == null ? null : state.playerName.getString());
		List<Object> lines = NameTagLines.lines(state.id, state.playerName != null);
		int base = state.playerName != null ? 2 : 1;
		for (int i = 0; i < lines.size(); i++) {
			queue.submitLabel(matrices, state.nameLabelPos, -Math.round((base + i) * 10.35f), (net.minecraft.text.Text) lines.get(i),
					!state.sneaking, state.light, state.squaredDistanceToCamera, camera);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.entity.player.AvatarRenderer.class)
public abstract class NameTagLinesMixin {

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("TAIL"), require = 0)
	private void velo$extraLines(net.minecraft.client.renderer.entity.state.AvatarRenderState state,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		if (state.nameTag == null || state.nameTagAttachment == null) {
			return;
		}
		net.veloclient.velo.client.pvp.ServerHealth.observe(state.id, state.nameTag.getString(),
				state.scoreText == null ? null : state.scoreText.getString());
		List<Object> lines = NameTagLines.lines(state.id, state.scoreText != null);
		int base = state.scoreText != null ? 2 : 1;
		for (int i = 0; i < lines.size(); i++) {
			collector.submitNameTag(pose, state.nameTagAttachment, -Math.round((base + i) * 10.35f),
					(net.minecraft.network.chat.Component) lines.get(i), !state.isDiscrete, state.lightCoords, state.distanceToCameraSq, camera);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.entity.player.AvatarRenderer.class)
public abstract class NameTagLinesMixin {

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("TAIL"), require = 0)
	private void velo$extraLines(net.minecraft.client.renderer.entity.state.AvatarRenderState state,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		if (state.nameTag == null || state.nameTagAttachment == null) {
			return;
		}
		net.veloclient.velo.client.pvp.ServerHealth.observe(state.id, state.nameTag.getString(),
				state.scoreText == null ? null : state.scoreText.getString());
		List<Object> lines = NameTagLines.lines(state.id, state.scoreText != null);
		int base = state.scoreText != null ? 2 : 1;
		for (int i = 0; i < lines.size(); i++) {
			collector.submitNameTag(pose, state.nameTagAttachment, -Math.round((base + i) * 10.35f),
					(net.minecraft.network.chat.Component) lines.get(i), !state.isDiscrete, state.lightCoords, camera);
		}
	}
}
*///?}
