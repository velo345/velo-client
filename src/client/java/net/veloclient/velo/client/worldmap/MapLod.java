package net.veloclient.velo.client.worldmap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The zoomed-out world map: a pyramid of 256x256 tiles, level L covering 2^L x 2^L regions (each texel 2^L
 * blocks). The map picks the level whose texels match the screen's pixels, so far out it draws a handful of
 * small textures instead of thousands of full-detail regions - same sharpness on the monitor, a fraction of
 * the work. Tiles are built off the render thread (level 1 from region files, higher levels from the four
 * tiles below) and uploaded a few per frame.
 */
final class MapLod {

	static final int TILE = MapRegion.SIZE;
	static final int MAX_LEVEL = 7;
	private static final int MAX_PIXELS = 192;
	private static final int MAX_TEXTURES = 96;
	private static final int UPLOADS_PER_FRAME = 3;
	private static final int[] EMPTY = new int[0];

	private record Key(String dir, MapTextures.Style style, int level, int tx, int tz) {
	}

	private record Entry(Identifier id, NativeImageBackedTexture texture) {
	}

	/** Which tiles have anything in them, per level (index 0 = regions), for one store - from its files. */
	private record Index(Set<Long>[] levels) {
	}

	private static final Map<Key, int[]> PIXELS = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true));
	private static final Set<Key> PENDING = ConcurrentHashMap.newKeySet();
	private static final Map<Key, Entry> GPU = new LinkedHashMap<>(128, 0.75f, true);
	private static final Map<String, Index> INDEX = new ConcurrentHashMap<>();
	/** Regions in memory when the map opened (newer than their files) - the tiles read these first. */
	private static final Map<String, Map<Long, MapRegion>> LIVE = new ConcurrentHashMap<>();
	private static final Map<String, Integer> SEEN_VERSIONS = new HashMap<>();
	private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-worldmap-lod");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private static int uploadsThisFrame;
	private static int counter;

	private MapLod() {
	}

	static void beginFrame() {
		uploadsThisFrame = 0;
	}

	/** The map opened or switched layer: learn what's on disk and drop tiles over regions changed since. Render thread. */
	static void refresh(MapStore store) {
		Map<Long, MapRegion> live = new ConcurrentHashMap<>();
		Set<Long> changed = new HashSet<>();
		for (MapRegion region : store.loadedRegions()) {
			long key = MapRegion.key(region.rx, region.rz);
			live.put(key, region);
			Integer seen = SEEN_VERSIONS.put(store.dir + "|" + key, region.version);
			if (seen == null || seen != region.version) {
				changed.add(key);
			}
		}
		LIVE.put(store.dir.toString(), live);
		String dir = store.dir.toString();
		if (!changed.isEmpty()) {
			synchronized (PIXELS) {
				PIXELS.keySet().removeIf(k -> k.dir.equals(dir) && covers(k, changed));
			}
			var it = GPU.entrySet().iterator();
			while (it.hasNext()) {
				var e = it.next();
				if (e.getKey().dir.equals(dir) && covers(e.getKey(), changed)) {
					MinecraftClient.getInstance().getTextureManager().destroyTexture(e.getValue().id);
					it.remove();
				}
			}
		}
		WORKER.submit(() -> {
			Set<Long> regions = store.regionsOnDisk();
			regions.addAll(live.keySet());
			@SuppressWarnings("unchecked")
			Set<Long>[] levels = new Set[MAX_LEVEL + 1];
			levels[0] = regions;
			for (int level = 1; level <= MAX_LEVEL; level++) {
				Set<Long> tiles = new HashSet<>();
				for (long key : regions) {
					tiles.add(MapRegion.key((int) (key >> 32) >> level, (int) key >> level));
				}
				levels[level] = tiles;
			}
			INDEX.put(dir, new Index(levels));
		});
	}

	private static boolean covers(Key key, Set<Long> regions) {
		for (long region : regions) {
			if ((int) (region >> 32) >> key.level == key.tx && (int) region >> key.level == key.tz) {
				return true;
			}
		}
		return false;
	}

	/** Whether that tile (level >= 1) has anything explored in it; true while the file list is still loading. */
	static boolean mayHave(MapStore store, int level, int tx, int tz) {
		Index index = INDEX.get(store.dir.toString());
		return index == null || index.levels[level].contains(MapRegion.key(tx, tz));
	}

	/** Whether the file list for this store is known yet. */
	static boolean indexed(MapStore store) {
		return INDEX.containsKey(store.dir.toString());
	}

	/** Any region explored at all in this store (false only once the file list is known). */
	static boolean anything(MapStore store) {
		Index index = INDEX.get(store.dir.toString());
		return index == null || !index.levels[0].isEmpty();
	}

	/** The tile's texture if it's ready (else null; it's being built). Render thread. */
	static Identifier texture(MapStore store, MapTextures.Style style, int level, int tx, int tz) {
		Key key = new Key(store.dir.toString(), style, level, tx, tz);
		Entry entry = GPU.get(key);
		if (entry != null) {
			return entry.id;
		}
		int[] pixels = PIXELS.get(key);
		if (pixels == null) {
			if (INDEX.containsKey(key.dir) && PENDING.add(key)) {
				WORKER.submit(() -> {
					try {
						build(store, key);
					} catch (Throwable t) {
						VeloClient.LOGGER.warn("Velo world map: couldn't build a zoomed-out tile", t);
					} finally {
						PENDING.remove(key);
					}
				});
			}
			return null;
		}
		if (pixels == EMPTY || uploadsThisFrame >= UPLOADS_PER_FRAME) {
			return null;
		}
		uploadsThisFrame++;
		NativeImage image = new NativeImage(TILE, TILE, true);
		for (int z = 0; z < TILE; z++) {
			for (int x = 0; x < TILE; x++) {
				image.setColorArgb(x, z, pixels[z * TILE + x]);
			}
		}
		Identifier id = Identifier.of("velo-client", "worldmap_lod_" + (counter++));
		NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "velo world map (zoomed out)", image);
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
		GPU.put(key, new Entry(id, texture));
		while (GPU.size() > MAX_TEXTURES) {
			Key eldest = GPU.keySet().iterator().next();
			MinecraftClient.getInstance().getTextureManager().destroyTexture(GPU.remove(eldest).id);
		}
		return id;
	}

	/** An already-uploaded tile, without building anything (the stand-in while a sharper one is on its way). */
	static Identifier cached(MapStore store, MapTextures.Style style, int level, int tx, int tz) {
		Entry entry = GPU.get(new Key(store.dir.toString(), style, level, tx, tz));
		return entry == null ? null : entry.id;
	}

	static void clear() {
		for (Entry entry : GPU.values()) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(entry.id);
		}
		GPU.clear();
		PIXELS.clear();
	}

	// ---- Building (worker threads) ----

	private static int[] build(MapStore store, Key key) {
		int[] cached = PIXELS.get(key);
		if (cached != null) {
			return cached;
		}
		Index index = INDEX.get(key.dir);
		int[] out = new int[TILE * TILE];
		boolean any = false;
		for (int q = 0; q < 4; q++) {
			int cx = key.tx * 2 + (q & 1);
			int cz = key.tz * 2 + (q >> 1);
			if (index != null && !index.levels[key.level - 1].contains(MapRegion.key(cx, cz))) {
				continue;
			}
			int[] child = key.level == 1 ? regionPixels(store, key.style, cx, cz)
					: build(store, new Key(key.dir, key.style, key.level - 1, cx, cz));
			if (child == null || child == EMPTY) {
				continue;
			}
			any |= downsampleInto(child, out, (q & 1) * TILE / 2, (q >> 1) * TILE / 2);
		}
		int[] result = any ? out : EMPTY;
		PIXELS.put(key, result);
		synchronized (PIXELS) {
			while (PIXELS.size() > MAX_PIXELS) {
				PIXELS.remove(PIXELS.keySet().iterator().next());
			}
		}
		return result;
	}

	private static int[] regionPixels(MapStore store, MapTextures.Style style, int rx, int rz) {
		Map<Long, MapRegion> live = LIVE.get(store.dir.toString());
		MapRegion region = live == null ? null : live.get(MapRegion.key(rx, rz));
		if (region == null) {
			region = store.readUncached(rx, rz);
		}
		if (region == null) {
			return null;
		}
		int[] pixels = new int[TILE * TILE];
		for (int z = 0; z < TILE; z++) {
			for (int x = 0; x < TILE; x++) {
				pixels[z * TILE + x] = MapTextures.color(store, region, style, x, z);
			}
		}
		return pixels;
	}

	/** Halves a tile into a quarter of {@code out}: each texel the average of the explored ones of its 2x2. */
	private static boolean downsampleInto(int[] child, int[] out, int ox, int oz) {
		boolean any = false;
		int half = TILE / 2;
		for (int z = 0; z < half; z++) {
			for (int x = 0; x < half; x++) {
				int r = 0;
				int g = 0;
				int b = 0;
				int n = 0;
				for (int d = 0; d < 4; d++) {
					int c = child[(z * 2 + (d >> 1)) * TILE + x * 2 + (d & 1)];
					if (c != 0) {
						r += (c >> 16) & 0xFF;
						g += (c >> 8) & 0xFF;
						b += c & 0xFF;
						n++;
					}
				}
				if (n > 0) {
					out[(oz + z) * TILE + ox + x] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
					any = true;
				}
			}
		}
		return any;
	}
}
