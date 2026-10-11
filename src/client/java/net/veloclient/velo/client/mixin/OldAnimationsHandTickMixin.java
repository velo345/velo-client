package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.OldAnimationsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.8 swing: since 1.9 the held item sinks after every attack and rises with the attack cooldown
 * (the hand-height target is the player's "item swap scale"). Reporting a full scale keeps the
 * hand up; swapping items still plays its own lower/raise animation.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.item.HeldItemRenderer.class)
public abstract class OldAnimationsHandTickMixin {

	@Redirect(method = "updateHeldItems", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/network/ClientPlayerEntity;getHandEquippingProgress(F)F"), require = 0)
	private float velo$noCooldownDrop(net.minecraft.client.network.ClientPlayerEntity player, float partialTick) {
		return OldAnimationsModule.noCooldownDrop() ? 1f : player.getHandEquippingProgress(partialTick);
	}
}
//?} else if <26.3 {
/*@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class OldAnimationsHandTickMixin {

	@Redirect(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"), require = 0)
	private float velo$noCooldownDrop(net.minecraft.client.player.LocalPlayer player, float partialTick) {
		return OldAnimationsModule.noCooldownDrop() ? 1f : player.getItemSwapScale(partialTick);
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.player.FirstPersonHandsAndItems.class)
public abstract class OldAnimationsHandTickMixin {

	@Redirect(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"), require = 0)
	private float velo$noCooldownDrop(net.minecraft.client.player.LocalPlayer player, float partialTick) {
		return OldAnimationsModule.noCooldownDrop() ? 1f : player.getItemSwapScale(partialTick);
	}
}
*///?}
