package net.veloclient.legacy.feature;

/** Public door to the Zoom module's field of view (the module class itself is package-private). */
public final class ZoomHook {

	private ZoomHook() {
	}

	public static float fov(float fov) {
		return LegacyFeatures.Zoom.fov(fov);
	}
}
