package net.veloclient.launcher.data;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Reads the module manifest the mod writes on every launch
 * ({@code net.veloclient.velo.module.ModuleRegistry#exportManifest}), so the
 * launcher's Mods browser is driven by the exact same single source of truth
 * as the in-game panel (design spec section 8) - it never needs its own
 * hardcoded module list, and can't drift from what's actually installed.
 */
public final class ManifestReader {

	private static final Gson GSON = new Gson();

	private ManifestReader() {
	}

	public static List<ModuleInfo> read() {
		var file = VeloPaths.manifestFile();
		if (!Files.exists(file)) {
			return List.of();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Manifest manifest = GSON.fromJson(reader, Manifest.class);
			return manifest == null || manifest.modules == null ? List.of() : manifest.modules;
		} catch (IOException e) {
			return List.of();
		}
	}

	private static final class Manifest {
		List<ModuleInfo> modules;
	}
}
