package net.veloclient.velo.dev.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.dev.bridge.VeloBridgeClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Discord Rich Presence: "Playing Velo Client" with where you are - the menus, singleplayer, or the server (servers
 * with Velo Bridge set their own text, e.g. "In BedWars · Team Red"). Talks to the Discord app on this PC only; quietly
 * waits while Discord isn't open.
 */
public final class DiscordPresenceModule extends AbstractModule implements Configurable {

	/** The "Velo Client" application on Discord. */
	private static final String CLIENT_ID = "1560056398858027058";
	private static boolean showServer = true;
	private static boolean showWorldName;
	private static boolean showButton = true;
	private volatile Thread worker;

	public DiscordPresenceModule() {
		super("discord-presence", "Discord Rich Presence", "Shows \"Playing Velo Client\" on Discord with where you are - the menus, a world or the server.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public void onEnable() {
		Thread running = worker;
		if (running == null || !running.isAlive()) {
			worker = Thread.ofVirtual().name("velo-discord-presence").start(this::loop);
		}
	}

	@Override
	public void onDisable() {
		Thread running = worker;
		worker = null;
		if (running != null) {
			running.interrupt();
		}
	}

	private void loop() {
		DiscordIpc ipc = null;
		String sent = null;
		long since = System.currentTimeMillis() / 1000;
		String place = null;
		while (worker == Thread.currentThread()) {
			try {
				if (ipc == null) {
					ipc = DiscordIpc.open(CLIENT_ID);
					sent = null;
				}
				String[] now = describe();
				String where = now[0] + "|" + now[1];
				if (!where.equals(place)) {
					place = where;
					since = System.currentTimeMillis() / 1000;
				}
				JsonObject activity = activity(now[0], now[1], since);
				String json = activity.toString();
				if (!json.equals(sent)) {
					ipc.setActivity(activity);
					sent = json;
				}
				Thread.sleep(5_000);
			} catch (InterruptedException e) {
				break;
			} catch (Exception e) {
				// Discord closed or not running: try again a bit later.
				if (ipc != null) {
					ipc.close();
					ipc = null;
				}
				VeloClient.LOGGER.debug("Discord presence: {}", e.getMessage());
				try {
					Thread.sleep(20_000);
				} catch (InterruptedException ie) {
					break;
				}
			}
		}
		if (ipc != null) {
			try {
				ipc.setActivity(null);
			} catch (Exception ignored) {
				// Discord clears it when we disconnect anyway.
			}
			ipc.close();
		}
	}

	/** {details, state} for where the player is right now. */
	private static String[] describe() {
		if (VeloBridgeClient.presenceDetails() != null) {
			return new String[] {VeloBridgeClient.presenceDetails(), VeloBridgeClient.presenceState()};
		}
		String server = ClientCompat.currentServerAddress();
		if (server != null && !server.isBlank()) {
			ServerMappingsClient.Mapping mapped = showServer ? ServerMappingsClient.get(server) : null;
			if (mapped != null) {
				String details = mapped.details().replace("%name%", mapped.name());
				return new String[] {details, mapped.state() != null ? mapped.state().replace("%name%", mapped.name()) : "Multiplayer"};
			}
			String name = ClientCompat.currentServerName();
			return new String[] {showServer ? "Playing on " + (name != null && !name.isBlank() ? name : server) : "Playing multiplayer", "Multiplayer"};
		}
		if (ClientCompat.isSingleplayer()) {
			String world = ClientCompat.singleplayerWorldName();
			return new String[] {"Singleplayer", showWorldName && world != null ? world : "Exploring a world"};
		}
		return new String[] {"In the menus", "Minecraft " + ClientCompat.minecraftVersion()};
	}

	private static JsonObject activity(String details, String state, long since) {
		JsonObject a = new JsonObject();
		a.addProperty("details", clip(details));
		if (state != null && !state.isBlank()) {
			a.addProperty("state", clip(state));
		}
		JsonObject timestamps = new JsonObject();
		timestamps.addProperty("start", since);
		a.add("timestamps", timestamps);
		JsonObject assets = new JsonObject();
		String server = ClientCompat.currentServerAddress();
		ServerMappingsClient.Mapping mapped = showServer && server != null ? ServerMappingsClient.get(server) : null;
		if (mapped != null && mapped.logo() != null) {
			// a mapped server (github.com/velo345/server-mappings): its logo big, Velo small
			assets.addProperty("large_image", mapped.logo());
			assets.addProperty("large_text", clip(mapped.largeText()));
			assets.addProperty("small_image", "https://velo-client.com/assets/icon-512.png");
			assets.addProperty("small_text", "Velo Client · Minecraft " + ClientCompat.minecraftVersion());
		} else {
			assets.addProperty("large_image", "https://velo-client.com/assets/icon-512.png");
			assets.addProperty("large_text", "Velo Client · Minecraft " + ClientCompat.minecraftVersion());
		}
		a.add("assets", assets);
		if (mapped != null && mapped.buttons() != null && mapped.buttons().isJsonArray() && !mapped.buttons().getAsJsonArray().isEmpty()) {
			JsonArray buttons = new JsonArray();
			for (var b : mapped.buttons().getAsJsonArray()) {
				if (buttons.size() < 2 && b.isJsonObject() && b.getAsJsonObject().has("label") && b.getAsJsonObject().has("url")
						&& b.getAsJsonObject().get("url").getAsString().startsWith("https://")) {
					JsonObject o = new JsonObject();
					o.addProperty("label", clip(b.getAsJsonObject().get("label").getAsString()));
					o.addProperty("url", b.getAsJsonObject().get("url").getAsString());
					buttons.add(o);
				}
			}
			a.add("buttons", buttons);
		} else if (showButton) {
			JsonArray buttons = new JsonArray();
			JsonObject get = new JsonObject();
			get.addProperty("label", "Get Velo Client");
			get.addProperty("url", "https://velo-client.com");
			buttons.add(get);
			a.add("buttons", buttons);
		}
		return a;
	}

	private static String clip(String s) {
		String t = s == null ? "" : s.strip();
		if (t.length() < 2) {
			t = t + "  ";
		}
		return t.length() > 120 ? t.substring(0, 120) : t;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show The Server You're On", () -> showServer, v -> showServer = v),
				new ConfigField.ToggleField("Show Singleplayer World Names", () -> showWorldName, v -> showWorldName = v),
				new ConfigField.ToggleField("\"Get Velo Client\" Button", () -> showButton, v -> showButton = v));
	}
}
