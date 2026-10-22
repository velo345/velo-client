package net.veloclient.velo.client.hosting;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.List;

/** Every version-specific call World Hosting needs, in one place: the singleplayer server, opening it, commands, players. */
final class HostCompat {

	private HostCompat() {
	}

	//? if <26.1 {
	static net.minecraft.server.integrated.IntegratedServer server() {
		return MinecraftClient.getInstance().getServer();
	}
	//?} else {
	/*static net.minecraft.client.server.IntegratedServer server() {
		return MinecraftClient.getInstance().getSingleplayerServer();
	}
	*///?}

	static boolean published() {
		var server = server();
		//? if <26.1 {
		return server != null && server.isRemote();
		//?} else {
		/*return server != null && server.isPublished();
		*///?}
	}

	/** Opens the world to LAN on {@code port} (only this PC's relay link uses it), cheats as the world has them. */
	static boolean open(int port, boolean cheats) {
		var server = server();
		if (server == null) {
			return false;
		}
		//? if <26.1 {
		return server.openToLan(server.getDefaultGameMode(), cheats, port);
		//?} else if <26.2 {
		/*return server.publishServer(server.getDefaultGameType(), cheats, port);
		*///?} else if <26.3 {
		/*return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, server.getDefaultGameType(), cheats, port);
		*///?} else {
		/*return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, cheats, port);
		*///?}
	}

	/** Runs a server command as the server itself (full permission), on the server thread. */
	static void command(String command) {
		var server = server();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			try {
				//? if <26.1 {
				server.getCommandManager().parseAndExecute(server.getCommandSource(), command);
				//?} else {
				/*server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
				*///?}
			} catch (Exception e) {
				net.veloclient.velo.VeloClient.LOGGER.debug("World Hosting command failed: {}", command, e);
			}
		});
	}

	/** Names of everyone in the world (you included). */
	static List<String> players() {
		var server = server();
		if (server == null) {
			return List.of();
		}
		//? if <26.1 {
		return List.of(server.getPlayerManager().getPlayerNames());
		//?} else {
		/*return List.of(server.getPlayerList().getPlayerNamesArray());
		*///?}
	}

	/** The world's folder (for backups). */
	static Path worldFolder() {
		var server = server();
		if (server == null) {
			return null;
		}
		//? if <26.1 {
		return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
		//?} else {
		/*return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
		*///?}
	}

	static String worldName() {
		var server = server();
		if (server == null) {
			return null;
		}
		//? if <26.1 {
		return server.getSaveProperties().getLevelName();
		//?} else {
		/*return server.getWorldData().getLevelName();
		*///?}
	}

	static String selfName() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player == null ? null : client.player.getGameProfile().name();
	}
}
