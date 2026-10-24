package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Menus outside a world (title screen, world list, server list) run at up to 120 FPS instead of
 * vanilla's fixed 60 - smoother menu animations and panorama on high-refresh screens, still well
 * below the uncapped in-game rate. Never above the player's own Max Framerate.
 */
//? if >=26.1 {
/*@Mixin(com.mojang.blaze3d.platform.FramerateLimitTracker.class)
public abstract class MenuFpsMixin {

	@Shadow
	private int framerateLimit;

	@ModifyConstant(method = "getFramerateLimit", constant = @Constant(intValue = 60), require = 0)
	private int velo$menuFps(int vanilla) {
		return Math.min(120, framerateLimit);
	}
}
*///?} else {
@Mixin(net.minecraft.client.option.InactivityFpsLimiter.class)
public abstract class MenuFpsMixin {

	@Shadow
	private int maxFps;

	@ModifyConstant(method = "update", constant = @Constant(intValue = 60), require = 0)
	private int velo$menuFps(int vanilla) {
		return Math.min(120, maxFps);
	}
}
//?}
