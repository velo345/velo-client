package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game captures (hides and traps) the mouse only while its window is really in front - see
 * {@link net.veloclient.velo.client.util.WindowFront}. A grab asked for while another window is in front waits and
 * happens as soon as the game comes to the front (or on the next click into it, as in vanilla).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.Mouse.class)
public abstract class WindowFrontGrabMixin {

	@Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
	private void velo$onlyInFront(CallbackInfo ci) {
		if (!net.veloclient.velo.client.util.WindowFront.inFront()) {
			net.veloclient.velo.client.util.WindowFront.grabWaiting = true;
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.MouseHandler.class)
public abstract class WindowFrontGrabMixin {

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void velo$onlyInFront(CallbackInfo ci) {
		if (!net.veloclient.velo.client.util.WindowFront.inFront()) {
			net.veloclient.velo.client.util.WindowFront.grabWaiting = true;
			ci.cancel();
		}
	}
}
*///?}
