package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.pvp.NameTagLines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Draws {@link NameTagLines} (Health Indicator, Enemy Effects, Enemy Hunger, Damage Numbers) above
 * other players, styled like vanilla name tags. Hooked at the end of the base entity renderer's
 * submit - which every player passes through after its model, with the pose back at the player -
 * NOT on the vanilla name tag: PvP servers usually hide vanilla name tags and draw names with
 * their own floating text, and these lines used to vanish along with the hidden tag. Without a
 * vanilla tag the lines sit higher ({@link NameTagLines#extraLines}) to clear the server's text.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.EntityRenderer.class)
public abstract class NameTagLinesMixin {

	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("RETURN"), require = 0)
	private void velo$extraLines(net.minecraft.client.render.entity.state.EntityRenderState renderState,
			net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			net.minecraft.client.render.state.CameraRenderState camera, CallbackInfo ci) {
		if (!(renderState instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState state)
				|| state.invisible || state.squaredDistanceToCamera > 4096) {
			return;
		}
		boolean vanillaTag = state.displayName != null;
		net.veloclient.velo.client.pvp.ServerHealth.observe(state.id, vanillaTag ? state.displayName.getString() : null,
				state.playerName == null ? null : state.playerName.getString());
		List<Object> lines = NameTagLines.lines(state.id, state.playerName != null);
		if (lines.isEmpty()) {
			return;
		}
		var anchor = state.nameLabelPos != null ? state.nameLabelPos : new net.minecraft.util.math.Vec3d(0, state.height, 0);
		int base = (vanillaTag ? 1 : NameTagLines.extraLines()) + (state.playerName != null ? 1 : 0);
		for (int i = 0; i < lines.size(); i++) {
			queue.submitLabel(matrices, anchor, -Math.round((base + i) * 10.35f), (net.minecraft.text.Text) lines.get(i),
					!state.sneaking, state.light, state.squaredDistanceToCamera, camera);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class NameTagLinesMixin {

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("RETURN"), require = 0)
	private void velo$extraLines(net.minecraft.client.renderer.entity.state.EntityRenderState renderState,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		if (!(renderState instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState state)
				|| state.isInvisible || state.distanceToCameraSq > 4096) {
			return;
		}
		boolean vanillaTag = state.nameTag != null;
		net.veloclient.velo.client.pvp.ServerHealth.observe(state.id, vanillaTag ? state.nameTag.getString() : null,
				state.scoreText == null ? null : state.scoreText.getString());
		List<Object> lines = NameTagLines.lines(state.id, state.scoreText != null);
		if (lines.isEmpty()) {
			return;
		}
		var anchor = state.nameTagAttachment != null ? state.nameTagAttachment : new net.minecraft.world.phys.Vec3(0, state.boundingBoxHeight, 0);
		int base = (vanillaTag ? 1 : NameTagLines.extraLines()) + (state.scoreText != null ? 1 : 0);
		for (int i = 0; i < lines.size(); i++) {
			collector.submitNameTag(pose, anchor, -Math.round((base + i) * 10.35f),
					(net.minecraft.network.chat.Component) lines.get(i), !state.isDiscrete, state.lightCoords, state.distanceToCameraSq, camera);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class NameTagLinesMixin {

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("RETURN"), require = 0)
	private void velo$extraLines(net.minecraft.client.renderer.entity.state.EntityRenderState renderState,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		if (!(renderState instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState state)
				|| state.isInvisible || state.distanceToCameraSq > 4096) {
			return;
		}
		boolean vanillaTag = state.nameTag != null;
		net.veloclient.velo.client.pvp.ServerHealth.observe(state.id, vanillaTag ? state.nameTag.getString() : null,
				state.scoreText == null ? null : state.scoreText.getString());
		List<Object> lines = NameTagLines.lines(state.id, state.scoreText != null);
		if (lines.isEmpty()) {
			return;
		}
		var anchor = state.nameTagAttachment != null ? state.nameTagAttachment : new net.minecraft.world.phys.Vec3(0, state.boundingBoxHeight, 0);
		int base = (vanillaTag ? 1 : NameTagLines.extraLines()) + (state.scoreText != null ? 1 : 0);
		for (int i = 0; i < lines.size(); i++) {
			collector.submitNameTag(pose, anchor, -Math.round((base + i) * 10.35f),
					(net.minecraft.network.chat.Component) lines.get(i), !state.isDiscrete, state.lightCoords, camera);
		}
	}
}
*///?}
