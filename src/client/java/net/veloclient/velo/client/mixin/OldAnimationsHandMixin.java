package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.OldAnimationsModule;
import net.veloclient.velo.client.pvp.OldAnimTransforms;
import net.veloclient.velo.client.pvp.PvpCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person hooks for Old Animations: remember which item/hand is being drawn, hide the shield
 * while sword-blocking, and append the old poses right after vanilla's equip and swing transforms
 * (the same method names in every version, which keeps this stable across the 26.3 split).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.item.HeldItemRenderer.class)
public abstract class OldAnimationsHandMixin {

	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$begin(net.minecraft.client.network.AbstractClientPlayerEntity player, float tickProgress, float pitch,
			net.minecraft.util.Hand hand, float swingProgress, net.minecraft.item.ItemStack item, float equipProgress,
			net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			int light, CallbackInfo ci) {
		OldAnimTransforms.currentStack = item;
		OldAnimTransforms.currentIsMainHand = hand == net.minecraft.util.Hand.MAIN_HAND;
		if (hand == net.minecraft.util.Hand.OFF_HAND && OldAnimationsModule.hideShield() && PvpCompat.isShield(item)) {
			ci.cancel();
		}
	}

	@Inject(method = "applyEquipOffset", at = @At("TAIL"), require = 0)
	private void velo$afterEquip(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.util.Arm arm, float equipProgress, CallbackInfo ci) {
		OldAnimTransforms.afterEquip(matrices, arm == net.minecraft.util.Arm.RIGHT);
	}

	@Inject(method = "applySwingOffset", at = @At("TAIL"), require = 0)
	private void velo$afterSwing(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.util.Arm arm, float swingProgress, CallbackInfo ci) {
		OldAnimTransforms.afterSwing(matrices, arm == net.minecraft.util.Arm.RIGHT);
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class OldAnimationsHandMixin {

	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$begin(net.minecraft.client.player.AbstractClientPlayer player, float partialTick, float pitch,
			net.minecraft.world.InteractionHand hand, float swingProgress, net.minecraft.world.item.ItemStack item, float equipProgress,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, CallbackInfo ci) {
		OldAnimTransforms.currentStack = item;
		OldAnimTransforms.currentIsMainHand = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
		if (hand == net.minecraft.world.InteractionHand.OFF_HAND && OldAnimationsModule.hideShield() && PvpCompat.isShield(item)) {
			ci.cancel();
		}
	}

	@Inject(method = "applyItemArmTransform", at = @At("TAIL"), require = 0)
	private void velo$afterEquip(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.entity.HumanoidArm arm, float equipProgress, CallbackInfo ci) {
		OldAnimTransforms.afterEquip(pose, arm == net.minecraft.world.entity.HumanoidArm.RIGHT);
	}

	@Inject(method = "applyItemArmAttackTransform", at = @At("TAIL"), require = 0)
	private void velo$afterSwing(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.entity.HumanoidArm arm, float swingProgress, CallbackInfo ci) {
		OldAnimTransforms.afterSwing(pose, arm == net.minecraft.world.entity.HumanoidArm.RIGHT);
	}
}
*///?} else if <26.3 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class OldAnimationsHandMixin {

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$begin(net.minecraft.client.player.AbstractClientPlayer player, float partialTick, float pitch,
			net.minecraft.world.InteractionHand hand, float swingProgress, net.minecraft.world.item.ItemStack item, float equipProgress,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, CallbackInfo ci) {
		OldAnimTransforms.currentStack = item;
		OldAnimTransforms.currentIsMainHand = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
		if (hand == net.minecraft.world.InteractionHand.OFF_HAND && OldAnimationsModule.hideShield() && PvpCompat.isShield(item)) {
			ci.cancel();
		}
	}

	@Inject(method = "applyItemArmTransform", at = @At("TAIL"), require = 0)
	private void velo$afterEquip(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.entity.HumanoidArm arm, float equipProgress, CallbackInfo ci) {
		OldAnimTransforms.afterEquip(pose, arm == net.minecraft.world.entity.HumanoidArm.RIGHT);
	}

	@Inject(method = "applyItemArmAttackTransform", at = @At("TAIL"), require = 0)
	private void velo$afterSwing(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.entity.HumanoidArm arm, float swingProgress, CallbackInfo ci) {
		OldAnimTransforms.afterSwing(pose, arm == net.minecraft.world.entity.HumanoidArm.RIGHT);
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
public abstract class OldAnimationsHandMixin {

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$begin(net.minecraft.client.renderer.state.level.PlayerRenderState player,
			net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState hands, float partialTick, float pitch,
			net.minecraft.world.InteractionHand hand, float swingProgress, net.minecraft.world.item.ItemStack item, float equipProgress,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, CallbackInfo ci) {
		OldAnimTransforms.currentStack = item;
		OldAnimTransforms.currentIsMainHand = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
		if (hand == net.minecraft.world.InteractionHand.OFF_HAND && OldAnimationsModule.hideShield() && PvpCompat.isShield(item)) {
			ci.cancel();
		}
	}

	@Inject(method = "applyItemArmTransform", at = @At("TAIL"), require = 0)
	private void velo$afterEquip(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.entity.HumanoidArm arm, float equipProgress, CallbackInfo ci) {
		OldAnimTransforms.afterEquip(pose, arm == net.minecraft.world.entity.HumanoidArm.RIGHT);
	}

	@Inject(method = "applyItemArmAttackTransform", at = @At("TAIL"), require = 0)
	private void velo$afterSwing(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.entity.HumanoidArm arm, float swingProgress, CallbackInfo ci) {
		OldAnimTransforms.afterSwing(pose, arm == net.minecraft.world.entity.HumanoidArm.RIGHT);
	}
}
*///?}
