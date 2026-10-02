package net.veloclient.velo.client.waypoints;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns every waypoint across every world, persisted to {@code waypoints-v2.json}. The older
 * {@code waypoints.json} (one flat list, no world, made by the old "add waypoint" key) is imported
 * once into a separate "Imported" world you can move into a real one from the waypoints menu.
 */
public final class WaypointManager {

	private static final String CONFIG_ID = "waypoints-v2";
	private static final String LEGACY_CONFIG_ID = "waypoints";
	public static final String LEGACY_WORLD = "legacy:imported";
	public static final String LATEST_DEATH = "Latest Death";
	public static final int DEATH_COLOR = 0xFFE5484D;

	/** Preset colors offered by the editor (new waypoints cycle through them). */
	public static final int[] PALETTE = {
			0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594, 0xFF0090FF,
			0xFF3E63DD, 0xFF8E4EC6, 0xFFD6409F, 0xFFFFFFFF, 0xFF8B8D98, 0xFF8D6A4F};

	private static final class Data {
		List<Waypoint> waypoints = new ArrayList<>();
		/** world key -> human-readable name (server name, save name...). */
		Map<String, String> worldNames = new LinkedHashMap<>();
		boolean legacyImported;
	}

	private record LegacyWaypoint(String name, String dimension, double x, double y, double z) {
	}

	private record LegacyData(List<LegacyWaypoint> waypoints) {
	}

	private static Data data;

	private WaypointManager() {
	}

	private static Data data() {
		if (data == null) {
			data = ConfigManager.load(CONFIG_ID, Data.class, new Data());
			if (data.waypoints == null) {
				data.waypoints = new ArrayList<>();
			}
			if (data.worldNames == null) {
				data.worldNames = new LinkedHashMap<>();
			}
			data.waypoints.removeIf(w -> w == null || w.world == null || w.dimension == null);
			for (Waypoint waypoint : data.waypoints) {
				if (waypoint.id == null) {
					waypoint.id = java.util.UUID.randomUUID().toString();
				}
				if (waypoint.name == null) {
					waypoint.name = "Waypoint";
				}
			}
			importLegacy();
		}
		return data;
	}

	private static void importLegacy() {
		if (data.legacyImported) {
			return;
		}
		data.legacyImported = true;
		LegacyData legacy = ConfigManager.load(LEGACY_CONFIG_ID, LegacyData.class, new LegacyData(List.of()));
		if (legacy.waypoints() != null && !legacy.waypoints().isEmpty()) {
			int i = 0;
			for (LegacyWaypoint old : legacy.waypoints()) {
				if (old == null || old.dimension() == null) {
					continue;
				}
				data.waypoints.add(new Waypoint(old.name() == null ? "Waypoint" : old.name(), LEGACY_WORLD, old.dimension(),
						old.x(), old.y(), old.z(), PALETTE[i++ % PALETTE.length], WaypointIcons.NONE));
			}
			data.worldNames.put(LEGACY_WORLD, "Imported (old waypoints)");
		}
		save();
	}

	public static void save() {
		ConfigManager.save(CONFIG_ID, data());
	}

