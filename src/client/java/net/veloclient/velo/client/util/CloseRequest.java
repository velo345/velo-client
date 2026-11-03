package net.veloclient.velo.client.util;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Velo launcher's Stop / Relaunch: it writes {@code velo/close-request} into the profile, and the game closes the
 * way its window's X does - worlds and settings are saved. Before, the launcher ended the process, which on Windows is a
 * hard kill. A request left over from an earlier run is removed at startup, so it can't close a fresh game.
 */
public final class CloseRequest {

	private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("velo").resolve("close-request");
	private static int ticks;
	private static boolean stopping;

	private CloseRequest() {
	}

	public static void register() {
		try {
			Files.deleteIfExists(FILE);
		} catch (Exception ignored) {
			// not writable: then there's nothing to read either
		}
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	private static void tick() {
		if (stopping || ++ticks % 20 != 0 || !Files.exists(FILE)) {
			return;
		}
		stopping = true;
		try {
			Files.deleteIfExists(FILE);
		} catch (Exception ignored) {
			// closing anyway
		}
		net.veloclient.velo.VeloClient.LOGGER.info("The Velo launcher asked the game to close (Stop / Relaunch) - closing normally");
		//? if <26.1 {
		net.minecraft.client.MinecraftClient.getInstance().scheduleStop();
		//?} else {
		/*net.minecraft.client.Minecraft.getInstance().stop();
		*///?}
	}
}
