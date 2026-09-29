package net.veloclient.velo.client.modules.hud;

import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists found containers to {@code ~/.velo-client/config/storage-finder.json}, keyed so re-scanning the same position never duplicates it. */
final class StorageFinderStore {

	private static final String MODULE_ID = "storage-finder";

	private StorageFinderStore() {
	}

	record Data(List<StorageEntry> found) {
	}

	static Map<String, StorageEntry> load() {
		Data data = ConfigManager.load(MODULE_ID, Data.class, new Data(new ArrayList<>()));
		Map<String, StorageEntry> byKey = new LinkedHashMap<>();
		for (StorageEntry entry : data.found()) {
			byKey.put(entry.key(), entry);
		}
		return byKey;
	}

	static void save(Map<String, StorageEntry> byKey) {
		ConfigManager.save(MODULE_ID, new Data(new ArrayList<>(byKey.values())));
	}
}
