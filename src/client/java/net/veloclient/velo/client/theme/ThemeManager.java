package net.veloclient.velo.client.theme;

import net.veloclient.velo.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the active theme and per-module color overrides (design spec
 * section 7), persisted to {@code ~/.velo-client/config/theme.json}. The
 * in-game panel and the (future) launcher both read from this one file.
 *
 * <p>Built-in {@link ThemePresets} are immutable Java constants and can't be
 * edited or deleted - only themes created via {@link #createCustomTheme}
 * (backed by {@link CustomThemeStore}) can be.
 */
public final class ThemeManager {

	private static final String CONFIG_ID = "theme";

	private static Theme active;
	private static Map<String, Integer> moduleColorOverrides = new LinkedHashMap<>();
	private static List<Theme> customThemes;

	private ThemeManager() {
	}

	public static synchronized Theme active() {
		if (active == null) {
			load();
		}
		return active;
	}

	public static synchronized void setActive(Theme theme) {
		active = theme;
		// Editing a custom theme's colors/sliders calls this with the same
		// name each time - keep the persisted custom-themes list in sync
		// rather than only updating the ephemeral "active" record, or edits
		// would be lost the moment a different theme was selected.
		List<Theme> customs = customThemes();
		for (int i = 0; i < customs.size(); i++) {
			if (customs.get(i).name().equals(theme.name())) {
				customs.set(i, theme);
				CustomThemeStore.save(customs);
				break;
			}
		}
		save();
	}

	public static synchronized boolean isBuiltIn(String themeName) {
		return ThemePresets.all().containsKey(themeName);
	}

	public static synchronized List<Theme> customThemes() {
		if (customThemes == null) {
			customThemes = CustomThemeStore.load();
		}
		return customThemes;
	}

	/** Duplicates {@code source}'s values under a new name and makes it the active theme. Fails silently (returns false) if the name collides with any existing theme. */
	public static synchronized boolean createCustomTheme(String name, Theme source) {
		if (name.isBlank() || isBuiltIn(name) || CustomThemeStore.asMap(customThemes()).containsKey(name)) {
			return false;
		}
		Theme copy = new Theme(name, source.background(), source.surface(), source.accentStart(), source.accentEnd(),
				source.text(), source.cornerRadius(), source.blurIntensity(), source.animationSpeed(), source.panelOpacity());
		List<Theme> customs = customThemes();
		customs.add(copy);
		CustomThemeStore.save(customs);
		setActive(copy);
		return true;
	}

	/** No-op if {@code name} isn't a custom theme (built-ins can't be deleted). Falls back to the default preset if the deleted theme was active. */
	public static synchronized void deleteCustomTheme(String name) {
		List<Theme> customs = customThemes();
		boolean removed = customs.removeIf(t -> t.name().equals(name));
		if (!removed) {
			return;
		}
		CustomThemeStore.save(customs);
		if (active != null && active.name().equals(name)) {
			setActive(ThemePresets.VELO_DARK);
		}
	}

	public static synchronized void setModuleColorOverride(String moduleId, int argb) {
		moduleColorOverrides.put(moduleId, argb);
		save();
	}

	public static synchronized void clearModuleColorOverride(String moduleId) {
		moduleColorOverrides.remove(moduleId);
		save();
	}

	/** Returns the module's overridden color, or {@code fallback} (usually the theme's text color) if none is set. */
	public static synchronized int moduleColor(String moduleId, int fallback) {
		return moduleColorOverrides.getOrDefault(moduleId, fallback);
	}

	private static void load() {
		Persisted persisted = ConfigManager.load(CONFIG_ID, Persisted.class,
				new Persisted(ThemePresets.VELO_DARK.name(), new LinkedHashMap<>()));
		Map<String, Theme> all = new LinkedHashMap<>(ThemePresets.all());
		all.putAll(CustomThemeStore.asMap(customThemes()));
		active = all.getOrDefault(persisted.themeName(), ThemePresets.VELO_DARK);
		moduleColorOverrides = new LinkedHashMap<>(persisted.moduleColorOverrides());
	}

	private static void save() {
		ConfigManager.save(CONFIG_ID, new Persisted(active.name(), moduleColorOverrides));
	}

	private record Persisted(String themeName, Map<String, Integer> moduleColorOverrides) {
	}
}
