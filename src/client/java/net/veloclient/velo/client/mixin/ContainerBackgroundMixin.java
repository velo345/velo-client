package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.ContainerAnimationsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Container Animations, the window itself: on 26.x a container's texture is drawn in {@code extractBackground}, a pass
 * of its own before the rest of the screen, so it gets the same pop/slide as the slots and labels here. The dimmed
 * world behind it (Screen's own background) stays put. On 1.21.11 ContainerAnimationsMixin does this around
 * {@code drawBackground}; this class only exists there to keep the mixin list the same.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.Screen.class)
public abstract class ContainerBackgroundMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.Screen.class)
public abstract class ContainerBackgroundMixin {

	@Unique
	private boolean velo$windowPushed;
	@Unique
	private boolean velo$dimReset;

	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"), require = 0)
	private void velo$beginWindow(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		velo$windowPushed = (Object) this instanceof ContainerAnimationsModule.Window window && ContainerAnimationsModule.active()
				&& !ContainerAnimationsModule.closeDue(this) && window.velo$push(context);
	}

	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", shift = At.Shift.AFTER), require = 0)
	private void velo$endWindow(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$windowPushed) {
			context.pose().popMatrix();
			velo$windowPushed = false;
		}
	}

	// The container's extractBackground first calls this (blur + dim over the whole screen): that part isn't moved.
	@Inject(method = "extractBackground", at = @At("HEAD"), require = 0)
	private void velo$plainDim(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		velo$dimReset = velo$windowPushed;
		if (velo$dimReset) {
			context.pose().pushMatrix();
			context.pose().identity();
		}
	}

	@Inject(method = "extractBackground", at = @At("RETURN"), require = 0)
	private void velo$plainDimEnd(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$dimReset) {
			context.pose().popMatrix();
			velo$dimReset = false;
		}
	}
}
*///?}
