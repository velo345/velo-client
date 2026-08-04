package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists the launcher's own "My Servers" list to
 * {@code ~/.velo-client/config/servers.json} - separate from vanilla's
 * {@code servers.dat} (different format, NBT vs JSON, and we don't want to
 * touch the user's actual Minecraft server list) but conceptually the same
 * idea: named, saved addresses you can quickly get back into.
 */
public final class SavedServerStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "servers.json";

	private SavedServerStore() {
	}

	public static List<SavedServer> loadAll() {
		Path file = VeloPaths.config().resolve(FILE_NAME);
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<SavedServer> servers = GSON.fromJson(reader, new TypeToken<List<SavedServer>>() {}.getType());
			return servers != null ? servers : new ArrayList<>();
		} catch (IOException e) {
			return new ArrayList<>();
		}
	}

	public static void saveAll(List<SavedServer> servers) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve(FILE_NAME);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(servers, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save server list", e);
		}
	}

	public static SavedServer add(String name, String host, int port, String instanceId) {
		List<SavedServer> servers = loadAll();
		SavedServer entry = new SavedServer(UUID.randomUUID().toString(), name, host, port, instanceId);
		servers.add(entry);
		saveAll(servers);
		return entry;
	}

	public static void update(SavedServer updated) {
		List<SavedServer> servers = loadAll();
		servers.replaceAll(s -> s.id().equals(updated.id()) ? updated : s);
		saveAll(servers);
	}

	public static void remove(SavedServer toRemove) {
		List<SavedServer> servers = loadAll();
		servers.removeIf(s -> s.id().equals(toRemove.id()));
		saveAll(servers);
	}
}
