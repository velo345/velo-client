package net.veloclient.velo.dev.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.config.VeloPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Storage Memory remembers, one file per server ({@code config/storage-memory/<server>.json}). A server holds
 * one {@link World} per backend world: behind a proxy (hub, survival, skyblock... all on one address) the hashed seed
 * the server sends on every switch tells them apart, so chests from one sub-server never show up as being "here" on
 * another. Containers, the ender chest, villager trades and work stations all belong to such a world.
 */
public final class StorageStore {

	/** One item, as stored. {@code in}: what a shulker box / bundle holds. */
	public static final class Item {
		public String id;
		public int c;
		/** Custom name (renamed / plugin item), or null. */
		public String n;
		/** Enchantments as {@code id=level}, or null. */
		public List<String> e;
		public List<Item> in;
		/** Custom item model (server items), or null. */
		public String m;

		/** Groups by item and custom name: a renamed / custom server item is its own entry. */
		public String key() {
			return n == null ? id : id + "|" + n;
		}
	}

	public static final class Container {
		public String dim;
		public int x, y, z;
		/** Other half of a double chest, or null. */
		public int[] other;
		public String block;
		public long time;
		public List<Item> items = new ArrayList<>();
		/** Party member who saw it last (Party Sync), null = you. */
		public String by;
	}

	public static final class Offer {
		public Item a, b, r;
		public int uses, max;
	}

	public static final class Trader {
		public String uuid;
		public String dim;
		public double x, y, z;
		public String profession;
		public int level;
		public String name;
		public boolean wandering;
		public long time;
		/** Party member who traded with it last, null = you. */
		public String by;
		public List<Offer> offers = new ArrayList<>();
	}

	public static final class Station {
		public String dim;
		public int x, y, z;
		public String block;
		public long time;
	}

	public static final class World {
		public String name;
		public long firstSeen, lastSeen;
		public Map<String, Container> containers = new LinkedHashMap<>();
		public List<Item> ender;
		public long enderTime;
		public Map<String, Trader> traders = new LinkedHashMap<>();
		public Map<String, Station> stations = new LinkedHashMap<>();
		/** Containers that were broken / forgotten (pos key -> when), so Party Sync doesn't bring them back. */
		public Map<String, Long> gone = new LinkedHashMap<>();
	}

	public static final class Server {
		public String key;
		public String label;
		public Map<String, World> worlds = new LinkedHashMap<>();
	}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, Server> LOADED = new LinkedHashMap<>();
	private static final List<String> DIRTY = new ArrayList<>();
	private static long lastSave;
	/** Bumped on every change, so screens can rebuild their indexes only when needed. */
	private static int version;

	private StorageStore() {
	}

	public static int version() {
		return version;
	}

	private static Path dir() {
		return VeloPaths.config().resolve("storage-memory");
	}

	private static String fileName(String serverKey) {
		return serverKey.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
	}

	/** {@code mp:play.example.net}, {@code sp:My World}... (same keys as the waypoints) - null outside a world. */
	public static String currentServerKey() {
		return WaypointManager.currentWorldKey();
	}

	/** The backend world you're in: its hashed seed (one per world, the same in all its dimensions). */
	public static String currentWorldKey() {
		if (!StorageMc.inWorld()) {
			return null;
		}
		return Long.toHexString(StorageMc.seedHash());
	}

