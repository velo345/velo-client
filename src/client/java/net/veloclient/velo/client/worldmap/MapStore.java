package net.veloclient.velo.client.worldmap;

import net.veloclient.velo.VeloClient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The explored map of one world + dimension + layer ("surface" or "cave-&lt;band&gt;"), as regions on
 * disk ({@code ~/.velo-client/worldmap/<world>/<dimension>/<layer>/r.X.Z.bin}, gzip). Only the
 * most recently used regions are kept in memory; the rest load on demand.
 */
final class MapStore {

	private static final int MAX_LOADED = 40;
	private static final int MAGIC = 0x56454C4F;
	private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "velo-worldmap-save");
		thread.setDaemon(true);
		return thread;
	});

	final Path dir;
	final String layer;
	private final List<String> palette;
	private final Path paletteFile;
	private final Map<Long, MapRegion> loaded = new LinkedHashMap<>(64, 0.75f, true);
	private final java.util.Set<Long> missing = new java.util.HashSet<>();

	MapStore(Path dimensionDir, String layer) {
		this.dir = dimensionDir.resolve(layer);
		this.layer = layer;
		this.paletteFile = dimensionDir.resolve("biomes.txt");
		List<String> read = new ArrayList<>();
		try {
			if (Files.exists(paletteFile)) {
				read.addAll(Files.readAllLines(paletteFile, StandardCharsets.UTF_8));
			}
		} catch (IOException ignored) {
			// start fresh
		}
		this.palette = read;
	}

	synchronized int biomeIndex(String id) {
		int index = palette.indexOf(id);
		if (index < 0) {
			palette.add(id);
			index = palette.size() - 1;
			List<String> copy = List.copyOf(palette);
			SAVER.submit(() -> {
				try {
					Files.createDirectories(paletteFile.getParent());
					Files.write(paletteFile, copy, StandardCharsets.UTF_8);
				} catch (IOException ignored) {
					// retried next time a biome is added
				}
			});
		}
		return index;
	}

	synchronized String biomeName(int index) {
		return index >= 0 && index < palette.size() ? palette.get(index) : "";
	}

	/** The region, loading it from disk; {@code create} makes an empty one if there's none. */
	MapRegion region(int rx, int rz, boolean create) {
		long key = MapRegion.key(rx, rz);
		MapRegion region = loaded.get(key);
		if (region != null) {
			return region;
		}
		if (!create && missing.contains(key)) {
			return null;
		}
		region = read(rx, rz);
		if (region == null) {
			if (!create) {
				missing.add(key);
				return null;
			}
			region = new MapRegion(rx, rz);
		}
		missing.remove(key);
		loaded.put(key, region);
		while (loaded.size() > MAX_LOADED) {
			Long eldest = loaded.keySet().iterator().next();
			MapRegion out = loaded.remove(eldest);
			if (out.dirty) {
				save(out);
			}
		}
		return region;
	}

	private Path file(int rx, int rz) {
		return dir.resolve("r." + rx + "." + rz + ".bin");
	}

	private MapRegion read(int rx, int rz) {
		Path file = file(rx, rz);
		if (!Files.exists(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC) {
				return null;
			}
			in.readInt(); // format version
			MapRegion region = new MapRegion(rx, rz);
			for (int i = 0; i < region.rgb.length; i++) {
				region.rgb[i] = in.readInt();
			}
			for (int i = 0; i < region.height.length; i++) {
				region.height[i] = in.readShort();
			}
			for (int i = 0; i < region.biome.length; i++) {
				region.biome[i] = in.readShort();
			}
			return region;
		} catch (IOException e) {
			VeloClient.LOGGER.warn("Velo world map: couldn't read {}", file, e);
			return null;
		}
	}

	private void save(MapRegion region) {
		region.dirty = false;
		int[] rgb = region.rgb.clone();
		short[] height = region.height.clone();
		short[] biome = region.biome.clone();
		Path file = file(region.rx, region.rz);
		SAVER.submit(() -> {
			try {
				Files.createDirectories(file.getParent());
				Path temp = file.resolveSibling(file.getFileName() + ".tmp");
				try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
					out.writeInt(MAGIC);
					out.writeInt(1);
					for (int v : rgb) {
						out.writeInt(v);
					}
					for (short v : height) {
						out.writeShort(v);
					}
					for (short v : biome) {
						out.writeShort(v);
					}
				}
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				VeloClient.LOGGER.warn("Velo world map: couldn't save {}", file, e);
			}
		});
	}

	/** Writes every changed region (called periodically and when leaving the world). */
	void saveDirty() {
		for (MapRegion region : loaded.values()) {
			if (region.dirty) {
				save(region);
			}
		}
	}
}
