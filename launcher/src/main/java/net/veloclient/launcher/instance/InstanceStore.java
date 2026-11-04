package net.veloclient.launcher.instance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
				} catch (IOException | JsonParseException ignored) {
					// Skip unreadable/corrupt instance.json rather than losing every
					// other profile too - a malformed file (partial write from a
					// crash, manual edit, ...) used to throw JsonSyntaxException
					// here uncaught (not an IOException, so this catch never saw
					// it), aborting loadAll() entirely and making every profile
					// disappear from the grid, not just the broken one.
				}
			}
		} catch (IOException ignored) {
			// No instances yet.
		}
		instances.sort(Comparator.comparingLong(Instance::createdAtEpochMillis));
		return instances;
	}

	public static Instance createNew(String name, String mcVersion, InstanceIcon icon) {
		// The first-start guide only makes sense once: the very first launch with Velo ever (its default profile) - not
		// on profiles made later, and not again after deleting every profile (remembered in config/guide-offered).
		Path offered = net.veloclient.launcher.data.VeloPaths.root().resolve("config").resolve("guide-offered");
		boolean first = loadAll().isEmpty() && !Files.exists(offered);
		String id = UUID.randomUUID().toString();
		Instance instance = new Instance(id, name, mcVersion, icon, System.currentTimeMillis());
		InstancePaths.ensureDirectories(id);
		save(instance);
		if (first) {
			markGuidePending(id);
			try {
				Files.createDirectories(offered.getParent());
				Files.writeString(offered, java.time.Instant.now().toString(), StandardCharsets.UTF_8);
			} catch (IOException ignored) {
				// worst case the guide is offered on a later first profile too
			}
		}
		giveOwnVeloProfile(id, name);
		return instance;
	}

	/**
	 * A new profile gets its own Velo settings profile (its folder's {@code velo/active-profile.json}, read by the game)
	 * named after it - so it starts with Velo's defaults instead of taking over the modules another profile turned on
	 * (recoloured hearts, 1.8 animations...). The name never matches an existing settings profile, or that one would load.
	 */
	private static void giveOwnVeloProfile(String id, String name) {
		try {
			String base = name == null || name.isBlank() ? "Profile" : name.strip();
			String chosen = base;
			for (int n = 2; Files.exists(net.veloclient.launcher.data.VeloPaths.root().resolve("saved-profiles").resolve(fileName(chosen) + ".json")); n++) {
				chosen = base + " " + n;
			}
			Path marker = InstancePaths.gameDir(id).resolve("velo").resolve("active-profile.json");
			Files.createDirectories(marker.getParent());
			com.google.gson.JsonObject json = new com.google.gson.JsonObject();
			json.addProperty("name", chosen);
			Files.writeString(marker, json.toString(), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException ignored) {
			// then it shares the global settings profile, like profiles always did
		}
	}

	/** The file name the game and the launcher's ProfileStore give a settings profile. */
	private static String fileName(String profileName) {
		return profileName.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}

	/**
	 * A brand-new profile shows Velo's first-start guide once, on its first title screen (the game deletes the file when
	 * the guide is finished or skipped). Only the very first profile; later ones, duplicates and imports never get it.
	 */
	private static void markGuidePending(String id) {
		try {
			Path marker = InstancePaths.gameDir(id).resolve("velo").resolve("guide-pending");
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, java.time.Instant.now().toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// no guide for this profile - harmless
		}
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

	/**
	 * Copies a profile's mods/config/resource packs/shader packs (and icon,
	 * if custom) into a brand new profile - deliberately not its {@code
	 * saves/}, since "make a similar setup" means the same mod/config
	 * loadout, not a copy of the world data too.
	 */
	public static Instance duplicate(Instance source, String newName) {
		String newId = UUID.randomUUID().toString();
		InstancePaths.ensureDirectories(newId);
		copyDirectoryIfPresent(InstancePaths.modsDir(source.id()), InstancePaths.modsDir(newId));
		copyDirectoryIfPresent(InstancePaths.resourcePacksDir(source.id()), InstancePaths.resourcePacksDir(newId));
		copyDirectoryIfPresent(InstancePaths.shaderPacksDir(source.id()), InstancePaths.shaderPacksDir(newId));
		copyDirectoryIfPresent(InstancePaths.gameDir(source.id()).resolve("config"), InstancePaths.gameDir(newId).resolve("config"));
		Path sourceIcon = InstancePaths.iconFile(source.id());
		if (Files.exists(sourceIcon)) {
			try {
				Files.copy(sourceIcon, InstancePaths.iconFile(newId), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Falls back to the built-in icon vanilla-style; not worth failing the whole duplicate over.
			}
		}
		Instance duplicate = new Instance(newId, newName, source.mcVersion(), source.icon(), System.currentTimeMillis(),
				source.ramMinMb(), source.ramMaxMb(), source.extraJvmArgs());
		save(duplicate);
		return duplicate;
	}

	private static void copyDirectoryIfPresent(Path source, Path target) {
		if (!Files.isDirectory(source)) {
			return;
		}
		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path relative = source.relativize(file);
				Path destination = target.resolve(relative.toString());
				Files.createDirectories(destination.getParent());
				Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to copy " + source + " while duplicating profile", e);
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
