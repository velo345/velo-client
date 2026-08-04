package net.veloclient.launcher.ui;

import javafx.scene.control.Dialog;
import javafx.stage.Window;

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
		// JavaFX's own owner-centering is unreliable on multi-monitor setups
		// (dialogs were landing at a fixed (0,0)-ish position on whatever the
		// OS considers the "primary" screen, regardless of which monitor the
		// main window actually lived on) - explicitly center over the owner
		// once the dialog's real size is known, every time.
		Window owner = dialog.getOwner();
		if (owner != null) {
			dialog.setOnShowing(e -> {
				dialog.getDialogPane().applyCss();
				dialog.getDialogPane().layout();
				double width = dialog.getDialogPane().prefWidth(-1);
				double height = dialog.getDialogPane().prefHeight(width);
				dialog.setX(owner.getX() + (owner.getWidth() - width) / 2);
				dialog.setY(owner.getY() + (owner.getHeight() - height) / 2);
			});
		}
	}
}
