package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchCommand=spawn}): profiling on a real server. Ten seconds after joining it sends that
 * command once (e.g. {@code /spawn}), ten seconds later MoveBench starts logging frame stats and the camera turns slowly
 * on the spot (30 degrees a second, no movement) so the whole spawn passes through view. Nothing else is sent.
 */
public final class ServerBench {

	private static int ticks;

	private ServerBench() {
	}

	public static void register() {
		String command = System.getProperty("velo.benchCommand");
		if (command == null || command.isBlank()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if >=26.2 {
			/*var player = client.player;
			if (player == null || client.getCurrentServer() == null) {
				ticks = 0;
				return;
			}
			ticks++;
			if (ticks == 200) {
				player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
				net.veloclient.velo.VeloClient.LOGGER.info("Velo server bench: sent /{}", command);
			}
			if (ticks == 260 || ticks == 330) {
				inspect(client, ticks == 260);
			}
			if (ticks == 460) {
				MoveBench.recordOnly();
			}
			if (ticks > 460) {
				player.setYRot(player.getYRot() + 1.5f);
			}
			// a picture every 3 s while turning (the same angles every run: the teleport sets the view) - to compare runs
			if (ticks > 460 && (ticks - 460) % 60 == 0 && ticks <= 460 + 60 * 8) {
				shot(client, "turn-" + (ticks - 460) / 60);
			}
			*///?}
		});
	}

	//? if >=26.2 {
	/*// Logs the open screen (title, every container slot with its item name), saves a screenshot and, with
	// -Dvelo.benchPick=<text>, clicks the first slot whose item name contains that text (e.g. a spawn selector).
	private static void inspect(net.minecraft.client.Minecraft client, boolean pick) {
		var screen = client.gui.screen();
		String tag = pick ? "after-command" : "after-pick";
		if (screen == null) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo server bench [{}]: no screen open", tag);
		} else {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo server bench [{}]: screen {} \"{}\"", tag, screen.getClass().getName(), screen.getTitle().getString());
			if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container) {
				var menu = container.getMenu();
				String want = System.getProperty("velo.benchPick", "").toLowerCase(java.util.Locale.ROOT);
				int picked = -1;
				for (int i = 0; i < menu.slots.size(); i++) {
					var slot = menu.slots.get(i);
					if (slot.container instanceof net.minecraft.world.entity.player.Inventory || !slot.hasItem()) {
						continue;
					}
					String name = slot.getItem().getHoverName().getString();
					net.veloclient.velo.VeloClient.LOGGER.info("Velo server bench   slot {}: {} x{}", i, name, slot.getItem().getCount());
					if (pick && picked < 0 && !want.isEmpty() && name.toLowerCase(java.util.Locale.ROOT).contains(want)) {
						picked = i;
					}
				}
				if (picked >= 0) {
					client.gameMode.handleContainerInput(menu.containerId, picked, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
					net.veloclient.velo.VeloClient.LOGGER.info("Velo server bench: clicked slot {}", picked);
				}
			}
		}
		shot(client, tag);
	}

	private static void shot(net.minecraft.client.Minecraft client, String tag) {
		String dir = System.getProperty("velo.benchShotDir");
		if (dir != null) {
			java.nio.file.Path file = java.nio.file.Path.of(dir, System.getProperty("velo.benchShotPrefix", "server-bench") + "-" + tag + ".png");
			net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				try (image) {
					image.writeToFile(file);
				} catch (Exception e) {
					net.veloclient.velo.VeloClient.LOGGER.warn("server bench screenshot failed", e);
				}
			});
		}
	}
	*///?}
}
