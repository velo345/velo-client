package net.veloclient.velo.client.social;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.network.SocialClient;

import java.util.Locale;
import java.util.Set;

/**
 * Party chat through Velo, not the server: {@code /party} (name configurable in the Party module)
 * switches what you type in chat between public chat and your party, and {@code /party <message>}
 * sends a single message to the party. Party messages only reach Velo players in your party and
 * show up in chat with their own style. When you're not in a Velo party - or you use one of the
 * server's own party subcommands ({@code /party invite ...} on Hypixel etc.) - the command goes to
 * the server as usual.
 */
public final class PartyChat {

	/** Server party-plugin subcommands that must keep reaching the server. */
	private static final Set<String> SERVER_SUBCOMMANDS = Set.of("invite", "accept", "deny", "decline", "leave", "kick", "remove", "disband",
			"list", "promote", "demote", "warp", "transfer", "join", "create", "chat", "help", "settings", "mute", "poll", "private", "public",
			"open", "close", "kickoffline", "hijack", "challenge", "info", "toggle", "c");

	private static volatile boolean partyMode;
	private static volatile String commandName = "party";

	private PartyChat() {
	}

	public static void setCommandName(String name) {
		String clean = name == null ? "" : name.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
		commandName = clean.isEmpty() ? "party" : clean;
	}

	public static String commandName() {
		return commandName;
	}

	public static boolean partyMode() {
		return partyMode && SocialClient.inPartyOrSolo();
	}

	/** Outgoing chat line: true when it went to the party instead (cancel the public send). */
	public static boolean onOutgoingChat(String message) {
		if (!partyMode) {
			return false;
		}
		if (SocialClient.party() == null) {
			partyMode = false;
			info("You're not in a Velo party anymore - back to public chat.");
			return false;
		}
		send(message);
		return true;
	}

	/** Outgoing command (without the slash): true when it was ours and must not reach the server. */
	public static boolean onOutgoingCommand(String command) {
		String trimmed = command.strip();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (!(lower.equals(commandName) || lower.startsWith(commandName + " "))) {
			return false;
		}
		if (SocialClient.party() == null) {
			return false;
		}
		String rest = trimmed.length() > commandName.length() ? trimmed.substring(commandName.length()).strip() : "";
		if (rest.isEmpty()) {
			partyMode = !partyMode;
			info(partyMode ? "Party chat ON - your messages go to your Velo party. /" + commandName + " to switch back."
					: "Party chat OFF - back to public chat.");
			return true;
		}
		String first = rest.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (SERVER_SUBCOMMANDS.contains(first)) {
			return false;
		}
		send(rest);
		return true;
	}

	private static void send(String message) {
		SocialClient.partyChat(message).thenAccept(error -> {
			if (error != null) {
				MinecraftClient.getInstance().execute(() -> info("Party message not sent: " + error));
			}
		});
	}

	/** An incoming party chat line (also your own, echoed back by the server). */
	public static void show(JsonObject line) {
		String kind = line.has("kind") ? line.get("kind").getAsString() : "chat";
		String text = line.has("text") ? line.get("text").getAsString() : "";
		if ("system".equals(kind)) {
			display(net.veloclient.velo.client.pvp.PvpCompat.literal("[Party] ", 0xFFC27BFF).append(
					net.veloclient.velo.client.pvp.PvpCompat.literal(text, 0xFFB8A8D0)));
			return;
		}
		String name = line.has("name") && !line.get("name").isJsonNull() ? line.get("name").getAsString() : "?";
		boolean mine = name.equals(net.veloclient.velo.client.serverpacks.ServerData.localName());
		display(net.veloclient.velo.client.pvp.PvpCompat.literal("[Party] ", 0xFFC27BFF)
				.append(net.veloclient.velo.client.pvp.PvpCompat.literal(name, mine ? 0xFFB8B8C8 : 0xFF7FD8FF))
				.append(net.veloclient.velo.client.pvp.PvpCompat.literal(": " + text, 0xFFF0E8FF)));
		if (!mine && SocialSettings.sound()) {
			net.veloclient.velo.client.pvp.PvpCompat.playUiSound("minecraft:block.note_block.chime", 1.5f, 0.45f);
		}
	}

	private static void info(String message) {
		display(net.veloclient.velo.client.pvp.PvpCompat.literal("[Party] ", 0xFFC27BFF)
				.append(net.veloclient.velo.client.pvp.PvpCompat.literal(message, 0xFFB8A8D0)));
	}

	//? if <26.1 {
	private static void display(net.minecraft.text.Text message) {
		var player = MinecraftClient.getInstance().player;
		if (player != null) {
			player.sendMessage(message, false);
		}
	}
	//?} else {
	/*private static void display(net.minecraft.network.chat.Component message) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(message);
		}
	}
	*///?}
}
