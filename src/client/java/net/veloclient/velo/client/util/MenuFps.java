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
