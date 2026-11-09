package net.veloclient.bridge.internal;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Music by area: when a Velo player walks into a zone its music fades in, when they leave it fades out (or the next
 * zone's takes over). Sounds are sound event ids - vanilla ones or your resource pack's (custom music: add an .ogg to
 * your pack under a sound event). Checked twice a second.
 *
 * <pre>
 * sound-zones:
 *   spawn:
 *     world: world
 *     from: [-50, 0, -50]
 *     to: [50, 320, 50]
 *     sound: "minecraft:music_disc.cat"
 *     volume: 0.8
 *     loop: true
 *     priority: 0           # overlapping zones: the higher one wins
 * world-music:              # music for a whole world when no zone applies
 *   world_nether: "minecraft:music.nether.basalt_deltas"
 * </pre>
 */
public final class SoundZones {

	private static final Map<UUID, String> PLAYING = new ConcurrentHashMap<>();
	/** Music an API call started - zones leave it alone until it's stopped. */
	private static final Map<UUID, Boolean> MANUAL = new ConcurrentHashMap<>();

	private SoundZones() {
	}

	public static void tick(ConfigurationSection zones, ConfigurationSection worldMusic) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!Players.isVelo(p.getUniqueId()) || MANUAL.containsKey(p.getUniqueId())) {
				continue;
			}
			Tasks.forPlayer(p, () -> check(p, zones, worldMusic));
		}
	}

	private static void check(Player p, ConfigurationSection zones, ConfigurationSection worldMusic) {
		Location l = p.getLocation();
		String best = null;
		int bestPriority = Integer.MIN_VALUE;
		ConfigurationSection bestZone = null;
		if (zones != null) {
			for (String id : zones.getKeys(false)) {
				ConfigurationSection z = zones.getConfigurationSection(id);
				if (z == null || !z.getBoolean("enabled", true) || !l.getWorld().getName().equals(z.getString("world", "world"))) {
					continue;
				}
				var a = z.getDoubleList("from");
				var b = z.getDoubleList("to");
				if (a.size() < 3 || b.size() < 3) {
					continue;
				}
				if (inside(l.getX(), a.get(0), b.get(0)) && inside(l.getY(), a.get(1), b.get(1)) && inside(l.getZ(), a.get(2), b.get(2))) {
					int pr = z.getInt("priority", 0);
					if (pr > bestPriority) {
						bestPriority = pr;
						best = id;
						bestZone = z;
					}
				}
			}
		}
		String spec;
		if (bestZone != null) {
			spec = bestZone.getString("sound", "") + "|" + bestZone.getDouble("volume", 0.8) + "|" + bestZone.getBoolean("loop", true) + "|zone:" + best;
		} else if (worldMusic != null && worldMusic.contains(l.getWorld().getName())) {
			spec = worldMusic.getString(l.getWorld().getName()) + "|0.7|true|world";
		} else {
			spec = "";
		}
		String now = PLAYING.getOrDefault(p.getUniqueId(), "");
		if (!spec.equals(now)) {
			if (spec.isEmpty()) {
				PLAYING.remove(p.getUniqueId());
				Protocol.send(p, Protocol.message("music_stop"));
			} else {
				PLAYING.put(p.getUniqueId(), spec);
				String[] s = spec.split("\\|");
				send(p, s[0], Double.parseDouble(s[1]), Boolean.parseBoolean(s[2]));
			}
		}
	}

	private static boolean inside(double v, double a, double b) {
		return v >= Math.min(a, b) && v <= Math.max(a, b) + 1;
	}

	private static void send(Player p, String sound, double volume, boolean loop) {
		JsonObject m = Protocol.message("music");
		m.addProperty("sound", sound);
		m.addProperty("volume", Math.max(0, Math.min(1, volume)));
		m.addProperty("loop", loop);
		Protocol.send(p, m);
	}

	/** API / actions: "sound|volume|loop" or "" to stop (zones take over again). */
	public static void manual(Player p, String spec) {
		if (spec == null || spec.isBlank()) {
			MANUAL.remove(p.getUniqueId());
			PLAYING.remove(p.getUniqueId());
			Protocol.send(p, Protocol.message("music_stop"));
			return;
		}
		String[] s = spec.split("\\|");
		MANUAL.put(p.getUniqueId(), true);
		PLAYING.put(p.getUniqueId(), "manual");
		send(p, s[0], s.length > 1 ? Double.parseDouble(s[1]) : 0.8, s.length <= 2 || Boolean.parseBoolean(s[2]));
	}

	public static void forget(UUID uuid) {
		PLAYING.remove(uuid);
		MANUAL.remove(uuid);
	}

	/** Positions for "/velobridge zone pos1|pos2" while creating a zone. */
	public static final Map<UUID, double[][]> SELECTION = new HashMap<>();
}
