package net.veloclient.velo.client.mixin;

import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hover inside scrolled/clipped areas under a scaled pose. Vanilla stores the scissor rect already transformed by
 * the pose but tests the untransformed mouse point against it, so in dense Velo windows (and while a window
 * animates) widgets in a scroll region lost their hover everywhere but near the top-left corner. Map the point
 * through the pose first - only in dense windows, where the mouse really is in pose units.
 */
@Mixin(DrawContext.class)
public abstract class ScissorHoverMixin {

	@Unique
	private boolean velo$mapping;

	//? if <26.1 {
	@Inject(method = "scissorContains", at = @At("HEAD"), cancellable = true)
	//?} else {
	/*@Inject(method = "containsPointInScissor", at = @At("HEAD"), cancellable = true)
	*///?}
	private void velo$mapThroughPose(int x, int y, CallbackInfoReturnable<Boolean> cir) {
		// Only dense Velo windows hand widgets the mouse in pose units; elsewhere (other mods' scaled UIs) the point is
		// already in screen units and mapping it again would break their hover.
		if (velo$mapping || !net.veloclient.velo.client.gui.window.DenseGui.active()) {
			return;
		}
		DrawContext self = (DrawContext) (Object) this;
		org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(self.getMatrices());
		if (pose.m00 == 1f && pose.m11 == 1f && pose.m01 == 0f && pose.m10 == 0f && pose.m20 == 0f && pose.m21 == 0f) {
			return;
		}
		org.joml.Vector2f point = pose.transformPosition(x, y, new org.joml.Vector2f());
		velo$mapping = true;
		try {
			//? if <26.1 {
			cir.setReturnValue(self.scissorContains(Math.round(point.x), Math.round(point.y)));
			//?} else {
			/*cir.setReturnValue(self.containsPointInScissor(Math.round(point.x), Math.round(point.y)));
			*///?}
		} finally {
			velo$mapping = false;
		}
	}
}
