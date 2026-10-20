package net.veloclient.velo.client.mixin;

import net.minecraft.client.Mouse;
import net.veloclient.velo.client.gui.window.DenseGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mouse positions in a dense Velo window's units (see {@link DenseGui}). */
@Mixin(Mouse.class)
public abstract class DenseGuiMouseMixin {

	//? if <26.1 {
	@Inject(method = "getScaledX", at = @At("RETURN"), cancellable = true)
	private void velo$denseX(net.minecraft.client.util.Window window, CallbackInfoReturnable<Double> cir) {
		if (DenseGui.active()) {
			cir.setReturnValue(cir.getReturnValue() * DenseGui.factor());
		}
	}

	@Inject(method = "getScaledY", at = @At("RETURN"), cancellable = true)
	private void velo$denseY(net.minecraft.client.util.Window window, CallbackInfoReturnable<Double> cir) {
		if (DenseGui.active()) {
			cir.setReturnValue(cir.getReturnValue() * DenseGui.factor());
		}
	}
	//?} else {
	/*@Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("RETURN"), cancellable = true)
	private static void velo$denseX(com.mojang.blaze3d.platform.Window window, double x, CallbackInfoReturnable<Double> cir) {
		if (DenseGui.active()) {
			cir.setReturnValue(cir.getReturnValue() * DenseGui.factor());
		}
	}

	@Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("RETURN"), cancellable = true)
	private static void velo$denseY(com.mojang.blaze3d.platform.Window window, double y, CallbackInfoReturnable<Double> cir) {
		if (DenseGui.active()) {
			cir.setReturnValue(cir.getReturnValue() * DenseGui.factor());
		}
	}
	*///?}
}
