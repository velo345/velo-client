package net.veloclient.bridge.internal;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Server HUD elements from the config ({@code hud:}), refreshed once a second for every Velo player. Only changes are
 * sent. Velo shows them in its "Server HUD" element, which players can move in the HUD editor.
 *
 * <pre>
 * hud:
 *   coins:
 *     type: text             # text | bar | timer | image
 *     title: "Coins"
 *     text: "%vault_eco_balance%"
 *     icon: gold_nugget
 *     color: "#f4b82e"
 *     worlds: []             # empty = everywhere
 *     permission: ""
 *   health:
 *     type: bar
 *     title: "Health"
 *     value: "%health%"
 *     max: "%max_health%"
 *     color: "#ff5555"
 *   event:
 *     type: timer
 *     title: "Event starts in"
 *     ends: "2026-12-24T18:00:00Z"   # or seconds-from-start: 600
 * </pre>
 */
public final class HudElements {

	private static final Map<UUID, Map<String, String>> SENT = new ConcurrentHashMap<>();

	private HudElements() {
	}

	public static void tick(ConfigurationSection hud, Supplier<String> serverName) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!Players.isVelo(p.getUniqueId())) {
				continue;
			}
			Tasks.forPlayer(p, () -> update(p, hud, serverName.get()));
		}
	}

	private static void update(Player p, ConfigurationSection hud, String serverName) {
		Map<String, String> sent = SENT.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
		Set<String> now = new HashSet<>();
		if (hud != null) {
			for (String id : hud.getKeys(false)) {
				ConfigurationSection e = hud.getConfigurationSection(id);
				if (e == null || !e.getBoolean("enabled", true)) {
					continue;
				}
				var worlds = e.getStringList("worlds");
				if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) {
					continue;
				}
				String perm = e.getString("permission", "");
				if (!perm.isBlank() && !p.hasPermission(perm)) {
					continue;
				}
				JsonObject m = Protocol.message("hud_set");
				m.addProperty("id", "cfg-" + id);
				String type = e.getString("type", "text");
				m.addProperty("type", type);
				m.addProperty("title", Placeholders.apply(e.getString("title", ""), p, serverName));
				m.addProperty("order", e.getInt("order", 0));
				if (e.contains("color")) {
					m.addProperty("color", e.getString("color"));
				}
				if (e.contains("icon")) {
					m.addProperty("icon", e.getString("icon"));
				}
				switch (type) {
					case "bar" -> {
						double max = Placeholders.number(Placeholders.apply(e.getString("max", "1"), p, serverName), 1);
						double value = Placeholders.number(Placeholders.apply(e.getString("value", "0"), p, serverName), 0);
						m.addProperty("value", max <= 0 ? 0 : Math.max(0, Math.min(1, value / max)));
						m.addProperty("text", e.contains("text") ? Placeholders.apply(e.getString("text"), p, serverName)
								: (Math.round(value * 10) / 10.0) + " / " + (Math.round(max * 10) / 10.0));
					}
					case "timer" -> {
						long ends;
						try {
							ends = e.contains("ends") ? java.time.Instant.parse(e.getString("ends")).toEpochMilli() : 0;
						} catch (RuntimeException ex) {
							ends = 0;
						}
						m.addProperty("endsAt", ends);
					}
					case "image" -> {
						m.addProperty("url", e.getString("url", ""));
						m.addProperty("w", Math.max(4, Math.min(256, e.getInt("width", 64))));
						m.addProperty("h", Math.max(4, Math.min(256, e.getInt("height", 64))));
					}
					default -> m.addProperty("text", Placeholders.apply(e.getString("text", ""), p, serverName));
				}
				String key = "cfg-" + id;
				now.add(key);
				String json = m.toString();
				if (!json.equals(sent.get(key))) {
					sent.put(key, json);
					Protocol.send(p, m);
				}
			}
		}
		for (String old : new HashSet<>(sent.keySet())) {
			if (old.startsWith("cfg-") && !now.contains(old)) {
				sent.remove(old);
				JsonObject r = Protocol.message("hud_remove");
				r.addProperty("id", old);
				Protocol.send(p, r);
			}
		}
	}

	public static void forget(UUID uuid) {
		SENT.remove(uuid);
	}

	/** After a config change: send everything again. */
	public static void resendAll() {
		SENT.clear();
	}
}
