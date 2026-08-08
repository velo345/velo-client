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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists user-created themes to {@code ~/.velo-client/config/custom-themes.json}
 * - the exact same file the in-game mod's own {@code CustomThemeStore} reads
 * and writes (same {@code {"themes": [...]}} shape, one {@link LauncherTheme}
 * per entry field-for-field matching the mod's {@code Theme}), so a theme
 * created in either app shows up in the other. The launcher has no
 * {@code ConfigManager} of its own (see {@code ThemeStore}'s own note on
 * that), so this reads/writes the file directly instead.
 */
public final class LauncherCustomThemeStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private LauncherCustomThemeStore() {
	}

	private record Data(List<LauncherTheme> themes) {
	}

	public static List<LauncherTheme> load() {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve("custom-themes.json");
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Data data = GSON.fromJson(reader, Data.class);
			return data != null && data.themes() != null ? new ArrayList<>(data.themes()) : new ArrayList<>();
		} catch (IOException | JsonParseException e) {
			return new ArrayList<>();
		}
	}

	public static void save(List<LauncherTheme> themes) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve("custom-themes.json");
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(new Data(themes), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save custom themes", e);
		}
	}

	public static Map<String, LauncherTheme> asMap(List<LauncherTheme> themes) {
		Map<String, LauncherTheme> map = new LinkedHashMap<>();
		for (LauncherTheme theme : themes) {
			map.put(theme.name(), theme);
		}
		return map;
	}

	public static boolean isBuiltIn(String name) {
		return LauncherThemePresets.all().containsKey(name);
	}

	/** Duplicates {@code source}'s values under a new name. Returns empty if the name is blank or collides with any existing theme. */
	public static java.util.Optional<LauncherTheme> create(String name, LauncherTheme source) {
		if (name == null || name.isBlank() || isBuiltIn(name)) {
			return java.util.Optional.empty();
		}
		List<LauncherTheme> customs = load();
		if (asMap(customs).containsKey(name)) {
			return java.util.Optional.empty();
		}
		LauncherTheme copy = new LauncherTheme(name, source.background(), source.surface(), source.accentStart(),
				source.accentEnd(), source.text(), source.cornerRadius(), source.blurIntensity(), source.animationSpeed(),
				source.panelOpacity());
		customs.add(copy);
		save(customs);
		return java.util.Optional.of(copy);
	}

	/** Persists an edit to an existing custom theme (matched by name) - no-op if it isn't a custom theme. */
	public static void update(LauncherTheme theme) {
		List<LauncherTheme> customs = load();
		for (int i = 0; i < customs.size(); i++) {
			if (customs.get(i).name().equals(theme.name())) {
				customs.set(i, theme);
				save(customs);
				return;
			}
		}
	}

	public static void delete(String name) {
		List<LauncherTheme> customs = load();
		if (customs.removeIf(t -> t.name().equals(name))) {
			save(customs);
		}
	}
}
