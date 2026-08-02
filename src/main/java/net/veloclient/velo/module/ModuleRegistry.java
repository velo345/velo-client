package net.veloclient.velo.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry every module is added to exactly once at startup. This is
 * the single source of truth the manifest, the in-game panel and (eventually)
 * the launcher's mod browser all read from, per design spec section 8.
 */
public final class ModuleRegistry {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Module> MODULES = new LinkedHashMap<>();

	private ModuleRegistry() {
	}

	public static void register(Module module) {
		if (MODULES.containsKey(module.id())) {
			throw new IllegalStateException("Duplicate module id registered: " + module.id());
		}
		MODULES.put(module.id(), module);
	}

	public static Optional<Module> get(String id) {
		return Optional.ofNullable(MODULES.get(id));
	}

	public static Collection<Module> all() {
		return List.copyOf(MODULES.values());
	}

	public static List<Module> byCategory(ModuleCategory category) {
		return MODULES.values().stream()
				.filter(m -> m.category() == category)
				.toList();
	}

	/**
	 * Disables every module that isn't tagged {@link SafetyTag#ALWAYS_SAFE}.
	 * This is the "Safe Mode" master switch from design spec section 8.
	 */
	public static void applySafeMode() {
		for (Module module : MODULES.values()) {
			if (!module.safetyTag().allowedInSafeMode()) {
				module.setEnabled(false);
			}
		}
	}

	/** Writes manifest.json (id, category, default-enabled, safety-tag, description) for every module. */
	public static void exportManifest() {
		VeloPaths.ensureDirectories();
		List<ManifestEntry> entries = new ArrayList<>();
		for (Module module : MODULES.values()) {
			entries.add(new ManifestEntry(
					module.id(),
					module.displayName(),
					module.description(),
					module.category().name(),
					module.safetyTag().name(),
					module.defaultEnabled()));
		}
		try (Writer writer = Files.newBufferedWriter(VeloPaths.manifestFile(), StandardCharsets.UTF_8)) {
			GSON.toJson(new Manifest(entries), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write module manifest", e);
		}
	}

	private record Manifest(List<ManifestEntry> modules) {
	}

	private record ManifestEntry(String id, String displayName, String description,
			String category, String safetyTag, boolean defaultEnabled) {
	}
}