	public static Server server(String key) {
		if (key == null) {
			return null;
		}
		return LOADED.computeIfAbsent(key, k -> {
			Server server = null;
			Path file = dir().resolve(fileName(k));
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					server = GSON.fromJson(reader, Server.class);
				} catch (Exception e) {
					server = null;
				}
			}
			if (server == null) {
				server = new Server();
			}
			server.key = k;
			if (server.worlds == null) {
				server.worlds = new LinkedHashMap<>();
			}
			for (World world : server.worlds.values()) {
				if (world.containers == null) {
					world.containers = new LinkedHashMap<>();
				}
				if (world.traders == null) {
					world.traders = new LinkedHashMap<>();
				}
				if (world.stations == null) {
					world.stations = new LinkedHashMap<>();
				}
				if (world.gone == null) {
					world.gone = new LinkedHashMap<>();
				}
				// Week-old tombstones have done their job.
				long cutoff = System.currentTimeMillis() - 7L * 24 * 3600_000;
				world.gone.values().removeIf(t -> t == null || t < cutoff);
			}
			return server;
		});
	}

	/** Every server with saved storage (files on disk plus what's loaded). */
	public static List<String> knownServers() {
		List<String> keys = new ArrayList<>(LOADED.keySet());
		try (var files = Files.list(dir())) {
			files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
				try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					Server server = GSON.fromJson(reader, Server.class);
					if (server != null && server.key != null && !keys.contains(server.key)) {
						keys.add(server.key);
					}
				} catch (Exception ignored) {
					// unreadable file: skip it
				}
			});
		} catch (Exception ignored) {
			// no folder yet
		}
		return keys;
	}

	public static String serverLabel(String key) {
		Server server = server(key);
		if (server != null && server.label != null) {
			return server.label;
		}
		return WaypointManager.worldName(key);
	}

	/** The world you're standing in (created on first visit), or null outside a world. */
	public static World current() {
		String serverKey = currentServerKey();
		String worldKey = currentWorldKey();
		if (serverKey == null || worldKey == null) {
			return null;
		}
		Server server = server(serverKey);
		server.label = WaypointManager.worldName(serverKey);
		World world = server.worlds.get(worldKey);
		long now = System.currentTimeMillis();
		if (world == null) {
			world = new World();
			world.firstSeen = now;
			world.name = server.worlds.isEmpty() ? "Main world" : "World " + (char) ('A' + Math.min(25, server.worlds.size()));
			server.worlds.put(worldKey, world);
			markDirty(serverKey);
		}
		if (now - world.lastSeen > 60_000) {
			world.lastSeen = now;
			markDirty(serverKey);
		}
		return world;
	}

	/** Removes a container and remembers that it's gone (for Party Sync). */
	public static void removeContainer(World world, String key) {
		if (world.containers.remove(key) != null) {
			world.gone.put(key, System.currentTimeMillis());
		}
	}

	public static String posKey(String dim, int x, int y, int z) {
		return dim + "|" + x + "|" + y + "|" + z;
	}

	public static void markDirty(String serverKey) {
		version++;
		if (serverKey != null && !DIRTY.contains(serverKey)) {
			DIRTY.add(serverKey);
		}
	}

	public static void markCurrentDirty() {
		markDirty(currentServerKey());
	}

	/** Writes changed servers, at most every few seconds unless {@code now}. */
	public static void flush(boolean now) {
		if (DIRTY.isEmpty() || !now && System.currentTimeMillis() - lastSave < 5000) {
			return;
		}
		lastSave = System.currentTimeMillis();
		try {
			Files.createDirectories(dir());
		} catch (Exception e) {
			return;
		}
		for (String key : List.copyOf(DIRTY)) {
			Server server = LOADED.get(key);
			if (server == null) {
				continue;
			}
			Path file = dir().resolve(fileName(key));
			Path temp = dir().resolve(fileName(key) + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(server, writer);
			} catch (Exception e) {
				continue;
			}
			try {
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception e) {
				try {
					Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
					continue;
				}
			}
			DIRTY.remove(key);
		}
	}

	public static void forgetWorld(String serverKey, String worldKey) {
		Server server = server(serverKey);
		if (server != null && server.worlds.remove(worldKey) != null) {
			markDirty(serverKey);
		}
	}

	public static String dimensionLabel(String dim) {
		if (dim == null) {
			return "";
		}
		return switch (dim) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> pretty(dim);
		};
	}

	/** "minecraft:blast_furnace" -> "Blast Furnace". */
	public static String pretty(String id) {
		if (id == null) {
			return "";
		}
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		StringBuilder out = new StringBuilder();
		for (String part : path.split("[_/]")) {
			if (part.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	public static String here() {
		return ClientCompat.dimensionId();
	}
}
