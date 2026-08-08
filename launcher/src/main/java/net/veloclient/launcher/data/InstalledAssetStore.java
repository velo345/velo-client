package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-instance, per-kind registry of {@link InstalledAsset}s, one JSON file
 * per {@link Kind} living next to that kind's own folder inside the instance
 * directory (mirrors {@code InstanceStore}'s Gson-to-file style).
 */
public final class InstalledAssetStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Kind {
		MOD("installed-mods.json", "mod"),
		RESOURCE_PACK("installed-resourcepacks.json", "resourcepack"),
		SHADER_PACK("installed-shaderpacks.json", "shader");

		private final String fileName;
		private final String modrinthProjectType;

		Kind(String fileName, String modrinthProjectType) {
			this.fileName = fileName;
			this.modrinthProjectType = modrinthProjectType;
		}

		public String modrinthProjectType() {
			return modrinthProjectType;
		}
	}

	private InstalledAssetStore() {
	}

	private static Path file(String instanceId, Kind kind) {
		return InstancePaths.dir(instanceId).resolve(kind.fileName);
	}

	public static List<InstalledAsset> loadAll(String instanceId, Kind kind) {
		Path file = file(instanceId, kind);
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<InstalledAsset> assets = GSON.fromJson(reader, new TypeToken<List<InstalledAsset>>() {}.getType());
			return assets != null ? assets : new ArrayList<>();
		} catch (IOException | JsonParseException e) {
			// A corrupt registry (partial write, manual edit, ...) used to throw
			// JsonSyntaxException uncaught here - not an IOException, so this
			// permanently broke that one profile's Mods/Resource Packs/Shader
			// Packs tab on every subsequent open, looking exactly like "clicking
			// the profile does nothing" from the user's side. Treat it the same
			// as "no registry yet" instead.
			return new ArrayList<>();
		}
	}

	public static void saveAll(String instanceId, Kind kind, List<InstalledAsset> assets) {
		try {
			Files.createDirectories(InstancePaths.dir(instanceId));
			try (Writer writer = Files.newBufferedWriter(file(instanceId, kind), StandardCharsets.UTF_8)) {
				GSON.toJson(assets, writer);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to save " + kind + " registry for instance " + instanceId, e);
		}
	}

	/** Records/replaces one asset's metadata (matched by filename), for after a Modrinth install/update. */
	public static void record(String instanceId, Kind kind, InstalledAsset asset) {
		List<InstalledAsset> assets = loadAll(instanceId, kind);
		assets.removeIf(a -> a.filename().equals(asset.filename()));
		assets.add(asset);
		saveAll(instanceId, kind, assets);
	}

	/** Drops the record for a filename that was removed/replaced on disk (no-op if it was never recorded). */
	public static void forget(String instanceId, Kind kind, String filename) {
		List<InstalledAsset> assets = loadAll(instanceId, kind);
		if (assets.removeIf(a -> a.filename().equals(filename))) {
			saveAll(instanceId, kind, assets);
		}
	}

	public static java.util.Map<String, InstalledAsset> asMap(List<InstalledAsset> assets) {
		java.util.Map<String, InstalledAsset> map = new java.util.LinkedHashMap<>();
		for (InstalledAsset asset : assets) {
			map.put(asset.filename(), asset);
		}
		return map;
	}
}
