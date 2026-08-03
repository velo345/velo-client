package net.veloclient.velo.client.theme;

import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists user-created themes (as opposed to {@link ThemePresets}' built-in, read-only ones) to {@code ~/.velo-client/config/custom-themes.json}. */
final class CustomThemeStore {

	private static final String CONFIG_ID = "custom-themes";

	private CustomThemeStore() {
	}

	private record Data(List<Theme> themes) {
	}

	static List<Theme> load() {
		Data data = ConfigManager.load(CONFIG_ID, Data.class, new Data(new ArrayList<>()));
		return new ArrayList<>(data.themes());
	}

	static void save(List<Theme> themes) {
		ConfigManager.save(CONFIG_ID, new Data(themes));
	}

	static Map<String, Theme> asMap(List<Theme> themes) {
		Map<String, Theme> map = new LinkedHashMap<>();
		for (Theme theme : themes) {
			map.put(theme.name(), theme);
		}
		return map;
	}
}
