package net.veloclient.launcher.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/** The round back-arrow button at the top of detail pages; {@code where} says where it goes back to (shown as its tooltip). */
public final class BackButton {

	private BackButton() {
	}

	public static Button create(String where, Runnable onBack) {
		SVGPath arrow = new SVGPath();
		// A left arrow: shaft plus head, drawn as one filled outline (16 x 14).
		arrow.setContent("M7.4 0.6 L8.8 2 L4.8 6 H16 V8 H4.8 L8.8 12 L7.4 13.4 L0.8 7 Z");
		arrow.getStyleClass().add("back-arrow");
		Button button = new Button();
		button.setGraphic(arrow);
		button.getStyleClass().add("back-icon-button");
		Tooltip tip = new Tooltip(where == null || where.isBlank() ? "Back" : "Back to " + where);
		tip.setShowDelay(Duration.millis(300));
		button.setTooltip(tip);
		button.setAccessibleText(tip.getText());
		if (onBack != null) {
			button.setOnAction(e -> onBack.run());
		}
		return button;
	}
}
