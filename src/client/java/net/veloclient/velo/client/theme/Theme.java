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
}
