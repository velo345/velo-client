package net.veloclient.velo.client.devtools;

import net.veloclient.velo.client.gui.drawer.VeloDrawerScreen;

/** -Dvelo.drawerTour=true: once in a world, opens the R-Shift drawer and saves screenshots of its states, then quits. */
public final class DrawerTour {

	private static int ticks = -1;
	private static VeloDrawerScreen screen;

	private DrawerTour() {
	}

	public static void register() {
		if (!Boolean.getBoolean("velo.drawerTour")) {
			return;
		}
		//? if >=1.21.10 && <26.1 {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				return;
			}
			ticks++;
			switch (ticks) {
				case 80 -> {
					screen = new VeloDrawerScreen();
					client.setScreen(screen);
					screen.testExpanded(false);
				}
				case 110 -> shot(client, "drawer-1-home.png");
				case 115 -> screen.testCategory("HUD");
				case 140 -> shot(client, "drawer-2-hud.png");
				case 145 -> screen.testModule("fps-counter");
				case 170 -> shot(client, "drawer-3-fps.png");
				case 175 -> screen.testModule("custom-crosshair");
				case 200 -> shot(client, "drawer-4-crosshair.png");
				case 205 -> {
					screen.testHome();
					screen.testProfileMenu(true);
				}
				case 230 -> shot(client, "drawer-5-profiles.png");
				case 235 -> {
					screen.testProfileMenu(false);
					screen.testExpanded(true);
				}
				case 239 -> shot(client, "drawer-6-morph.png");
				case 270 -> shot(client, "drawer-7-full.png");
				case 275 -> screen.testCategory("PvP");
				case 300 -> shot(client, "drawer-8-full-pvp.png");
				case 302 -> screen.testFeatures();
				case 325 -> shot(client, "drawer-9-full-features.png");
				case 330 -> screen.testModule("performance-boost");
				case 355 -> shot(client, "drawer-10-full-module.png");
				case 360 -> {
					screen.testExpanded(false);
					screen.testHome();
				}
				case 375 -> shot(client, "drawer-11-panel-after.png");
				case 380 -> screen.testOpen(false);
				case 405 -> shot(client, "drawer-12-collapsed.png");
				case 425 -> client.scheduleStop();
				default -> {
				}
			}
		});
		//?}
	}

	//? if >=1.21.10 && <26.1 {
	private static void shot(net.minecraft.client.MinecraftClient client, String name) {
		net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1, m -> { });
	}
	//?}
}
