package net.veloclient.velo.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.veloclient.velo.client.redstone.RedstoneMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While Redstone Mode's tool slot is selected the first-person hand is empty, like Axiom's tool
 * slot: the item of the real hotbar slot underneath stays in the inventory but isn't shown in hand.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.item.HeldItemRenderer.class)
public abstract class ToolSlotHandMixin {

	@ModifyExpressionValue(method = "updateHeldItems", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/network/ClientPlayerEntity;getMainHandStack()Lnet/minecraft/item/ItemStack;"), require = 0)
	private net.minecraft.item.ItemStack velo$emptyHand(net.minecraft.item.ItemStack original) {
		return RedstoneMode.toolHeld() ? net.minecraft.item.ItemStack.EMPTY : original;
	}
}
//?} else if <26.3 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class ToolSlotHandMixin {

	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"), require = 0)
	private net.minecraft.world.item.ItemStack velo$emptyHand(net.minecraft.world.item.ItemStack original) {
		return RedstoneMode.toolHeld() ? net.minecraft.world.item.ItemStack.EMPTY : original;
	}

	// Dev-only (screenshot tour, -Dvelo.tourNoHand): no bare arm in front of promo footage.
	@org.spongepowered.asm.mixin.injection.Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$tourNoArm(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (NO_ARM) {
			ci.cancel();
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static final boolean NO_ARM = System.getProperty("velo.tourNoHand") != null;
}
*///?} else {
/*@Mixin(net.minecraft.client.player.FirstPersonHandsAndItems.class)
public abstract class ToolSlotHandMixin {

	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"), require = 0)
	private net.minecraft.world.item.ItemStack velo$emptyHand(net.minecraft.world.item.ItemStack original) {
		return RedstoneMode.toolHeld() ? net.minecraft.world.item.ItemStack.EMPTY : original;
	}
}
*///?}
