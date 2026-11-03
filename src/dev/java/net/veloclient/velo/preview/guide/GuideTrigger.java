package net.veloclient.velo.preview.guide;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens the first-start guide once, on the title screen, for a profile that hasn't seen it: the launcher drops
 * {@code velo/guide-pending} into a profile's folder when it creates one (profiles from before the guide existed never get
 * the file, so they never get the guide). Finishing or skipping removes it. Launcher Settings can switch guides off for
 * every profile ({@link net.veloclient.velo.client.sound.UiPrefs#guides()}). {@code -Dvelo.guide=force} shows it anyway.
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

	private static void tick() {
		if (handled) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var screen = client.currentScreen;
		boolean title = screen instanceof net.minecraft.client.gui.screen.TitleScreen;
		//?} else if <26.2 {
		/*var screen = client.screen;
		boolean title = screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		*///?} else {
		/*var screen = client.gui.screen();
		boolean title = screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		*///?}
		if (!title) {
			return;
		}
		handled = true;
		if (wanted()) {
			client.setScreen(new GuideScreen(screen));
		}
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
