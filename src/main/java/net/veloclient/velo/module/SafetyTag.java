package net.veloclient.velo.module;

/**
 * Server-safety classification that every module must declare. Shown as a
 * badge in the launcher and in-game panel so users know what's fine where
 * (see SAFETY.md and section 2/8 of the design spec).
 */
public enum SafetyTag {
	/** Never provides gameplay advantage or information a server didn't already send; fine everywhere. */
	ALWAYS_SAFE("Always Safe"),
	/** Purely visual/cosmetic, invisible to the server and non-Velo players; no gameplay effect. */
	COSMETIC_ONLY("Cosmetic Only"),
	/** Legal and packet-faithful, but some strict servers disallow the category (e.g. hitbox overlays). */
	CHECK_SERVER_RULES("Check Server Rules");

	private final String displayName;

	SafetyTag(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	/** Whether the Safe Mode master switch (section 8) should keep this module enabled. */
	public boolean allowedInSafeMode() {
		return this == ALWAYS_SAFE;
	}
}
