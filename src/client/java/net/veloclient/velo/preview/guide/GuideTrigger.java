package net.veloclient.velo.preview.guide;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens the setup guide once for every new profile: the launcher drops {@code velo/guide-pending} into a profile's folder
 * when it creates one (finishing or skipping removes it). It opens on the first title screen - or, when the profile
 * launches straight onto a server, about 3 s after joining, once no other screen is open. Launcher Settings can switch
 * guides off for every profile ({@link net.veloclient.velo.client.sound.UiPrefs#guides()}); R-Shift > Settings opens it
 * any time ({@link #open()}). {@code -Dvelo.guide=force} shows it anyway.
 */
public final class GuideTrigger {

	private static boolean handled;

	private GuideTrigger() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	static Path marker() {
		return FabricLoader.getInstance().getGameDir().resolve("velo").resolve("guide-pending");
	}

	private static boolean wanted() {
		if ("force".equals(System.getProperty("velo.guide"))) {
			return true;
		}
		return net.veloclient.velo.client.sound.UiPrefs.guides() && Files.exists(marker());
	}

	private static int inWorldTicks;

	private static void tick() {
		if (handled) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var screen = client.currentScreen;
		boolean title = screen instanceof net.minecraft.client.gui.screen.TitleScreen;
		boolean inWorld = client.player != null && client.world != null;
		//?} else if <26.2 {
		/*var screen = client.screen;
		boolean title = screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		boolean inWorld = client.player != null && client.level != null;
		*///?} else {
		/*var screen = client.gui.screen();
		boolean title = screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		boolean inWorld = client.player != null && client.level != null;
		*///?}
		if (title) {
			handled = true;
			if (wanted()) {
				client.setScreen(new GuideScreen(screen));
			}
			return;
		}
		// Launched straight onto a server: wait until the world is there and nothing else is open (loading screens,
		// the server's resource pack prompt...), then show it over the game.
		inWorldTicks = inWorld ? inWorldTicks + 1 : 0;
		if (inWorldTicks >= 60 && screen == null) {
			handled = true;
			if (wanted()) {
				client.setScreen(new GuideScreen(null));
			}
		}
	}

	/** R-Shift > Settings: run the setup guide again, from wherever the player is. */
	public static void open() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var screen = client.currentScreen;
		//?} else if <26.2 {
		/*var screen = client.screen;
		*///?} else {
		/*var screen = client.gui.screen();
		*///?}
		client.setScreen(new GuideScreen(screen));
	}

	/** The guide is done (finished or skipped): this profile won't show it again. */
	static void finished() {
		try {
			Files.deleteIfExists(marker());
		} catch (Exception ignored) {
			// it shows again next time - harmless
		}
	}
}
