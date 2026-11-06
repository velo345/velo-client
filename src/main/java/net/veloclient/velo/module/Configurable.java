package net.veloclient.velo.module;

import java.util.List;

/** Implemented by modules that expose adjustable settings beyond on/off - shown behind the gear icon in the panel. */
public interface Configurable {

	List<ConfigField> configFields();

	/**
	 * Extra settings that don't fit one field each (e.g. Item Position's per-item-type profiles), saved with the
	 * profile as one string. Null = nothing extra.
	 */
	default String extraState() {
		return null;
	}

	default void restoreExtraState(String state) {
	}

	/** False for a field that must not be saved in or restored from the profile (its value lives somewhere else). */
	default boolean persistField(String label) {
		return true;
	}
}
