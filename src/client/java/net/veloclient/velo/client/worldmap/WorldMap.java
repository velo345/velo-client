package net.veloclient.velo.client.worldmap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.config.VeloPaths;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Records the world as you explore it, for the world map: every chunk that loads around you is
 * read (top block color, height, biome) into the current world + dimension's map - a few chunks per
 * tick so it never causes a lag spike. While you're underground (or in the Nether) the layer at
 * your height is recorded too, as the "Caves" layer. Saved to disk every 30 seconds and when you
 * leave the world.
 */
public final class WorldMap {

	/** Biome id at a block ("minecraft:plains"), "" when unknown - for the Better F3 screen. */
	public static String biomeAt(int x, int y, int z) {
		return MapCompat.biome(x, y, z);
	}

	private static final int CHUNKS_PER_TICK = 8;
	private static final LinkedHashSet<Long> QUEUE = new LinkedHashSet<>();
	private static final Map<String, MapStore> STORES = new HashMap<>();
	private static String worldKey;
	private static String dimension;
	private static int ticks;

	private WorldMap() {
	}

	public static void register() {
		MapCompat.onChunkLoad(chunk -> {
			synchronized (QUEUE) {
				QUEUE.add(chunkKey(MapCompat.chunkX(chunk), MapCompat.chunkZ(chunk)));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	private static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	static Path root() {
		return VeloPaths.root().resolve("worldmap");
	}

	static String safe(String name) {
		return name.replaceAll("[^A-Za-z0-9._-]+", "_");
	}

	/** The world currently being recorded, or null when not in a world. */
	static String currentWorld() {
		return worldKey;
	}

	static String currentDimension() {
		return dimension;
	}

	/** Store for a world/dimension/layer (cached). */
	static MapStore store(String world, String dim, String layer) {
		String key = world + "|" + dim + "|" + layer;
		return STORES.computeIfAbsent(key, k -> new MapStore(root().resolve(safe(world)).resolve(safe(dim)), layer));
	}

	private static void tick() {
		MinecraftClient client = MinecraftClient.getInstance();
		String world = WaypointManager.currentWorldKey();
		String dim = world == null ? null : ClientCompat.dimensionId();
		if (world == null || dim == null) {
			if (worldKey != null) {
				flush();
				worldKey = null;
				dimension = null;
				STORES.clear();
				synchronized (QUEUE) {
					QUEUE.clear();
				}
			}
			return;
		}
		if (!world.equals(worldKey) || !dim.equals(dimension)) {
			flush();
			worldKey = world;
			dimension = dim;
		}
		ticks++;
		if (!WorldMapModule.recording()) {
			return;
		}
		double[] player = MapCompat.player();
		if (player == null) {
			return;
		}
		int pcx = (int) Math.floor(player[0]) >> 4;
		int pcz = (int) Math.floor(player[2]) >> 4;
		// Re-read the area around the player now and then, to catch blocks that changed.
		if (ticks % 100 == 0) {
			synchronized (QUEUE) {
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						QUEUE.add(chunkKey(pcx + dx, pcz + dz));
					}
				}
			}
		}
		MapStore surface = store(world, dim, "surface");
		boolean ceiling = MapCompat.hasCeiling();
		int minY = MapCompat.bottomY();
		for (int i = 0; i < CHUNKS_PER_TICK; i++) {
			long key;
			synchronized (QUEUE) {
				if (QUEUE.isEmpty()) {
					break;
				}
				var it = QUEUE.iterator();
				key = it.next();
				it.remove();
			}
			int cx = (int) (key >> 32);
			int cz = (int) key;
			Object chunk = MapCompat.loadedChunk(cx, cz);
			if (chunk != null) {
				// The Nether's surface is its roof - record the first floor below it instead.
				scanChunk(surface, chunk, cx, cz, ceiling ? 100 : 0, minY, !ceiling);
			}
		}
		// Caves: the layer at the player's height, while underground.
		if (ticks % 40 == 0 && WorldMapModule.recordCaves()) {
			int py = (int) Math.floor(player[1]);
			boolean underground = ceiling;
			if (!underground) {
				MapRegion r = surface.region(Math.floorDiv((int) Math.floor(player[0]), MapRegion.SIZE),
						Math.floorDiv((int) Math.floor(player[2]), MapRegion.SIZE), false);
				if (r != null) {
					int lx = Math.floorMod((int) Math.floor(player[0]), MapRegion.SIZE);
					int lz = Math.floorMod((int) Math.floor(player[2]), MapRegion.SIZE);
					underground = r.rgb[lz * MapRegion.SIZE + lx] != 0 && py < r.height[lz * MapRegion.SIZE + lx] - 6;
				}
			}
			if (underground) {
				int band = Math.floorDiv(py, 16);
				MapStore caves = store(world, dim, "cave" + band);
				for (int dx = -4; dx <= 4; dx++) {
					for (int dz = -4; dz <= 4; dz++) {
						Object chunk = MapCompat.loadedChunk(pcx + dx, pcz + dz);
						if (chunk != null) {
							scanChunk(caves, chunk, pcx + dx, pcz + dz, band * 16 + 18, Math.max(minY, band * 16 - 24), false);
						}
					}
				}
			}
		}
		if (ticks % 600 == 0) {
			flush();
		}
	}

	private static void scanChunk(MapStore store, Object chunk, int cx, int cz, int startY, int minY, boolean fromHeightmap) {
		int baseX = cx << 4;
		int baseZ = cz << 4;
		MapRegion region = store.region(Math.floorDiv(baseX, MapRegion.SIZE), Math.floorDiv(baseZ, MapRegion.SIZE), true);
		int[] biomes = new int[16];
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				MapCompat.Column column = MapCompat.column(chunk, lx, lz, startY, minY, fromHeightmap);
				int cell = (lz >> 2) * 4 + (lx >> 2);
				if ((lx & 3) == 0 && (lz & 3) == 0) {
					biomes[cell] = store.biomeIndex(MapCompat.biome(baseX + lx, column.y(), baseZ + lz));
				}
				int color = column.rgb();
				if (column.waterDepth() > 0) {
					// Deeper water reads darker, like vanilla maps.
					color = shade(color, 1f - Math.min(column.waterDepth(), 10) * 0.045f);
				}
				region.set(Math.floorMod(baseX + lx, MapRegion.SIZE), Math.floorMod(baseZ + lz, MapRegion.SIZE), color,
						column.ground(), biomes[cell]);
			}
		}
	}

	static int shade(int rgb, float factor) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * factor));
		int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * factor));
		int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
		return r << 16 | g << 8 | b;
	}

	/** Saves everything changed (periodically, when leaving a world, and when the map closes). */
	public static void flush() {
		for (MapStore store : STORES.values()) {
			store.saveDirty();
		}
	}
}
