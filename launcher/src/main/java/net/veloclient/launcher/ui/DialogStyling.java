package net.veloclient.launcher.ui;

import javafx.scene.control.Dialog;

/**
 * JavaFX {@code Dialog}/{@code Alert} windows render in their own scene
 * graph, separate from the main window - they don't automatically inherit
 * its stylesheet or theme colors. Call this on every dialog so it picks up
 * {@code launcher.css} (and its fallback theme colors) instead of falling
 * back to plain default JavaFX styling.
 */
public final class DialogStyling {

	private DialogStyling() {
	}

	public static void apply(Dialog<?> dialog) {
		dialog.getDialogPane().getStylesheets().add(
				DialogStyling.class.getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
	}
}
