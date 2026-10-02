package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The one layout every Settings section uses: a titled card holding rows of "label + one short
 * hint" on the left and the control on the right. Longer explanations go into a tooltip on the
 * row instead of paragraphs of text on the page.
 */
public final class SettingsUi {

	private SettingsUi() {
	}

	public static VBox card(String title) {
		Label heading = new Label(title);
		heading.getStyleClass().add("settings-card-title");
		VBox card = new VBox(2, heading);
		card.getStyleClass().add("settings-card");
		return card;
	}

	/** Adds a row to {@code card}; {@code details} (nullable) becomes the row's tooltip. */
	public static HBox row(VBox card, String label, String hint, String details, Node... controls) {
		Label name = new Label(label);
		name.getStyleClass().add("settings-row-label");
		VBox text = new VBox(1, name);
		if (hint != null && !hint.isBlank()) {
			Label hintLabel = new Label(hint);
			hintLabel.getStyleClass().add("settings-row-hint");
			hintLabel.setWrapText(true);
			text.getChildren().add(hintLabel);
		}
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		HBox right = new HBox(8, controls);
		right.setAlignment(Pos.CENTER_RIGHT);
		right.setMinWidth(Region.USE_PREF_SIZE);
		HBox row = new HBox(16, text, right);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("settings-row");
		if (details != null) {
			Tooltip tip = new Tooltip(details);
			tip.setWrapText(true);
			tip.setMaxWidth(360);
			Tooltip.install(row, tip);
		}
		card.getChildren().add(row);
		return row;
	}
}
