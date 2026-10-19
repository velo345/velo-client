package net.veloclient.velo.client.theme;

/**
 * Full theme definition (design spec section 7): base palette, accent
 * gradient, corner radius, blur intensity, animation speed, and panel
 * opacity. Colors are packed ARGB ints (0xAARRGGBB) so they can be fed
 * straight into {@code DrawContext.fill}/text color parameters.
 */
public record Theme(
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

	public Theme {
		cornerRadius = Math.clamp(cornerRadius, 0, 16);
		blurIntensity = Math.clamp(blurIntensity, 0f, 1f);
		animationSpeed = Math.clamp(animationSpeed, 0f, 2f);
		panelOpacity = Math.clamp(panelOpacity, 0f, 1f);
	}

	/** Surface color with {@link #panelOpacity} baked into the alpha channel. */
	public int surfaceWithOpacity() {
		int alpha = Math.round(panelOpacity * 255) << 24;
		return alpha | (surface & 0x00FFFFFF);
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
