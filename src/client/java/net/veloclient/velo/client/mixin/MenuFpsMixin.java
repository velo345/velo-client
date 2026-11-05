package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Menus outside a world (title screen, world list, server list) run at up to 120 FPS instead of
 * vanilla's fixed 60 - smoother menu animations and panorama on high-refresh screens, still well
 * below the uncapped in-game rate. Never above the player's own Max Framerate, and never above the
 * monitor's refresh rate: 120 frames on a 75 Hz screen only paced unevenly (menus felt laggy).
 */
//? if >=26.1 {
/*@Mixin(com.mojang.blaze3d.platform.FramerateLimitTracker.class)
public abstract class MenuFpsMixin {

	@Shadow
	private int framerateLimit;

	@ModifyConstant(method = "getFramerateLimit", constant = @Constant(intValue = 60), require = 0)
	private int velo$menuFps(int vanilla) {
		return Math.min(net.veloclient.velo.client.util.MenuFps.cap(), framerateLimit);
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "getFramerateLimit", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true, require = 0)
	private void velo$pauseMenuFps(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
		if (net.veloclient.velo.client.util.MenuFps.pauseMenuOpen()) {
			cir.setReturnValue(Math.min(cir.getReturnValue(), net.veloclient.velo.client.util.MenuFps.cap()));
		}
	}
}
*///?} else if >=1.21.2 {
@Mixin(net.minecraft.client.option.InactivityFpsLimiter.class)
public abstract class MenuFpsMixin {

	@Shadow
	private int maxFps;

	@ModifyConstant(method = "update", constant = @Constant(intValue = 60), require = 0)
	private int velo$menuFps(int vanilla) {
		return Math.min(net.veloclient.velo.client.util.MenuFps.cap(), maxFps);
	}

	/** The Escape menu over a world: capped like the other menus (see MenuFps.pauseMenuOpen). */
	@org.spongepowered.asm.mixin.injection.Inject(method = "update", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true, require = 0)
	private void velo$pauseMenuFps(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
		if (net.veloclient.velo.client.util.MenuFps.pauseMenuOpen()) {
			cir.setReturnValue(Math.min(cir.getReturnValue(), net.veloclient.velo.client.util.MenuFps.cap()));
		}
	}
}
//?} else {
/*// 1.21.1: hooks a system that came later - left out on 1.21.1.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class MenuFpsMixin {
}
*///?}
