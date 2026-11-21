package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

/**
 * Draws the Velo menu (R-Shift) once, invisibly, while the world is loading: the first menu of a session cost a 30-60
 * ms frame - every class it touches initialised, its code still running cold (not yet compiled by the JIT), its
 * fonts and icons loaded - while every later one opens in under a millisecond. The loading screen is a moment where
 * nobody notices a slower frame. Everything is clipped away (an empty scissor), so nothing shows; it happens once
 * per game start.
 */
public final class MenuWarmup {

	private static boolean done = Boolean.getBoolean("velo.noMenuWarmup");
	private static int framesSeen;

	private MenuWarmup() {
	}

	/** After each frame of the world-loading screen. */
	public static void afterLoadingFrame(Screen loading, DrawContext context) {
		if (done || ++framesSeen < 10) {
			return; // let the loading screen draw itself first
		}
		done = true;
		MinecraftClient client = MinecraftClient.getInstance();
		long started = System.nanoTime();
		net.veloclient.velo.client.gui.window.VeloWindow.warming = true;
		context.enableScissor(0, 0, 0, 0);
		try {
			Screen menu = net.veloclient.velo.client.gui.ModMenuScreen.forView(false);
			//? if <1.21.11 {
			/*menu.init(client, loading.width, loading.height);
			*///?} else {
			menu.init(loading.width, loading.height);
			//?}
			//? if <26.1 {
			menu.render(context, -10000, -10000, 0f);
			//?} else {
			/*menu.extractRenderState(context, -10000, -10000, 0f);
			*///?}
			menu.removed();
		} catch (Throwable t) {
			net.veloclient.velo.VeloClient.LOGGER.debug("Velo menu warm-up skipped", t);
		} finally {
			context.disableScissor();
			net.veloclient.velo.client.gui.window.VeloWindow.warming = false;
		}
		net.veloclient.velo.VeloClient.LOGGER.info("Velo menu warm-up: {} ms (on the loading screen)", (System.nanoTime() - started) / 1_000_000);
	}

	public static boolean isLoadingScreen(Screen screen) {
		//? if <1.21.2 {
		/*return screen instanceof net.minecraft.client.gui.screen.world.LevelLoadingScreen
				|| screen instanceof net.minecraft.client.gui.screen.DownloadingTerrainScreen;
		*///?} else if <26.1 {
		return screen instanceof net.minecraft.client.gui.screen.world.LevelLoadingScreen;
		//?} else {
		/*return screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
		*///?}
	}
}
