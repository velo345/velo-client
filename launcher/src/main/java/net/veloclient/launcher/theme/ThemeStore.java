package net.veloclient.launcher.theme;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads/writes the same {@code ~/.velo-client/config/theme.json} the mod's {@code ThemeManager} uses. */
public final class ThemeStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ThemeStore() {
	}

	public static LauncherTheme load() {
		VeloPaths.ensureDirectories();
		var file = VeloPaths.config().resolve("theme.json");
		if (!Files.exists(file)) {
			return LauncherThemePresets.VELO_DARK;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Persisted persisted = GSON.fromJson(reader, Persisted.class);
			if (persisted == null) {
				return LauncherThemePresets.VELO_DARK;
			}
			Map<String, LauncherTheme> all = new LinkedHashMap<>(LauncherThemePresets.all());
			all.putAll(LauncherCustomThemeStore.asMap(LauncherCustomThemeStore.load()));
			return all.getOrDefault(persisted.themeName, LauncherThemePresets.VELO_DARK);
		} catch (IOException | JsonParseException e) {
			// A corrupt theme.json (JsonSyntaxException, not an IOException)
			// used to escape this catch entirely and fail the whole launcher
			// startup, since this loads before the main window exists.
			return LauncherThemePresets.VELO_DARK;
		}
	}

	public static void save(LauncherTheme theme) {
		VeloPaths.ensureDirectories();
		// Editing a custom theme's colors/sliders calls this with the same
		// name each time - keep the persisted custom-themes list in sync too
		// (mirroring the mod's own ThemeManager.setActive), or edits would be
		// lost the moment a different theme got selected.
		if (!LauncherCustomThemeStore.isBuiltIn(theme.name())) {
			LauncherCustomThemeStore.update(theme);
		}
		var file = VeloPaths.config().resolve("theme.json");
		Persisted persisted = new Persisted(theme.name(), new LinkedHashMap<>());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(persisted, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save theme", e);
		}
	}

	private static final class Persisted {
		String themeName;
		Map<String, Integer> moduleColorOverrides;

		Persisted(String themeName, Map<String, Integer> moduleColorOverrides) {
			this.themeName = themeName;
			this.moduleColorOverrides = moduleColorOverrides;
		}
	}
}
