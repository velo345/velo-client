package net.veloclient.bridge.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Velo cosmetics on your NPCs (FancyNpcs or Citizens, or any player-shaped entity by UUID): cloaks, wings, auras and
 * bodywear, seen by Velo players. Design them in the owner panel (like Velo's Locker) or in config.yml:
 *
 * <pre>
 * npc-cosmetics:
 *   shopkeeper:                 # FancyNpcs / Citizens NPC name (or an entity UUID)
 *     cape: cape_velo_neon
 *     wings: wings_angelic
 *     aura: "aura_stars"
 *     wear: "hat_tophat:1d1d1d,scarf_knit"
 * </pre>
 *
 * The NPC plugins are found by reflection - nothing to install besides them. Checked every 10 seconds (NPCs respawn
 * with new entity ids); only changes are sent.
 */
public final class NpcCosmetics {

	private static String lastSent = "";
	private static JsonObject current = Protocol.message("npc_cosmetics");

	private NpcCosmetics() {
	}

	/** A spawned NPC: its entity id and, when known, its UUID. */
	record Found(int entityId, String uuid) {
	}

	/** Every NPC by name, from FancyNpcs and Citizens (main / global thread). */
	public static Map<String, Found> npcs() {
		Map<String, Found> out = new LinkedHashMap<>();
		fancy(out);
		citizens(out);
		return out;
	}

	private static void fancy(Map<String, Found> out) {
		if (Bukkit.getPluginManager().getPlugin("FancyNpcs") == null) {
			return;
		}
		try {
			Class<?> api = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
			Object plugin = api.getMethod("get").invoke(null);
			Object manager = plugin.getClass().getMethod("getNpcManager").invoke(plugin);
			Iterable<?> all = (Iterable<?>) manager.getClass().getMethod("getAllNpcs").invoke(manager);
			for (Object npc : all) {
				Object data = call(npc, "getData");
				String name = data == null ? null : String.valueOf(call(data, "getName"));
				Object id = call(npc, "getEntityId");
				if (name != null && id instanceof Integer entityId) {
					out.putIfAbsent(name, new Found(entityId, null));
				}
			}
		} catch (Throwable ignored) {
			// other FancyNpcs version: no NPC cosmetics from it
		}
	}

	private static void citizens(Map<String, Found> out) {
		if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
			return;
		}
		try {
			Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
			Iterable<?> registry = (Iterable<?>) api.getMethod("getNPCRegistry").invoke(null);
			for (Object npc : registry) {
				if (!Boolean.TRUE.equals(call(npc, "isSpawned"))) {
					continue;
				}
				String name = String.valueOf(call(npc, "getName"));
				if (call(npc, "getEntity") instanceof Entity e) {
					out.putIfAbsent(name, new Found(e.getEntityId(), e.getUniqueId().toString()));
					out.putIfAbsent(String.valueOf(call(npc, "getId")), new Found(e.getEntityId(), e.getUniqueId().toString()));
				}
			}
		} catch (Throwable ignored) {
			// other Citizens version
		}
	}

	private static Object call(Object o, String method) {
		try {
			Method m = o.getClass().getMethod(method);
			m.setAccessible(true);
			return m.invoke(o);
		} catch (Throwable e) {
			return null;
		}
	}

	/** Names of the NPCs on this server (for the panel's designer). */
	public static JsonArray names() {
		JsonArray a = new JsonArray();
		try {
			npcs().keySet().stream().filter(n -> !n.matches("\\d+")).limit(300).forEach(a::add);
		} catch (Throwable ignored) {
			// none
		}
		return a;
	}

	/** Builds the message from the config; sends it to every Velo player when it changed. Global thread. */
	public static void tick(ConfigurationSection cfg) {
		JsonObject m = Protocol.message("npc_cosmetics");
		JsonArray list = new JsonArray();
		if (cfg != null && !cfg.getKeys(false).isEmpty()) {
			Map<String, Found> found = npcs();
			for (String key : cfg.getKeys(false)) {
				ConfigurationSection c = cfg.getConfigurationSection(key);
				if (c == null) {
					continue;
				}
				JsonObject o = new JsonObject();
				Found f = found.get(key);
				if (f != null) {
					o.addProperty("entity", f.entityId());
					if (f.uuid() != null) {
						o.addProperty("uuid", f.uuid());
					}
				} else if (key.matches("[0-9a-fA-F-]{32,36}")) {
					o.addProperty("uuid", key);
				} else {
					continue;
				}
				for (String k : List.of("cape", "wings", "aura", "wear", "pet")) {
					String v = c.getString(k, "");
					if (!v.isBlank()) {
						o.addProperty(k, v.length() > 400 ? v.substring(0, 400) : v);
					}
				}
				list.add(o);
				if (list.size() >= 200) {
					break;
				}
			}
		}
		m.add("npcs", list);
		String json = m.toString();
		current = m;
		if (!json.equals(lastSent)) {
			lastSent = json;
			for (Player p : Bukkit.getOnlinePlayers()) {
				if (Players.isVelo(p.getUniqueId())) {
					Protocol.send(p, m);
				}
			}
		}
	}

	/** For a player who just said hello. */
	public static void send(Player p) {
		if (current.has("npcs") && current.getAsJsonArray("npcs").size() > 0) {
			Protocol.send(p, current);
		}
	}
}
