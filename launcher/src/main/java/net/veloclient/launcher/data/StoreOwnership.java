package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Which {@code StoreCatalog} item ids the player owns - reads/writes the same {@code config/store-ownership.json} the mod's own {@code StoreOwnership} uses. */
public final class StoreOwnership {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private StoreOwnership() {
	}

	public static boolean owns(String itemId) {
		return owned().contains(itemId);
	}

	public static void grant(String itemId) {
		Set<String> owned = owned();
		owned.add(itemId);
		save(owned);
	}

	private static Set<String> owned() {
		Path file = VeloPaths.config().resolve("store-ownership.json");
		if (!Files.exists(file)) {
			return new LinkedHashSet<>();
		}
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Persisted persisted = GSON.fromJson(reader, Persisted.class);
			return persisted == null || persisted.ownedItemIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(persisted.ownedItemIds);
		} catch (IOException e) {
			return new LinkedHashSet<>();
		}
	}

	private static void save(Set<String> owned) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve("store-ownership.json");
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(new Persisted(owned), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save store ownership", e);
		}
	}

	private static final class Persisted {
		Set<String> ownedItemIds;

		Persisted(Set<String> ownedItemIds) {
			this.ownedItemIds = ownedItemIds;
		}
	}
}
