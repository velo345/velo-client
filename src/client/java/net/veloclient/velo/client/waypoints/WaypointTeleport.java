package net.veloclient.velo.client.waypoints;

import java.util.Locale;

/**
 * "Teleport" for waypoints and the world map, only when you're allowed to: servers only send the
 * commands a player may use, so if /tp is in your command list (op, creative singleplayer with
 * cheats, a permission plugin granting it) the shortcut shows up. It just runs the command for you.
 */
public final class WaypointTeleport {

	private WaypointTeleport() {
	}

	public static boolean available() {
		//? if <26.1 {
		var handler = net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
		if (handler == null) {
			return false;
		}
		var root = handler.getCommandDispatcher().getRoot();
		//?} else {
		/*var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		if (connection == null) {
			return false;
		}
		var root = connection.getCommands().getRoot();
		*///?}
		return root.getChild("tp") != null || root.getChild("teleport") != null;
	}

	/** Teleports you to (x, y, z) in {@code dimension} (e.g. "minecraft:the_nether"); null = where you are. */
	public static void teleport(double x, double y, double z, String dimension) {
		String target = String.format(Locale.ROOT, "%.2f %.2f %.2f", Math.floor(x) + 0.5, Math.floor(y), Math.floor(z) + 0.5);
		String current = net.veloclient.velo.client.util.ClientCompat.dimensionId();
		String command = dimension != null && !dimension.equals(current)
				? "execute in " + dimension + " run tp @s " + target
				: "tp @s " + target;
		//? if <26.1 {
		var handler = net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
		if (handler != null) {
			handler.sendChatCommand(command);
		}
		//?} else {
		/*var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendCommand(command);
		}
		*///?}
	}
}
