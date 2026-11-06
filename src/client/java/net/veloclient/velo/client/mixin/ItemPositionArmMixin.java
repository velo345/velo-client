package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.ItemPositionModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Position for the empty hand: vanilla draws a bare arm through its own method (no equip transform), so the
 * main hand never moved without an item. The offset is applied before the arm's own transforms.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.item.HeldItemRenderer.class)
public abstract class ItemPositionArmMixin {

	@Inject(method = "renderArmHoldingItem", at = @At("HEAD"), require = 0)
	private void velo$armOffset(net.minecraft.client.util.math.MatrixStack matrices,
			//? if <1.21.2 {
			/*net.minecraft.client.render.VertexConsumerProvider queue,
			*///?} else {
			net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			//?}
			int light, float equipProgress, float swingProgress, net.minecraft.util.Arm arm, CallbackInfo ci) {
		float[] o = ItemPositionModule.armOffset(true);
		if (o != null) {
			matrices.translate(o[0] * (arm == net.minecraft.util.Arm.RIGHT ? 1 : -1), o[1], o[2]);
		}
	}
}
//?} else if <26.3 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class ItemPositionArmMixin {

	@Inject(method = "renderPlayerArm", at = @At("HEAD"), require = 0)
	private void velo$armOffset(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			int light, float equipProgress, float swingProgress, net.minecraft.world.entity.HumanoidArm arm, CallbackInfo ci) {
		float[] o = ItemPositionModule.armOffset(true);
		if (o != null) {
			pose.translate(o[0] * (arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1), o[1], o[2]);
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemPositionArmMixin {

	@Inject(method = "renderPlayerArm", at = @At("HEAD"), require = 0)
	private void velo$armOffset(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			int light, float equipProgress, float swingProgress, net.minecraft.world.entity.HumanoidArm arm,
			net.minecraft.client.renderer.state.level.PlayerRenderState player, CallbackInfo ci) {
		float[] o = ItemPositionModule.armOffset(true);
		if (o != null) {
			pose.translate(o[0] * (arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1), o[1], o[2]);
		}
	}
}
*///?}
