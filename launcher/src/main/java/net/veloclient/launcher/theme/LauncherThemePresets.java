package net.veloclient.launcher.theme;

import java.util.LinkedHashMap;
import java.util.Map;

/** Same preset values as the mod's {@code ThemePresets} (design spec section 7). */
public final class LauncherThemePresets {

	public static final LauncherTheme VELO_DARK = new LauncherTheme(
			"Velo Dark", 0xF00F0A0A, 0xE01E1212, 0xFFFF4444, 0xFFB71C1C, 0xFFFFFFFF, 8, 0.6f, 1.0f, 0.85f);
	public static final LauncherTheme MIDNIGHT = new LauncherTheme(
			"Midnight", 0xF0000010, 0xE0101025, 0xFF3B5BFF, 0xFF7A5CFF, 0xFFE8E8FF, 6, 0.5f, 1.0f, 0.85f);
	public static final LauncherTheme NORD = new LauncherTheme(
			"Nord", 0xF02E3440, 0xE03B4252, 0xFF88C0D0, 0xFF81A1C1, 0xFFECEFF4, 6, 0.4f, 1.0f, 0.85f);
	public static final LauncherTheme MONOCHROME = new LauncherTheme(
			"Monochrome", 0xF0111111, 0xE01E1E1E, 0xFFBBBBBB, 0xFFFFFFFF, 0xFFFFFFFF, 4, 0.3f, 0.8f, 0.85f);

	private LauncherThemePresets() {
	}

	public static Map<String, LauncherTheme> all() {
		Map<String, LauncherTheme> presets = new LinkedHashMap<>();
		for (LauncherTheme theme : new LauncherTheme[] {VELO_DARK, MIDNIGHT, NORD, MONOCHROME}) {
			presets.put(theme.name(), theme);
		}
		return presets;
	}
}
