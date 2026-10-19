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
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0);
	}

	public static String toCssHex(int argb) {
		return String.format("#%06X", argb & 0xFFFFFF);
	}

	/**
	 * Text color for anything drawn on a solid {@code accentStart} fill: white while that still
	 * reads (contrast of at least 3:1 - Velo red, Midnight blue), otherwise near-black. Light
	 * accents like Nord's cyan or Monochrome's white made white labels on them unreadable.
	 */
	public int onAccent() {
		return 1.05 / (luminance(accentStart) + 0.05) >= 3.0 ? 0xFFFFFFFF : 0xFF15171C;
	}

	/** WCAG relative luminance of an RGB color (alpha ignored). */
	public static double luminance(int rgb) {
		return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
	}

	private static double channel(int value) {
		double c = (value & 0xFF) / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