	/**
	 * Identifies the world you're in: {@code mp:<address>} for a server, {@code sp:<save name>} for
	 * singleplayer, {@code realm:<name>} for a Realm - or null on the title screen. Also remembers a
	 * friendly name for it so the world picker can show it later from anywhere.
	 */
	public static String currentWorldKey() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return null;
		}
		String key;
		String label;
		if (ClientCompat.isSingleplayer()) {
			String save = ClientCompat.singleplayerWorldName();
			key = "sp:" + (save == null ? "world" : save);
			label = (save == null ? "Singleplayer" : save) + " (Singleplayer)";
		} else if (ClientCompat.isRealm()) {
			String name = ClientCompat.currentServerName();
			key = "realm:" + (name == null ? "realm" : name);
			label = (name == null ? "Realm" : name) + " (Realm)";
		} else {
			String address = ClientCompat.currentServerAddress();
			key = "mp:" + (address == null ? "unknown" : address.toLowerCase(Locale.ROOT));
			String name = ClientCompat.currentServerName();
			label = name != null && address != null && !name.equalsIgnoreCase(address) ? name + " (" + address + ")"
					: address != null ? address : "Server";
		}
		if (!label.equals(data().worldNames.get(key))) {
			data().worldNames.put(key, label);
			save();
		}
		return key;
	}

	public static String worldName(String key) {
		if (key == null) {
			return "No world";
		}
		String name = data().worldNames.get(key);
		return name != null ? name : key.substring(key.indexOf(':') + 1);
	}

	/** Every world that has at least one waypoint (plus the current one), current first. */
	public static List<String> knownWorlds() {
		List<String> worlds = new ArrayList<>();
		String current = currentWorldKey();
		if (current != null) {
			worlds.add(current);
		}
		for (Waypoint waypoint : data().waypoints) {
			if (!worlds.contains(waypoint.world)) {
				worlds.add(waypoint.world);
			}
		}
		return worlds;
	}

	public static List<Waypoint> all() {
		return data().waypoints;
	}

	public static List<Waypoint> inWorld(String world) {
		return data().waypoints.stream().filter(w -> w.world.equals(world)).toList();
	}

	public static List<Waypoint> inWorldAndDimension(String world, String dimension) {
		return data().waypoints.stream().filter(w -> w.world.equals(world) && w.dimension.equals(dimension)).toList();
	}

	/** Enabled waypoints in the world+dimension you're standing in - what gets rendered. */
	public static List<Waypoint> visibleHere() {
		String world = currentWorldKey();
		String dimension = ClientCompat.dimensionId();
		if (world == null || dimension == null) {
			return List.of();
		}
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint waypoint : data().waypoints) {
			if (waypoint.enabled && waypoint.world.equals(world) && waypoint.dimension.equals(dimension)) {
				out.add(waypoint);
			}
		}
		return out;
	}

	public static int nextColor() {
		String world = currentWorldKey();
		long count = world == null ? 0 : inWorld(world).size();
		return PALETTE[(int) (count % PALETTE.length)];
	}

	/** Adds a waypoint at the player's feet in the current world/dimension; null if not in a world. */
	public static Waypoint createHere(String name, int color, String icon) {
		MinecraftClient client = MinecraftClient.getInstance();
		String world = currentWorldKey();
		String dimension = ClientCompat.dimensionId();
		if (client.player == null || world == null || dimension == null) {
			return null;
		}
		String cleanName = name == null || name.isBlank() ? defaultName() : name.strip();
		Waypoint waypoint = new Waypoint(cleanName, world, dimension,
				Math.floor(client.player.getX()) + 0.5, Math.floor(client.player.getY()), Math.floor(client.player.getZ()) + 0.5,
				color, icon == null ? WaypointIcons.NONE : icon);
		data().waypoints.add(waypoint);
		save();
		return waypoint;
	}

	public static String defaultName() {
		String world = currentWorldKey();
		int count = world == null ? 1 : (int) inWorld(world).stream().filter(w -> !w.death).count() + 1;
		return "Waypoint " + count;
	}

	public static void add(Waypoint waypoint) {
		data().waypoints.add(waypoint);
		save();
	}

	public static void delete(Waypoint waypoint) {
		data().waypoints.removeIf(w -> w.id.equals(waypoint.id));
		save();
	}

	/** Deletes every automatic death waypoint in {@code world}; returns how many were removed. */
	public static int deleteDeaths(String world) {
		int before = data().waypoints.size();
		data().waypoints.removeIf(w -> w.death && w.world.equals(world));
		save();
		return before - data().waypoints.size();
	}

	public static void moveWorld(String fromWorld, String toWorld) {
		for (Waypoint waypoint : data().waypoints) {
			if (waypoint.world.equals(fromWorld)) {
				waypoint.world = toWorld;
			}
		}
		save();
	}

	/**
	 * Records a death: the newest death in this world is always called "Latest Death"; the previous
	 * one gets renamed to "Death - &lt;date time&gt;" so it's still findable but no longer ambiguous.
	 */
	public static Waypoint recordDeath(double x, double y, double z) {
		String world = currentWorldKey();
		String dimension = ClientCompat.dimensionId();
		if (world == null || dimension == null) {
			return null;
		}
		java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT);
		for (Waypoint waypoint : data().waypoints) {
			if (waypoint.death && waypoint.world.equals(world) && LATEST_DEATH.equals(waypoint.name)) {
				waypoint.name = "Death - " + format.format(java.time.Instant.ofEpochMilli(waypoint.created)
						.atZone(java.time.ZoneId.systemDefault()));
			}
		}
		Waypoint death = new Waypoint(LATEST_DEATH, world, dimension, Math.floor(x) + 0.5, Math.floor(y), Math.floor(z) + 0.5,
				DEATH_COLOR, WaypointIcons.SKULL);
		death.death = true;
		data().waypoints.add(death);
		save();
		return death;
	}

	public static List<Waypoint> sortedByDistance(List<Waypoint> waypoints) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return waypoints;
		}
		double px = client.player.getX();
		double py = client.player.getY();
		double pz = client.player.getZ();
		List<Waypoint> sorted = new ArrayList<>(waypoints);
		sorted.sort(Comparator.comparingDouble(w -> sq(w.x - px) + sq(w.y - py) + sq(w.z - pz)));
		return sorted;
	}

	private static double sq(double v) {
		return v * v;
	}

	/** The payload sent to friends when sharing (see SocialClient#shareWaypoint). */
	public static Map<String, Object> toShared(Waypoint waypoint) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", waypoint.name);
		map.put("x", waypoint.x);
		map.put("y", waypoint.y);
		map.put("z", waypoint.z);
		map.put("dimension", waypoint.dimension);
		map.put("world", waypoint.world);
		map.put("worldName", worldName(waypoint.world));
		map.put("color", waypoint.color);
		map.put("icon", waypoint.icon);
		return map;
	}

	/** Adds a waypoint a friend shared; it keeps the friend's world so it shows up on the same server. */
	public static Waypoint importShared(Map<String, Object> shared, String fromName) {
		String world = shared.get("world") instanceof String s ? s : currentWorldKey();
		if (world == null) {
			world = LEGACY_WORLD;
		}
		String dimension = shared.get("dimension") instanceof String s ? s : "minecraft:overworld";
		String name = shared.get("name") instanceof String s ? s : "Shared waypoint";
		Waypoint waypoint = new Waypoint(name + (fromName == null ? "" : " (" + fromName + ")"), world, dimension,
				number(shared.get("x")), number(shared.get("y")), number(shared.get("z")),
				shared.get("color") instanceof Number n ? n.intValue() | 0xFF000000 : nextColor(),
				shared.get("icon") instanceof String s ? s : WaypointIcons.NONE);
		if (!data().worldNames.containsKey(world) && shared.get("worldName") instanceof String label) {
			data().worldNames.put(world, label);
		}
		data().waypoints.add(waypoint);
		save();
		return waypoint;
	}

	private static double number(Object value) {
		return value instanceof Number n ? n.doubleValue() : 0;
	}

	/** Pretty dimension name for tabs and labels. */
	public static String dimensionLabel(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "The End";
			default -> {
				String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
				yield path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}
}
