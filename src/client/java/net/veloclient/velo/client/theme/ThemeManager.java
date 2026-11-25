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
			stamp = stamp();
		} else {
			// the launcher's theme editor (Customize / Settings) writes the same files: pick changes up live, once a second
			long now = System.nanoTime();
			if (now - checkedAt > 1_000_000_000L) {
				checkedAt = now;
				long current = stamp();
				if (current != stamp) {
					stamp = current;
					customThemes = null;
					load();
				}
			}
		}
		return net.veloclient.velo.client.gui.widget.VeloStyle.glass() ? glass(active) : active;
	}

	private static Theme glassBase;
	private static Theme glassTheme;

	/** The glass look (2026-11-24): neutral surfaces and the accent from Settings > Theme (white by default). */
	private static Theme glass(Theme t) {
		int accent = net.veloclient.velo.client.gui.glass.GlassPrefs.accent();
		if (t != glassBase || glassTheme == null || glassTheme.accentStart() != accent) {
			glassBase = t;
			int end = net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.2f);
			glassTheme = new Theme(t.name(), t.background(), 0xFF141418, accent, end, 0xFFFFFFFF, t.cornerRadius(), t.blurIntensity(),
					t.animationSpeed(), t.panelOpacity());
		}
		return glassTheme;
	}

	private static long stamp;
	private static long checkedAt;

	/** Last-modified of theme.json and the custom themes file together. */
	private static long stamp() {
		long sum = 0;
		for (String name : new String[] {"theme.json", "custom-themes.json"}) {
			try {
				java.nio.file.Path p = net.veloclient.velo.config.VeloPaths.config().resolve(name);
				if (java.nio.file.Files.exists(p)) {
					sum = sum * 31 + java.nio.file.Files.getLastModifiedTime(p).toMillis();
				}
			} catch (Exception ignored) {
				// unreadable: no change
			}
		}
		return sum;
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

	/** Renames a custom theme (false if the new name is empty or taken). */
	public static synchronized boolean renameCustomTheme(String oldName, String newName) {
		String name = newName.trim();
		if (name.isEmpty() || name.equals(oldName) || isBuiltIn(name) || CustomThemeStore.asMap(customThemes()).containsKey(name)) {
			return false;
		}
		List<Theme> customs = customThemes();
		for (int i = 0; i < customs.size(); i++) {
			Theme t = customs.get(i);
			if (t.name().equals(oldName)) {
				Theme renamed = new Theme(name, t.background(), t.surface(), t.accentStart(), t.accentEnd(), t.text(),
						t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), t.panelOpacity());
				customs.set(i, renamed);
				CustomThemeStore.save(customs);
				if (active != null && active.name().equals(oldName)) {
					active = renamed;
					save();
				}
				return true;
			}
		}
		return false;
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
		stamp = stamp(); // our own write isn't a change to reload
	}

	private record Persisted(String themeName, Map<String, Integer> moduleColorOverrides) {
	}
}
