package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.util.Optional;

/** Add/edit dialog for a saved server entry: name, host, port. */
public final class ServerEditDialog {

	public record Result(String name, String host, int port) {
	}

	private ServerEditDialog() {
	}

	public static Optional<Result> show(Stage owner, String title, String initialName, String initialHost, int initialPort) {
		Dialog<Result> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(title);
		dialog.setHeaderText(title);
		DialogStyling.apply(dialog);

		TextField nameField = new TextField(initialName);
		nameField.setPromptText("Display name, e.g. My Server");
		TextField hostField = new TextField(initialHost);
		hostField.setPromptText("play.example.net");
		TextField portField = new TextField(initialPort > 0 ? String.valueOf(initialPort) : "25565");
		portField.setPromptText("25565");

		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(10);
		grid.setPadding(new Insets(16));
		grid.addRow(0, new Label("Name"), nameField);
		grid.addRow(1, new Label("Address"), hostField);
		grid.addRow(2, new Label("Port"), portField);

		dialog.getDialogPane().setContent(grid);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		dialog.setResultConverter(button -> {
			if (button != ButtonType.OK) {
				return null;
			}
			String name = nameField.getText().isBlank() ? hostField.getText() : nameField.getText().trim();
			String host = hostField.getText().trim();
			int port = 25565;
			try {
				port = Integer.parseInt(portField.getText().trim());
			} catch (NumberFormatException ignored) {
				// Keep default port on bad input.
			}
			if (host.isEmpty()) {
				return null;
			}
			return new Result(name, host, port);
		});

		return dialog.showAndWait();
	}
}
