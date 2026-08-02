package net.veloclient.velo.client.theme;

import net.veloclient.velo.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the active theme and per-module color overrides (design spec
 * section 7), persisted to {@code ~/.velo-client/config/theme.json}. The
 * in-game panel and the (future) launcher both read from this one file.
 */
public final class ThemeManager {

	private static final String CONFIG_ID = "theme";

	private static Theme active;
	private static Map<String, Integer> moduleColorOverrides = new LinkedHashMap<>();

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
		save();
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
		active = ThemePresets.all().getOrDefault(persisted.themeName(), ThemePresets.VELO_DARK);
		moduleColorOverrides = new LinkedHashMap<>(persisted.moduleColorOverrides());
	}

	private static void save() {
		ConfigManager.save(CONFIG_ID, new Persisted(active.name(), moduleColorOverrides));
	}

	private record Persisted(String themeName, Map<String, Integer> moduleColorOverrides) {
	}
}
