package net.veloclient.velo.client.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A GUI clip region can end up 0 pixels tall or wide (a list squeezed to nothing at a small window /
 * high GUI scale, or nested clips that don't overlap). Vanilla still submits text inside it with that
 * scissor, and RenderPass then throws "Scissor size must be >0" - a hard crash. An empty clip should
 * simply show nothing, so it's replaced by a 1x1 scissor far outside the framebuffer.
 */
@Mixin(net.minecraft.client.gui.render.GuiRenderer.class)
public abstract class EmptyScissorGuardMixin {

	//? if <26.1 {
	@Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
	private void velo$guardEmptyScissor(net.minecraft.client.gui.ScreenRect area, RenderPass pass, CallbackInfo ci) {
		if (area.width() <= 0 || area.height() <= 0) {
			pass.enableScissor(100_000, 100_000, 1, 1);
			ci.cancel();
		}
	}
	//?} else {
	/*@Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
	private void velo$guardEmptyScissor(net.minecraft.client.gui.navigation.ScreenRectangle area, RenderPass pass, CallbackInfo ci) {
		if (area.width() <= 0 || area.height() <= 0) {
			pass.enableScissor(100_000, 100_000, 1, 1);
			ci.cancel();
		}
	}
	*///?}
}
