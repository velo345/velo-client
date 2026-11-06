package net.veloclient.velo.client.gui.window;

import net.veloclient.velo.module.ConfigField;

import java.util.List;

/**
 * A module whose settings window is a large dashboard instead of one long list ({@link ModuleConfigScreen} picks it
 * up on its own): a strip of live numbers at the top, the module's sections ({@link ConfigField.HeaderField}s) as
 * a side menu with only the chosen section's settings beside it, and optionally one choice drawn as big cards.
 * Every setting is still the module's own {@link ConfigField} with its own label, so saving and profiles are
 * unchanged.
 */
public interface ModuleDashboard {

	/**
	 * One live number in the top strip: e.g. ("FPS", "412", green). {@code note} is the small text after it (may be
	 * empty); {@code meter} 0..1 draws a bar under it (negative: none); {@code graph} draws the value's last seconds
	 * as a small graph under it instead (the value must be a number).
	 */
	record Stat(String label, String value, String note, int color, float meter, boolean graph) {
		public Stat(String label, String value, String note, int color) {
			this(label, value, note, color, -1f, false);
		}
	}

	/** The live numbers; read a few times a second while the window is open. */
	List<Stat> dashboardStats();

	/** Label of a {@link ConfigField.ChoiceField} shown as big cards at the top of its section (not as a row), or null. */
	default String cardChoice() {
		return null;
	}

	/** One short line under a card's name. */
	default String cardBlurb(String option) {
		return "";
	}

	/** A small line on the active card (e.g. what Auto is running right now), or null. */
	default String cardStatus(String option) {
		return null;
	}

	/** Two quick 1..5 ratings for a card: {speed, looks} - drawn as small meters, or null. */
	default int[] cardMeters(String option) {
		return null;
	}

	/** The current state of a section in a few words, under its name in the side menu ("16 chunks"), or null. */
	default String sectionSummary(String section) {
		return null;
	}

	/** A short, situation-aware tip for the bottom line (e.g. what to change when FPS is low), or null. */
	default String dashboardTip() {
		return null;
	}

	/** A one-line explanation shown above a section's settings, or null. */
	default String sectionHint(String section) {
		return null;
	}

	/**
	 * The fields a section shows in the dashboard instead of its own (e.g. another module's settings pulled in
	 * so they're one click away) - only shown here, never saved under this module. Null keeps the section's fields.
	 */
	default List<ConfigField> sectionOverride(String section) {
		return null;
	}

	/** Called when the dashboard opens / closes (e.g. to start measuring something only it shows). */
	default void dashboardOpened() {
	}

	default void dashboardClosed() {
	}
}
