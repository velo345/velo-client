package net.veloclient.velo.client.util;

/** The menus' frame cap: 120, or the monitor's refresh rate if that's lower (at least vanilla's 60). */
public final class MenuFps {

	private static long checkedAt;
	private static int cap = 120;

	private MenuFps() {
	}

	public static int cap() {
		long now = System.currentTimeMillis();
		if (now - checkedAt > 2000) {   // the window can move to another monitor
			checkedAt = now;
			try {
				int refresh = refreshRate();
				cap = refresh >= 30 ? Math.max(60, Math.min(120, refresh)) : 120;
			} catch (Throwable ignored) {
				cap = 120;
			}
		}
		return cap;
	}

	/**
	 * The Escape menu is open over a world. Vanilla doesn't cap that at all (only menus outside a world), so it drew the
	 * world and its blur as fast as it could - uneven pacing on high-FPS PCs, and a hot iGPU (players called it laggy).
	 */
	public static boolean pauseMenuOpen() {
		//? if <26.1 {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		return client.world != null && client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen;
		//?} else if <26.2 {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		return client.level != null && client.screen instanceof net.minecraft.client.gui.screens.PauseScreen;
		*///?} else {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		return client.level != null && client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen;
		*///?}
	}

	private static int refreshRate() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().getWindow().getRefreshRate();
		//?} else if <26.3 {
		/*return net.minecraft.client.Minecraft.getInstance().getWindow().getRefreshRate();
		*///?} else {
		/*return Math.round(net.minecraft.client.Minecraft.getInstance().getWindow().getActiveVideoMode().getRefreshRate());
		*///?}
	}
}
