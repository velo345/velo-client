package net.veloclient.velo.client.theme;

import java.util.LinkedHashMap;
import java.util.Map;

/** Built-in theme presets (design spec section 7). */
public final class ThemePresets {

	public static final Theme VELO_DARK = new Theme(
			"Velo Dark", 0xF00F0A0A, 0xE01E1212, 0xFFFF4444, 0xFFB71C1C, 0xFFFFFFFF, 8, 0.6f, 1.0f, 0.85f);

	public static final Theme MIDNIGHT = new Theme(
			"Midnight", 0xF0000010, 0xE0101025, 0xFF3B5BFF, 0xFF7A5CFF, 0xFFE8E8FF, 6, 0.5f, 1.0f, 0.85f);

	public static final Theme NORD = new Theme(
			"Nord", 0xF02E3440, 0xE03B4252, 0xFF88C0D0, 0xFF81A1C1, 0xFFECEFF4, 6, 0.4f, 1.0f, 0.85f);

	public static final Theme MONOCHROME = new Theme(
			"Monochrome", 0xF0111111, 0xE01E1E1E, 0xFFBBBBBB, 0xFFFFFFFF, 0xFFFFFFFF, 4, 0.3f, 0.8f, 0.85f);

	private ThemePresets() {
	}

	public static Map<String, Theme> all() {
		Map<String, Theme> presets = new LinkedHashMap<>();
		for (Theme theme : new Theme[] {VELO_DARK, MIDNIGHT, NORD, MONOCHROME}) {
			presets.put(theme.name(), theme);
		}
		return presets;
	}
}
