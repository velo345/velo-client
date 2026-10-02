package net.veloclient.launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * On-disk cache for launch metadata that never changes once published - a Minecraft version's
 * detail JSON and Fabric's loader profile for a pinned loader version. Saves the network
 * round-trips (and Mojang's ~300 KB version manifest) on every launch after the first, and lets
 * a profile launch offline once it has been launched online once.
 */
final class MetaCache {

	@FunctionalInterface
	interface Fetch {
		JsonObject get() throws IOException;
	}

	private MetaCache() {
	}

	static JsonObject getOrFetch(String key, Fetch fetch) throws IOException {
		Path file = VeloPaths.root().resolve("cache").resolve("meta").resolve(key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
		if (Files.exists(file)) {
			try {
				return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception corrupt) {
				Files.deleteIfExists(file);
			}
		}
		JsonObject fresh = fetch.get();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, fresh.toString(), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// Works uncached next time too.
		}
		return fresh;
	}
}
