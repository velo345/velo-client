package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.instance.BuiltinIcons;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/** Create/edit dialog for a profile: name, Minecraft version, and an icon (a builtin glyph or a custom image). */
public final class InstanceEditDialog {

	public record Result(String name, String mcVersion, InstanceIcon icon, Path customIconSource) {
	}

	private InstanceEditDialog() {
	}

	public static Optional<Result> show(Stage owner, LauncherTheme theme, String title, String initialName,
			String initialVersion, InstanceIcon initialIcon) {
		Dialog<Result> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(title);
		dialog.setHeaderText(title);
		DialogStyling.apply(dialog);

		Color accentStart = argbColor(theme.accentStart());
		Color accentEnd = argbColor(theme.accentEnd());

		TextField nameField = new TextField(initialName);
		nameField.setPromptText("Profile name, e.g. Survival Modpack");

		ComboBox<String> versionBox = new ComboBox<>();
		for (GameVersion version : GameVersion.values()) {
			versionBox.getItems().add(version.id());
		}
		versionBox.setValue(initialVersion != null ? initialVersion : GameVersion.values()[GameVersion.values().length - 1].id());

		ToggleGroup iconGroup = new ToggleGroup();
		FlowPane iconPicker = new FlowPane(8, 8);
		iconPicker.setPrefWrapLength(360);
		File[] customFileHolder = new File[1];

		for (String iconId : BuiltinIcons.ids()) {
			ToggleButton button = new ToggleButton();
			button.setToggleGroup(iconGroup);
			button.setUserData(InstanceIcon.builtin(iconId));
			button.getStyleClass().add("icon-picker-tile");
			button.setGraphic(BuiltinIcons.render(iconId, 44, accentStart, accentEnd));
			if (initialIcon != null && initialIcon.kind() == InstanceIcon.Kind.BUILTIN && initialIcon.value().equals(iconId)) {
				button.setSelected(true);
			}
			iconPicker.getChildren().add(button);
		}

		ToggleButton customButton = new ToggleButton("Custom...");
		customButton.setToggleGroup(iconGroup);
		customButton.getStyleClass().add("icon-picker-tile");
		customButton.setUserData(InstanceIcon.custom());
		if (initialIcon != null && initialIcon.kind() == InstanceIcon.Kind.CUSTOM) {
			customButton.setSelected(true);
		}
		customButton.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose a profile icon (PNG)");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
			File file = chooser.showOpenDialog(owner);
			if (file != null) {
				customFileHolder[0] = file;
				customButton.setSelected(true);
			} else if (customFileHolder[0] == null) {
				iconGroup.selectToggle(null);
			}
		});
		iconPicker.getChildren().add(customButton);

		if (iconGroup.getSelectedToggle() == null) {
			iconGroup.selectToggle(iconPicker.getChildren().isEmpty() ? null : (ToggleButton) iconPicker.getChildren().get(0));
		}

		VBox content = new VBox(14);
		content.setPadding(new Insets(16));
		content.getChildren().addAll(
				labeled("Name", nameField),
				labeled("Minecraft version", versionBox),
				labeled("Icon", iconPicker));

		dialog.getDialogPane().setContent(content);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
		okButton.disableProperty().bind(nameField.textProperty().isEmpty());

		dialog.setResultConverter(button -> {
			if (button != ButtonType.OK) {
				return null;
			}
			Toggle selected = iconGroup.getSelectedToggle();
			InstanceIcon icon = selected != null ? (InstanceIcon) selected.getUserData() : InstanceIcon.builtin(BuiltinIcons.DEFAULT);
			Path customSource = icon.kind() == InstanceIcon.Kind.CUSTOM && customFileHolder[0] != null
					? customFileHolder[0].toPath() : null;
			return new Result(nameField.getText().trim(), versionBox.getValue(), icon, customSource);
		});

		return dialog.showAndWait();
	}

	private static VBox labeled(String label, javafx.scene.Node control) {
		Label l = new Label(label);
		l.getStyleClass().add("field-label");
		VBox box = new VBox(6, l, control);
		return box;
	}

	private static Color argbColor(int argb) {
		return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
	}
}
