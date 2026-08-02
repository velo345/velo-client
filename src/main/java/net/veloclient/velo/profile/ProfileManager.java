package net.veloclient.velo.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.config.VeloPaths;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads/saves {@link ServerProfile}s from {@code ~/.velo-client/profiles/*.json}
 * and applies the best match to the {@link ModuleRegistry} on server join.
 */
public final class ProfileManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ProfileManager() {
	}

	public static List<ServerProfile> loadAll() {
		VeloPaths.ensureDirectories();
		List<ServerProfile> profiles = new ArrayList<>();
		try (Stream<Path> files = Files.list(VeloPaths.profiles())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					ServerProfile profile = GSON.fromJson(reader, ServerProfile.class);
					if (profile != null) {
						profiles.add(profile);
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load profiles", e);
		}
		return profiles;
	}

	public static void save(ServerProfile profile) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.profiles().resolve(sanitize(profile.name()) + ".json");
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(profile, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save profile " + profile.name(), e);
		}
	}

	/** Finds the first saved profile whose address patterns match, if any. */
	public static Optional<ServerProfile> findForAddress(String serverAddress) {
		return loadAll().stream().filter(p -> p.matches(serverAddress)).findFirst();
	}

	/** Applies a profile's module states and Safe Mode flag to the live registry. */
	public static void apply(ServerProfile profile) {
		if (profile.safeMode()) {
			ModuleRegistry.applySafeMode();
		}
		for (Map.Entry<String, Boolean> entry : profile.moduleStates().entrySet()) {
			ModuleRegistry.get(entry.getKey()).ifPresent(m -> m.setEnabled(entry.getValue()));
		}
	}

	/** Captures the registry's current module states into a new profile snapshot. */
	public static ServerProfile capture(String name, List<String> addressPatterns, boolean safeMode) {
		Map<String, Boolean> states = new java.util.LinkedHashMap<>();
		for (Module module : ModuleRegistry.all()) {
			states.put(module.id(), module.isEnabled());
		}
		return new ServerProfile(name, addressPatterns, safeMode, states);
	}

	private static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}
}
