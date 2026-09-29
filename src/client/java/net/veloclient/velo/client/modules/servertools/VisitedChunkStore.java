package net.veloclient.velo.client.modules.servertools;

import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persists which chunks (by "dimension@chunkX,chunkZ" key) this client has ever received a load packet for, to {@code ~/.velo-client/config/chunk-load-profiler.json} - written in batches, not on every new chunk, see {@link ChunkLoadProfilerModule}. */
final class VisitedChunkStore {

	private static final String MODULE_ID = "chunk-load-profiler";

	private VisitedChunkStore() {
	}

	record Data(List<String> visited) {
	}

	static Set<String> load() {
		Data data = ConfigManager.load(MODULE_ID, Data.class, new Data(new ArrayList<>()));
		return new HashSet<>(data.visited());
	}

	static void save(Set<String> visited) {
		ConfigManager.save(MODULE_ID, new Data(new ArrayList<>(visited)));
	}
}
