package net.veloclient.velo.module;

/**
 * A single toggleable feature. Implementations must be side-effect-free
 * outside {@link #onEnable()}/{@link #onDisable()} so the registry can be
 * introspected safely to build the manifest, the launcher's mod browser and
 * the in-game panel from one source of truth (design spec section 8).
 */
public interface Module {

	/** Stable, lowercase-hyphenated identifier. Never rename once shipped; profiles reference it. */
	String id();

	/** Short human-readable name shown in the panel and launcher. */
	String displayName();

	/** One or two sentences explaining what the module does, for the manifest and panel tooltip. */
	String description();

	ModuleCategory category();

	SafetyTag safetyTag();

	/** Whether this module ships enabled by default on a fresh profile. */
	boolean defaultEnabled();

	boolean isEnabled();

	void setEnabled(boolean enabled);

	/** Called when the module transitions from disabled to enabled. */
	default void onEnable() {
	}

	/** Called when the module transitions from enabled to disabled. */
	default void onDisable() {
	}
}
