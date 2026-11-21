package net.veloclient.velo.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * Whether the server's resource pack restyles or hides the status bars - MCC Island and others hide vanilla's hearts and
 * food by making those sprites see-through or moving them with the pack, depending on the game mode. Velo's Hearts /
 * Hunger modules then step aside, so the server's own HUD wins (bug report 2026-11-20: hearts stayed under MCC's
 * action bar). Checked every few seconds (a server pack can arrive any time), only the topmost pack per file counts.
 */
public final class ServerHudPack {

	private static long checkedAt;
	private static boolean hearts;
	private static boolean food;

	private ServerHudPack() {
	}

	public static boolean overridesHearts() {
		refresh();
		return hearts;
	}

	public static boolean overridesFood() {
		refresh();
		return food;
	}

	private static void refresh() {
		long now = System.nanoTime();
		if (now - checkedAt < 3_000_000_000L) {
			return;
		}
		checkedAt = now;
		try {
			hearts = fromServer("hud/heart/full") || fromServer("hud/heart/container");
			food = fromServer("hud/food_full") || fromServer("hud/food_empty");
		} catch (RuntimeException | LinkageError e) {
			hearts = false;
			food = false;
		}
	}

	/** True if the sprite {@code textures/gui/sprites/<path>.png} comes from a server's resource pack. */
	private static boolean fromServer(String path) {
		var manager = MinecraftClient.getInstance().getResourceManager();
		var id = Identifier.of("minecraft", "textures/gui/sprites/" + path + ".png");
		//? if <26.1 {
		var resource = manager.getResource(id);
		return resource.isPresent() && resource.get().getPack().getInfo().source() == net.minecraft.resource.ResourcePackSource.SERVER;
		//?} else {
		/*var resource = manager.getResource(id);
		return resource.isPresent() && resource.get().source().location().source() == net.minecraft.server.packs.repository.PackSource.SERVER;
		*///?}
	}
}
