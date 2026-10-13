package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.redstone.RmInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands left/right/middle clicks to Redstone Mode's tools (selection, previews, wiring) instead of attacking / using. */
//? if <26.1 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RedstoneClickMixin {

	@Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
	private void velo$attack(CallbackInfoReturnable<Boolean> cir) {
		if (RmInput.onAttack()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
	private void velo$use(CallbackInfo ci) {
		if (RmInput.onUse()) {
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void velo$breaking(boolean breaking, CallbackInfo ci) {
		if (RmInput.blocksBreaking()) {
			ci.cancel();
		}
	}

	@Inject(method = "doItemPick", at = @At("HEAD"), cancellable = true)
	private void velo$pick(CallbackInfo ci) {
		if (RmInput.onPick()) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.Minecraft.class)
public abstract class RedstoneClickMixin {

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void velo$attack(CallbackInfoReturnable<Boolean> cir) {
		if (RmInput.onAttack()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void velo$use(CallbackInfo ci) {
		if (RmInput.onUse()) {
			ci.cancel();
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void velo$breaking(boolean breaking, CallbackInfo ci) {
		if (RmInput.blocksBreaking()) {
			ci.cancel();
		}
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void velo$pick(CallbackInfo ci) {
		if (RmInput.onPick()) {
			ci.cancel();
		}
	}
}
*///?}
