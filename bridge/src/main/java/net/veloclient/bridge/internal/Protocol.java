package net.veloclient.bridge.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;

/**
 * The wire format: every message on the {@code velo:bridge} plugin channel is one UTF-8 JSON object with a "t" (type)
 * field. Unknown types and fields are ignored on both sides, so either can be newer than the other.
 * See https://dev.velo-client.com/protocol.
 */
public final class Protocol {

	public static final String CHANNEL = "velo:bridge";
	public static final int VERSION = 1;
	private static final Gson GSON = new Gson();
	private static Plugin plugin;

	private Protocol() {
	}

	public static void init(Plugin owner) {
		plugin = owner;
	}

	public static JsonObject message(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("t", type);
		return o;
	}

	/** Sends on the player's thread; silently does nothing for players without Velo. */
	public static void send(Player player, JsonObject message) {
		if (!Players.isVelo(player.getUniqueId())) {
			return;
		}
		byte[] bytes = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
		Tasks.forPlayer(player, () -> {
			if (player.isOnline()) {
				player.sendPluginMessage(plugin, CHANNEL, bytes);
			}
		});
	}

	public static JsonObject parse(byte[] bytes) {
		try {
			return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
