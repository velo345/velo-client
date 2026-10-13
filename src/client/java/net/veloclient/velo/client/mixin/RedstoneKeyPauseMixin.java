package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.redstone.RedstoneMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While Redstone Mode is on, every Velo keybind (modules and the Velo menu) reads as "not pressed" -
 * only Redstone Mode's own keys (separate category) keep working. Vanilla and other mods' keys are untouched.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.option.KeyBinding.class)
public abstract class RedstoneKeyPauseMixin {

	@Inject(method = "wasPressed", at = @At("HEAD"), cancellable = true)
	private void velo$pauseClick(CallbackInfoReturnable<Boolean> cir) {
		if (velo$paused()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
	private void velo$pauseHeld(CallbackInfoReturnable<Boolean> cir) {
		if (velo$paused()) {
			cir.setReturnValue(false);
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private boolean velo$paused() {
		if (!RedstoneMode.active()) {
			return false;
		}
		var self = (net.minecraft.client.option.KeyBinding) (Object) this;
		return self.getCategory() == VeloKeybinds.CATEGORY;
	}
}
//?} else {
/*@Mixin(net.minecraft.client.KeyMapping.class)
public abstract class RedstoneKeyPauseMixin {

	@Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
	private void velo$pauseClick(CallbackInfoReturnable<Boolean> cir) {
		if (velo$paused()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void velo$pauseHeld(CallbackInfoReturnable<Boolean> cir) {
		if (velo$paused()) {
			cir.setReturnValue(false);
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private boolean velo$paused() {
		if (!RedstoneMode.active()) {
			return false;
		}
		var self = (net.minecraft.client.KeyMapping) (Object) this;
		return self.getCategory() == VeloKeybinds.CATEGORY;
	}
}
*///?}
