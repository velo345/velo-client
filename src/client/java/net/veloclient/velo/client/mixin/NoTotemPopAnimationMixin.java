package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.NoTotemPopModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Totem Pop: the totem that flies into your face. Only totems - other items shown this way stay. */
//? if <26.1 {
@Mixin(net.minecraft.client.render.GameRenderer.class)
public abstract class NoTotemPopAnimationMixin {

	@Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noTotem(net.minecraft.item.ItemStack stack, CallbackInfo ci) {
		if (NoTotemPopModule.hideAnimation() && stack != null && stack.isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)) {
			ci.cancel();
		}
	}
}
//?} else if >=26.3 {
/*// 26.3 moved the floating item into its own class (GameRenderer#displayItemActivation is gone).
@Mixin(net.minecraft.client.player.ItemActivation.class)
public abstract class NoTotemPopAnimationMixin {

	@Inject(method = "activate", at = @At("HEAD"), cancellable = true)
	private void velo$noTotem(net.minecraft.world.item.ItemStack stack, net.minecraft.util.RandomSource random, CallbackInfo ci) {
		if (NoTotemPopModule.hideAnimation() && stack != null && stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
			ci.cancel();
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class NoTotemPopAnimationMixin {

	@Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noTotem(net.minecraft.world.item.ItemStack stack, CallbackInfo ci) {
		if (NoTotemPopModule.hideAnimation() && stack != null && stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
			ci.cancel();
		}
	}
}
*///?}
