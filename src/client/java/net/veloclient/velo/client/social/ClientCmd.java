package net.veloclient.velo.client.social;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//? if <26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
*///?}

/** Builders and helpers for client commands: Fabric API renamed ClientCommandManager to ClientCommands for 26.x - everything else uses these. */
final class ClientCmd {

	private ClientCmd() {
	}

	/** name -> whether the server has no command of that name; decided once per connection (see {@link #serverLacks}). */
	private static final java.util.Map<String, Boolean> DECIDED = new java.util.concurrent.ConcurrentHashMap<>();

	/** A new connection (or none): decide again when its command list arrives. */
	static void forgetServerCommands() {
		DECIDED.clear();
	}

	/**
	 * For commands that only exist when the server doesn't have one of the same name (/phantom, /flyspeed): true when the
	 * server's own command list has no {@code name}. Fabric evaluates a command's {@code requires} while it merges the client
	 * commands into the server's list - at that moment the list holds only the server's commands, which is what this reads - and
	 * again when you run the command, by which time our own copy is in there; so the first answer (once the server's list has
	 * arrived) is kept for the whole connection. Before the list arrives the command stays hidden.
	 */
	static boolean serverLacks(String name, FabricClientCommandSource source) {
		Boolean decided = DECIDED.get(name);
		if (decided != null) {
			return decided;
		}
		com.mojang.brigadier.tree.RootCommandNode<?> root;
		//? if <26.1 {
		var handler = source.getClient().getNetworkHandler();
		root = handler == null ? null : handler.getCommandDispatcher().getRoot();
		//?} else {
		/*var connection = source.getClient().getConnection();
		root = connection == null ? null : connection.getCommands().getRoot();
		*///?}
		if (root == null || root.getChildren().isEmpty()) {
			return false;
		}
		boolean lacks = root.getChild(name) == null;
		DECIDED.put(name, lacks);
		return lacks;
	}

	//? if <26.1 {
	static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return ClientCommandManager.literal(name);
	}

	static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
		return ClientCommandManager.argument(name, type);
	}
	//?} else {
	/*static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return ClientCommands.literal(name);
	}

	static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
		return ClientCommands.argument(name, type);
	}
	*///?}
}
