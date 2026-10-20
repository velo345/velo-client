package net.veloclient.launcher.theme;

import java.util.LinkedHashMap;
import java.util.Map;

/** The launcher's built-in themes (0.9.7 redesign: neutral grounds, one solid accent). Names match the mod's {@code ThemePresets}. */
public final class LauncherThemePresets {

	public static final LauncherTheme VELO_DARK = new LauncherTheme(
			"Velo Dark", 0xFF0B0B0E, 0xFF131317, 0xFFE0262E, 0xFFC81F27, 0xFFF4F4F6, 10, 0.6f, 1.0f, 0.92f);
	public static final LauncherTheme MIDNIGHT = new LauncherTheme(
			"Midnight", 0xFF090D18, 0xFF111726, 0xFF4F7DFF, 0xFF3B5BDB, 0xFFEEF1FF, 10, 0.5f, 1.0f, 0.92f);
	public static final LauncherTheme NORD = new LauncherTheme(
			"Nord", 0xFF2A303B, 0xFF333A47, 0xFF88C0D0, 0xFF81A1C1, 0xFFECEFF4, 10, 0.4f, 1.0f, 0.92f);
	public static final LauncherTheme MONOCHROME = new LauncherTheme(
			"Monochrome", 0xFF0D0D0D, 0xFF171717, 0xFFE6E6E6, 0xFFBDBDBD, 0xFFF5F5F5, 10, 0.3f, 0.8f, 0.92f);
	public static final LauncherTheme EMERALD = new LauncherTheme(
			"Emerald", 0xFF07110D, 0xFF0F1B16, 0xFF22C17A, 0xFF16A066, 0xFFE8FFF4, 10, 0.5f, 1.0f, 0.92f);
	public static final LauncherTheme SUNSET = new LauncherTheme(
			"Sunset", 0xFF130C0A, 0xFF1D1411, 0xFFFF7A3D, 0xFFE85D2A, 0xFFFFF3EC, 10, 0.5f, 1.0f, 0.92f);

	private LauncherThemePresets() {
	}

	public static Map<String, LauncherTheme> all() {
		Map<String, LauncherTheme> presets = new LinkedHashMap<>();
		for (LauncherTheme theme : new LauncherTheme[] {VELO_DARK, MIDNIGHT, NORD, MONOCHROME, EMERALD, SUNSET}) {
			presets.put(theme.name(), theme);
		}
		return presets;
	}
}
