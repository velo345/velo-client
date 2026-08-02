package net.veloclient.velo.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads/saves one human-editable JSON file per module under
 * {@code ~/.velo-client/config/<module-id>.json} (design spec section 8).
 * Kept intentionally dumb: each module owns a small settings record and is
 * responsible for sane defaults if no file exists yet.
 */
public final class ConfigManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ConfigManager() {
	}

	private static Path fileFor(String moduleId) {
		return VeloPaths.config().resolve(moduleId + ".json");
	}

	/** Returns {@code defaultValue} if no config file exists yet or it fails to parse. */
	public static <T> T load(String moduleId, Class<T> type, T defaultValue) {
		Path file = fileFor(moduleId);
		if (!Files.exists(file)) {
			return defaultValue;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			T parsed = GSON.fromJson(reader, type);
			return parsed != null ? parsed : defaultValue;
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			return defaultValue;
		}
	}

	public static <T> void save(String moduleId, T value) {
		VeloPaths.ensureDirectories();
		try (Writer writer = Files.newBufferedWriter(fileFor(moduleId), StandardCharsets.UTF_8)) {
			GSON.toJson(value, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save config for module " + moduleId, e);
		}
	}
}
