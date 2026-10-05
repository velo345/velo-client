package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.debug.BetterF3Module;
import net.veloclient.velo.module.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Better F3 is on, the open F3 screen is drawn by it instead of vanilla. */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.DebugHud.class)
public abstract class DebugHudMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void velo$betterF3(net.minecraft.client.gui.DrawContext context, CallbackInfo ci) {
		if (!((net.minecraft.client.gui.hud.DebugHud) (Object) this).shouldShowDebugHud()) {
			return;
		}
		if (ModuleRegistry.get("better-f3").orElse(null) instanceof BetterF3Module module && module.isEnabled()) {
			module.render(context);
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.DebugScreenOverlay.class)
public abstract class DebugHudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void velo$betterF3(net.minecraft.client.gui.GuiGraphicsExtractor context, CallbackInfo ci) {
		if (!((net.minecraft.client.gui.components.DebugScreenOverlay) (Object) this).showDebugScreen()) {
			return;
		}
		if (ModuleRegistry.get("better-f3").orElse(null) instanceof BetterF3Module module && module.isEnabled()) {
			module.render(context);
			ci.cancel();
		}
	}
}
*///?}
