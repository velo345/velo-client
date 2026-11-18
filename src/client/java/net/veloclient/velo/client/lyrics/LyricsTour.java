package net.veloclient.velo.client.lyrics;

//? if >=26.2 {
/*import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/^*
 * Dev-only: {@code -Dvelo.lyricsTour=<folder> -Dvelo.lyricsTour.world=<save> [-Dvelo.lyricsTour.presets=A,B|all] [-Dvelo.lyricsTour.solid=1]}
 * opens the world, shows each preset's demo lines and saves a screenshot per preset (26.2). Does nothing without the property.
 ^/
public final class LyricsTour {

	private static Path folder;
	private static int ticks;
	private static int bootTicks;
	private static boolean opened;
	private static List<String> queue;
	private static int index;
	private static int stage;
	private static long stageAt;

	private LyricsTour() {
	}

	public static void initIfRequested() {
		String target = System.getProperty("velo.lyricsTour");
		if (target == null || target.isBlank()) {
			return;
		}
		folder = Path.of(target);
		ClientTickEvents.END_CLIENT_TICK.registerTexture(LyricsTour::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			if (!opened && ++bootTicks == 160) {
				opened = true;
				client.createWorldOpenFlows().openWorld(System.getProperty("velo.lyricsTour.world"), () -> { });
			}
			return;
		}
		ticks++;
		if (ticks < 120) {
			return;
		}
		if (queue == null) {
			queue = new ArrayList<>();
			String list = System.getProperty("velo.lyricsTour.presets", "all");
			if ("all".equals(list)) {
				queue.addAll(LyricsStyle.PRESETS.keySet());
			} else {
				queue.addAll(List.of(list.split(",")));
			}
			command(client, "gamemode spectator @a");
			command(client, "tp @a 0 130 0 0 -4");
			LyricsModule.studioOpen = true;
		}
		if (index >= queue.size()) {
			LyricsModule.studioOpen = false;
			client.stop();
			return;
		}
		String name = queue.get(index).replace('_', ' ');
		long demo = LyricsSync.now(true, 0) % 15000;
		long waited = ticks - stageAt;
		if (stage == 0) {
			command(client, "tp @a 0 130 0 0 -4");
			stage = 1;
			stageAt = ticks;
		} else if (stage == 1 && waited > 40) {
			LyricsStyle s = LyricsStyle.PRESETS.getOrDefault(name, new LyricsStyle()).copy();
			if (System.getProperty("velo.lyricsTour.solid") != null) {
				s.text3d = true;
				s.followView = false;
			}
			LyricsModule.style = s;
			stage = 2;
			stageAt = ticks;
		} else if (stage == 2 && waited > 20 && demo > 1500 && demo < 1750) {
			shot(client, name.replace(' ', '_') + "_a");
			command(client, "tp @a 0 130 0 38 -4");
			stage = 3;
			stageAt = ticks;
		} else if (stage == 3 && waited > 12) {
			shot(client, name.replace(' ', '_') + "_b");
			command(client, "tp @a 0 130 0 -38 -4");
			stage = 4;
			stageAt = ticks;
		} else if (stage == 4 && waited > 12) {
			shot(client, name.replace(' ', '_') + "_c");
			stage = 0;
			index++;
		}
	}

	private static void command(MinecraftClient client, String line) {
		var server = client.getServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), line));
		}
	}

	private static void shot(MinecraftClient client, String name) {
		try {
			Files.createDirectories(folder);
			Path file = folder.resolve(name + ".png");
			net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				try (image) {
					image.writeTo(file);
					VeloClient.LOGGER.info("Lyrics tour saved {}", file);
				} catch (Exception e) {
					VeloClient.LOGGER.error("Lyrics tour: saving {} failed", file, e);
				}
			});
		} catch (Exception e) {
			VeloClient.LOGGER.error("Lyrics tour: {} failed", name, e);
		}
	}
}
*///?} else {
public final class LyricsTour {
	private LyricsTour() {
	}

	public static void initIfRequested() {
	}
}
//?}
