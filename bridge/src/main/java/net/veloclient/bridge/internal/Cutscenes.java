package net.veloclient.bridge.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cutscenes: a camera flight through points (smooth curve, Velo's Rewind camera), with letterbox bars and a title.
 * Velo players see the flight; others just get the title. Recorded in game:
 * {@code /velobridge cutscene record intro}, walk/fly to each spot and {@code /velobridge cutscene point}, then
 * {@code /velobridge cutscene save}.
 *
 * <pre>
 * cutscenes:
 *   intro:
 *     duration: 12          # seconds for the whole flight
 *     letterbox: true
 *     lock: true            # player can't move meanwhile (camera only - their real position never changes)
 *     title: "&bWelcome"
 *     subtitle: "to %server%"
 *     end-action: ""        # an action when it ends, e.g. "menu:start"
 *     world: world
 *     points:
 *       - [0.5, 80, 0.5, 45, 10]    # x, y, z, yaw, pitch
 *       - [20, 85, 30, 120, 20]
 * on-first-join-cutscene: ""        # play this one for players new to the server
 * </pre>
 */
public final class Cutscenes {

	private static final Map<UUID, List<double[]>> RECORDING = new HashMap<>();
	private static final Map<UUID, String> RECORDING_ID = new HashMap<>();
	private static final Map<UUID, String> PLAYING = new HashMap<>();

	private Cutscenes() {
	}

	public static boolean play(Player p, ConfigurationSection all, String id, String serverName) {
		ConfigurationSection c = all == null ? null : all.getConfigurationSection(id);
		if (c == null) {
			return false;
		}
		List<?> raw = c.getList("points", List.of());
		JsonArray points = new JsonArray();
		for (Object o : raw) {
			if (o instanceof List<?> l && l.size() >= 5) {
				JsonArray pt = new JsonArray();
				for (int i = 0; i < 5; i++) {
					pt.add(Double.parseDouble(String.valueOf(l.get(i))));
				}
				points.add(pt);
			}
		}
		String title = Placeholders.apply(c.getString("title", ""), p, serverName);
		String subtitle = Placeholders.apply(c.getString("subtitle", ""), p, serverName);
		if (!Players.isVelo(p.getUniqueId()) || points.size() < 2) {
			if (!title.isEmpty()) {
				Tasks.forPlayer(p, () -> p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', title),
						org.bukkit.ChatColor.translateAlternateColorCodes('&', subtitle), 10, 60, 20));
			}
			return true;
		}
		JsonObject m = Protocol.message("cutscene");
		m.addProperty("id", id);
		m.add("points", points);
		m.addProperty("duration", Math.max(1, c.getDouble("duration", 10)) * 1000);
		m.addProperty("letterbox", c.getBoolean("letterbox", true));
		m.addProperty("lock", c.getBoolean("lock", true));
		m.addProperty("title", title);
		m.addProperty("subtitle", subtitle);
		PLAYING.put(p.getUniqueId(), id);
		Protocol.send(p, m);
		return true;
	}

	public static void stop(Player p) {
		PLAYING.remove(p.getUniqueId());
		Protocol.send(p, Protocol.message("cutscene_stop"));
	}

	/** {@code cutscene_done} from the client: run the end action. */
	public static String done(Player p, String id, ConfigurationSection all) {
		String playing = PLAYING.remove(p.getUniqueId());
		if (playing == null || !playing.equals(id) || all == null || all.getConfigurationSection(id) == null) {
			return null;
		}
		return all.getConfigurationSection(id).getString("end-action", "");
	}

	public static void forget(UUID uuid) {
		PLAYING.remove(uuid);
	}

	// ---- recording

	public static void record(Player p, String id) {
		RECORDING.put(p.getUniqueId(), new ArrayList<>());
		RECORDING_ID.put(p.getUniqueId(), id);
	}

	public static int point(Player p) {
		List<double[]> list = RECORDING.get(p.getUniqueId());
		if (list == null) {
			return -1;
		}
		Location l = p.getEyeLocation();
		list.add(new double[] {round(l.getX()), round(l.getY()), round(l.getZ()), round(l.getYaw()), round(l.getPitch())});
		return list.size();
	}

	public static String save(Player p, FileConfiguration config) {
		List<double[]> list = RECORDING.remove(p.getUniqueId());
		String id = RECORDING_ID.remove(p.getUniqueId());
		if (list == null || list.size() < 2) {
			return null;
		}
		List<List<Double>> pts = new ArrayList<>();
		for (double[] d : list) {
			pts.add(List.of(d[0], d[1], d[2], d[3], d[4]));
		}
		String base = "cutscenes." + id;
		if (!config.contains(base + ".duration")) {
			config.set(base + ".duration", Math.max(4, list.size() * 3));
			config.set(base + ".letterbox", true);
			config.set(base + ".lock", true);
			config.set(base + ".title", "");
			config.set(base + ".subtitle", "");
		}
		config.set(base + ".world", p.getWorld().getName());
		config.set(base + ".points", pts);
		return id;
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	public static Map<String, Integer> list(ConfigurationSection all) {
		Map<String, Integer> out = new LinkedHashMap<>();
		if (all != null) {
			for (String k : all.getKeys(false)) {
				out.put(k, all.getList(k + ".points", List.of()).size());
			}
		}
		return out;
	}
}
