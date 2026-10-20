package net.veloclient.velo.client.gui.title;

import net.veloclient.velo.client.util.ClientCompat;

/**
 * Everything the redesigned title / pause menus read from the game that's named differently per Minecraft
 * version - kept in this one place so the menu code itself stays version-free.
 */
public final class MenuInfo {

	private MenuInfo() {
	}

	public static int fps() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().getCurrentFps();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().getFps();
		*///?}
	}

	/** Players on the tab list, or -1 when not connected. */
	public static int playersOnline() {
		//? if <26.1 {
		var handler = net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
		return handler == null ? -1 : handler.getPlayerList().size();
		//?} else {
		/*var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
		return listener == null ? -1 : listener.getOnlinePlayers().size();
		*///?}
	}

	/** Your own latency in ms as the server reports it, or -1. */
	public static int ping() {
		//? if <26.1 {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		var handler = mc.getNetworkHandler();
		if (handler == null || mc.player == null) {
			return -1;
		}
		var entry = handler.getPlayerListEntry(mc.player.getUuid());
		return entry == null ? -1 : entry.getLatency();
		//?} else {
		/*var mc = net.minecraft.client.Minecraft.getInstance();
		var listener = mc.getConnection();
		if (listener == null || mc.player == null) {
			return -1;
		}
		var info = listener.getPlayerInfo(mc.player.getUUID());
		return info == null ? -1 : info.getLatency();
		*///?}
	}

	/** The key that opens the Velo menu, as the player sees it ("RIGHT SHIFT"). */
	public static String menuKey() {
		try {
			//? if <26.1 {
			return net.veloclient.velo.client.keybind.VeloKeybinds.OPEN_MOD_MENU.getBoundKeyLocalizedText().getString().toUpperCase(java.util.Locale.ROOT);
			//?} else {
			/*return net.veloclient.velo.client.keybind.VeloKeybinds.OPEN_MOD_MENU.getTranslatedKeyMessage().getString().toUpperCase(java.util.Locale.ROOT);
			*///?}
		} catch (RuntimeException e) {
			return "";
		}
	}

	public static boolean singleplayer() {
		return ClientCompat.isSingleplayer();
	}

	public static String worldName() {
		return ClientCompat.singleplayerWorldName();
	}

	public static String serverName() {
		return ClientCompat.currentServerName();
	}

	public static String serverAddress() {
		return ClientCompat.currentServerAddress();
	}

	/** The signed-in account's name (also on the title screen, where there's no player yet). */
	public static String username() {
		try {
			//? if <26.1 {
			return net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername();
			//?} else {
			/*return net.minecraft.client.Minecraft.getInstance().getUser().getName();
			*///?}
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The account's uuid as a string, or null. */
	public static String uuid() {
		try {
			//? if <26.1 {
			var id = net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
			//?} else {
			/*var id = net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
			*///?}
			return id == null ? null : id.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The first server in the multiplayer list as {name, address, icon bytes}, or null - "Jump back in" before you've joined one with Velo. */
	public static Object[] firstSavedServer() {
		try {
			//? if <26.1 {
			var list = new net.minecraft.client.option.ServerList(net.minecraft.client.MinecraftClient.getInstance());
			list.loadFile();
			if (list.size() == 0) {
				return null;
			}
			var entry = list.get(0);
			return new Object[] {entry.name, entry.address, entry.getFavicon()};
			//?} else {
			/*var list = new net.minecraft.client.multiplayer.ServerList(net.minecraft.client.Minecraft.getInstance());
			list.load();
			if (list.size() == 0) {
				return null;
			}
			var entry = list.get(0);
			return new Object[] {entry.name, entry.ip, entry.getIconBytes()};
			*///?}
		} catch (RuntimeException e) {
			return null;
		}
	}
}
