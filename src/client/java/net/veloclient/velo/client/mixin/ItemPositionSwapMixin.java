package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.ItemPositionModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Item Position's "swap hands": the first-person hand/item renderer reads the main arm from here. Visual only. */
//? if <26.1 {
@Mixin(net.minecraft.client.render.item.HeldItemRenderer.class)
public abstract class ItemPositionSwapMixin {

	@Redirect(method = "renderFirstPersonItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;getMainArm()Lnet/minecraft/util/Arm;"), require = 0)
	private net.minecraft.util.Arm velo$swap(net.minecraft.client.network.AbstractClientPlayerEntity player) {
		return ItemPositionModule.arm(player.getMainArm());
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class ItemPositionSwapMixin {

	@Redirect(method = "renderArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;getMainArm()Lnet/minecraft/world/entity/HumanoidArm;"), require = 0)
	private net.minecraft.world.entity.HumanoidArm velo$swap(net.minecraft.client.player.AbstractClientPlayer player) {
		return ItemPositionModule.arm(player.getMainArm());
	}
}
*///?} else if <26.3 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class ItemPositionSwapMixin {

	@Redirect(method = "submitArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;getMainArm()Lnet/minecraft/world/entity/HumanoidArm;"), require = 0)
	private net.minecraft.world.entity.HumanoidArm velo$swap(net.minecraft.client.player.AbstractClientPlayer player) {
		return ItemPositionModule.arm(player.getMainArm());
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemPositionSwapMixin {

	@Redirect(method = "submitArmWithItem", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;mainArm:Lnet/minecraft/world/entity/HumanoidArm;"), require = 0)
	private net.minecraft.world.entity.HumanoidArm velo$swap(net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		return ItemPositionModule.arm(state.mainArm);
	}
}
*///?}
