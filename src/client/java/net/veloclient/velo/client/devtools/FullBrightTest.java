package net.veloclient.velo.client.devtools;

/**
 * Dev-only: {@code -Dvelo.fullBrightTest=true} - the Full Bright check on 1.21.x (the screenshot tour only captures on
 * 26.2): respawn, creative, midnight; shots with Full Bright off / on, eight frames in a row (flicker), then a closed
 * stone box (light 0) off / on / on again. Shots land in the run folder's screenshots/ as fbt-*.png; then it quits.
 */
public final class FullBrightTest {

	private FullBrightTest() {
	}

	public static void register() {
		if (!Boolean.getBoolean("velo.fullBrightTest")) {
			return;
		}
		//? if <26.1 {
		int[] ticks = {0};
		int[] menuTicks = {0};
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				// no world given (1.21.10 / 1.21.1 have no test save): make one like a player would - Create New World
				if (client.world == null && client.getOverlay() == null) {
					int m = ++menuTicks[0];
					if (m == 60 && client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen title) {
						//? if >=1.21.10 {
						net.minecraft.client.gui.screen.world.CreateWorldScreen.show(client, () -> client.setScreen(title));
						//?} else {
						/*net.minecraft.client.gui.screen.world.CreateWorldScreen.create(client, title);
						*///?}
					}
					if (m >= 120 && m % 40 == 0 && client.currentScreen instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen create) {
						String label = net.minecraft.client.resource.language.I18n.translate("selectWorld.create");
						for (var child : create.children()) {
							if (child instanceof net.minecraft.client.gui.widget.ButtonWidget button && button.getMessage().getString().equals(label)) {
								//? if >=1.21.10 {
								button.onPress(null);
								//?} else {
								/*button.onPress();
								*///?}
								break;
							}
						}
					}
				}
				return;
			}
			int t = ++ticks[0];
			if (t == 40 && client.player.isDead()) {
				client.player.requestRespawn();
			}
			if (t == 60) {
				full(false);
				commands(client, "gamemode creative @a", "effect clear @a", "time set 18000", "weather clear", "gamerule doDaylightCycle false");
			}
			if (t == 80) {
				client.inGameHud.getChatHud().clear(false);
			}
			if (t == 130) {
				shot(client, "fbt-night-off");
			}
			if (t == 131) {
				full(true);
			}
			if (t == 160) {
				shot(client, "fbt-night-on");
			}
			if (t >= 161 && t <= 168) {
				shot(client, "fbt-night-seq" + (t - 161));
			}
			if (t == 170) {
				commands(client, "execute as " + client.player.getGameProfile().name() + " at @s run fill ~-5 ~-1 ~-5 ~5 ~5 ~5 minecraft:stone hollow");
			}
			if (t == 190) {
				client.inGameHud.getChatHud().clear(false);
			}
			if (t == 230) {
				shot(client, "fbt-box-on");
			}
			if (t >= 231 && t <= 238) {
				shot(client, "fbt-box-seq" + (t - 231));
			}
			if (t == 239) {
				full(false);
			}
			if (t == 270) {
				shot(client, "fbt-box-off");
			}
			if (t == 271) {
				full(true);
			}
			if (t == 300) {
				shot(client, "fbt-box-on2");
			}
			if (t == 301) {
				full(false);
			}
			if (t == 330) {
				client.scheduleStop();
			}
		});
		//?}
	}

	private static void full(boolean on) {
		net.veloclient.velo.module.ModuleRegistry.get("full-bright").ifPresent(m -> m.setEnabled(on));
	}

	//? if <26.1 {
	private static void commands(net.minecraft.client.MinecraftClient client, String... commands) {
		var server = client.getServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			var source = server.getCommandSource();
			for (String command : commands) {
				//? if >=1.21.10 {
				server.getCommandManager().parseAndExecute(source, command);
				//?} else {
				/*server.getCommandManager().executeWithPrefix(source, command);
				*///?}
			}
		});
	}

	private static void shot(net.minecraft.client.MinecraftClient client, String name) {
		//? if >=1.21.10 {
		net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png", client.getFramebuffer(), 1, m -> { });
		//?} else {
		/*net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png", client.getFramebuffer(), m -> { });
		*///?}
	}
	//?}
}
