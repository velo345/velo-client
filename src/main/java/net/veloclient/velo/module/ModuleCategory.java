package net.veloclient.velo.module;

/**
 * Top-level categories shown as tabs in the in-game mod panel and the
 * launcher's module browser. Mirrors section 5/6 of the design spec.
 */
public enum ModuleCategory {
	PERFORMANCE("Performance"),
	HUD("HUD"),
	RENDERING("Rendering"),
	SERVER_TOOLS("Server Tools"),
	DEBUG("Debug"),
	COSMETICS("Cosmetics"),
	QOL("Quality of Life"),
	PVP("PvP"),
	/** Velo+ Addons (the optional velo-addons jar) - listed on their own, below the core categories. */
	ADDONS("Velo+ Addons");

	private final String displayName;

	ModuleCategory(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
