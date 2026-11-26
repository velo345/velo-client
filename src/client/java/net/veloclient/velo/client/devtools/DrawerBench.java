package net.veloclient.velo.client.devtools;

import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.gui.drawer.VeloDrawerScreen;

/**
 * Dev-only: {@code -Dvelo.drawerBench=true} - once in a world, FPS with no menu, with the R-Shift panel and with the full
 * menu (a few seconds each, logged as "[drawerBench]"), then quits. For finding what the menus cost (2026-11-26).
 */
public final class DrawerBench {

	private static int ticks = -1;
	private static final java.util.List<Integer> samples = new java.util.ArrayList<>();
	private static String phase = "";

	private DrawerBench() {
	}

	public static void register() {
		if (!Boolean.getBoolean("velo.drawerBench")) {
			return;
		}
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if <26.1 {
			if (client.player == null) {
				return;
			}
			int fps = client.getCurrentFps();
			//?} else {
			/*if (client.player == null) {
				return;
			}
			int fps = client.getFps();
			*///?}
			ticks++;
			if (ticks % 20 == 0 && !phase.isEmpty()) {
				samples.add(fps);
			}
			switch (ticks) {
				case 200 -> start("none");
				case 300 -> {
					report();
					client.setScreen(new VeloDrawerScreen());
				}
				case 330 -> start("panel");
				case 430 -> {
					report();
					VeloDrawerScreen.benchNoBlur = true;
				}
				case 450 -> start("panel-noblur");
				case 550 -> {
					report();
					VeloDrawerScreen.benchNoBlur = false;
					if (net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof VeloDrawerScreen drawer) {
						drawer.testExpanded(true);
					}
				}
				case 580 -> start("full");
				case 680 -> {
					report();
					client.setScreen(null);
				}
				case 720 -> {
					//? if >=26.1 {
					/*client.stop();
					*///?} else {
					client.scheduleStop();
					//?}
				}
				default -> {
				}
			}
		});
	}

	private static void start(String name) {
		phase = name;
		samples.clear();
	}

	private static void report() {
		double avg = samples.stream().mapToInt(Integer::intValue).average().orElse(0);
		VeloClient.LOGGER.info("[drawerBench] {}: {} fps (samples {})", phase, Math.round(avg), samples);
		phase = "";
	}
}
