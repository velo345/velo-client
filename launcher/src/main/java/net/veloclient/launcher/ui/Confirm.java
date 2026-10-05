package net.veloclient.launcher.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

/** A styled yes/no question. */
final class Confirm {

	private Confirm() {
	}

	static boolean ask(Stage owner, String title, String message) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(owner);
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		DialogStyling.apply(alert);
		return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
	}
}
