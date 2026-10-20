package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import net.veloclient.launcher.instance.Instance;

import java.util.List;
import java.util.Optional;

/** Add/edit dialog for a saved server entry: name, host, port, and which mod profile "Connect" should launch with. */
public final class ServerEditDialog {

	public record Result(String name, String host, int port, String instanceId) {
	}

	private ServerEditDialog() {
	}

	private static VBox labeled(String label, javafx.scene.Node control) {
		Label l = new Label(label);
		l.getStyleClass().add("field-label");
		return new VBox(6, l, control);
	}

	public static Optional<Result> show(Stage owner, String title, String initialName, String initialHost, int initialPort,
			List<Instance> instances, String initialInstanceId) {
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

		ComboBox<Instance> instanceBox = new ComboBox<>();
		instanceBox.getItems().add(null);
		instanceBox.getItems().addAll(instances);
		instanceBox.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(Instance instance) {
				return instance == null ? "None - just save the address" : instance.name() + "  (Minecraft " + instance.mcVersion() + ")";
			}

			@Override
			public Instance fromString(String string) {
				return null;
			}
		});
		instances.stream().filter(i -> i.id().equals(initialInstanceId)).findFirst()
				.ifPresentOrElse(instanceBox::setValue, () -> instanceBox.setValue(null));

		HBox.setHgrow(hostField, javafx.scene.layout.Priority.ALWAYS);
		portField.setPrefWidth(96);
		HBox address = new HBox(10, labeled("Address", hostField), labeled("Port", portField));
		HBox.setHgrow(address.getChildren().get(0), javafx.scene.layout.Priority.ALWAYS);
		instanceBox.setMaxWidth(Double.MAX_VALUE);
		VBox content = new VBox(18, labeled("Name", nameField), address, labeled("Launch with", instanceBox));
		content.setPadding(new Insets(4, 0, 4, 0));
		content.setPrefWidth(440);
		dialog.getDialogPane().setContent(content);
		ButtonType confirm = new ButtonType(initialHost == null || initialHost.isBlank() ? "Add server" : "Save", ButtonBar.ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);

		dialog.setResultConverter(button -> {
			if (button != confirm) {
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
			Instance selected = instanceBox.getValue();
			return new Result(name, host, port, selected != null ? selected.id() : null);
		});

		return dialog.showAndWait();
	}
}
