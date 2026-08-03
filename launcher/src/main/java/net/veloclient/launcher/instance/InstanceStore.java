package net.veloclient.launcher.instance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Reads/writes {@code ~/.velo-client/instances/<id>/instance.json}, mirroring {@code ProfileStore}'s style. */
public final class InstanceStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private InstanceStore() {
	}

	public static List<Instance> loadAll() {
		InstancePaths.ensureRoot();
		List<Instance> instances = new ArrayList<>();
		Path root = InstancePaths.root();
		if (!Files.isDirectory(root)) {
			return instances;
		}
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				Path metadata = dir.resolve("instance.json");
				if (!Files.exists(metadata)) {
					continue;
				}
				try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
					Instance instance = GSON.fromJson(reader, Instance.class);
					if (instance != null) {
						instances.add(instance);
					}
				} catch (IOException ignored) {
					// Skip unreadable instance.
				}
			}
		} catch (IOException ignored) {
			// No instances yet.
		}
		instances.sort(Comparator.comparingLong(Instance::createdAtEpochMillis));
		return instances;
	}

	public static Instance createNew(String name, String mcVersion, InstanceIcon icon) {
		String id = UUID.randomUUID().toString();
		Instance instance = new Instance(id, name, mcVersion, icon, System.currentTimeMillis());
		InstancePaths.ensureDirectories(id);
		save(instance);
		return instance;
	}

	public static void save(Instance instance) {
		InstancePaths.ensureDirectories(instance.id());
		Path file = InstancePaths.metadataFile(instance.id());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save profile " + instance.name(), e);
		}
	}

	public static void delete(Instance instance) {
		Path dir = InstancePaths.dir(instance.id());
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (IOException ignored) {
					// Best-effort delete.
				}
			});
		} catch (IOException e) {
			throw new RuntimeException("Failed to delete profile " + instance.name(), e);
		}
	}
}
