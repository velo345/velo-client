package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of Modrinth-sourced {@link DatapackAsset}s for one world's {@code
 * datapacks/} folder - mirrors {@link InstalledAssetStore}'s shape, but
 * keyed by an arbitrary world path instead of instanceId+Kind, since
 * datapacks live per-world (a profile can have several worlds), not
 * per-profile like mods/resource packs/shaders.
 */
public final class DatapackRegistry {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = ".velo-datapacks.json";

	private DatapackRegistry() {
	}

	private static Path file(Path datapacksDir) {
		return datapacksDir.resolve(FILE_NAME);
	}

	public static List<DatapackAsset> loadAll(Path datapacksDir) {
		Path file = file(datapacksDir);
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<DatapackAsset> assets = GSON.fromJson(reader, new TypeToken<List<DatapackAsset>>() {}.getType());
			return assets != null ? assets : new ArrayList<>();
		} catch (IOException | JsonParseException e) {
			return new ArrayList<>();
		}
	}

	public static void saveAll(Path datapacksDir, List<DatapackAsset> assets) {
		try {
			Files.createDirectories(datapacksDir);
			try (Writer writer = Files.newBufferedWriter(file(datapacksDir), StandardCharsets.UTF_8)) {
				GSON.toJson(assets, writer);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to save datapack registry for " + datapacksDir, e);
		}
	}

	public static void record(Path datapacksDir, DatapackAsset asset) {
		List<DatapackAsset> assets = loadAll(datapacksDir);
		assets.removeIf(a -> a.filename().equals(asset.filename()));
		assets.add(asset);
		saveAll(datapacksDir, assets);
	}

	public static void forget(Path datapacksDir, String filename) {
		List<DatapackAsset> assets = loadAll(datapacksDir);
		if (assets.removeIf(a -> a.filename().equals(filename))) {
			saveAll(datapacksDir, assets);
		}
	}

	public static Map<String, DatapackAsset> asMap(List<DatapackAsset> assets) {
		Map<String, DatapackAsset> map = new LinkedHashMap<>();
		for (DatapackAsset asset : assets) {
			map.put(asset.filename(), asset);
		}
		return map;
	}
}
