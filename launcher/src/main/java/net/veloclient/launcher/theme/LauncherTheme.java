package net.veloclient.launcher.theme;

/**
 * Mirrors the in-game mod's {@code Theme} record field-for-field (same
 * {@code theme.json} shape), so changing the theme in either app is reflected
 * in both (design spec section 7).
 */
public record LauncherTheme(
		String name,
		int background,
		int surface,
		int accentStart,
		int accentEnd,
		int text,
		int cornerRadius,
		float blurIntensity,
		float animationSpeed,
		float panelOpacity) {

	/** Converts a packed ARGB int (0xAARRGGBB) to a CSS-style rgba() string for JavaFX inline styles. */
	public static String toCssRgba(int argb) {
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return String.format("rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0);
	}

	public static String toCssHex(int argb) {
		return String.format("#%06X", argb & 0xFFFFFF);
	}
}
