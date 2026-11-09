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

		VBox content = new VBox(18);
		content.setPadding(new Insets(4, 0, 4, 0));
		content.setPrefWidth((VersionArt.TILE_W + 10) * 2 + 8 + 24);
		content.getChildren().addAll(
				labeled("Name", nameField),
				labeled("Minecraft version", versionTiles(versionBox)),
				labeled("Icon", iconPicker));

		// On a small laptop screen (or with Windows display scaling) the tiles + icons were taller than the screen and the
		// Create / Cancel buttons ended up below it (bug report, 2026-11-08): the content scrolls, the buttons always show.
		javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("scroll-pane");
		scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		double screenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
		scroll.setMaxHeight(Math.max(260, screenH - 230));
		scroll.setPrefViewportHeight(Math.min(content.prefHeight(-1) + 8, Math.max(260, screenH - 230)));
		dialog.getDialogPane().setContent(scroll);
		// wide enough for two version tiles side by side (the pane didn't follow the content's preferred width)
		double paneWidth = (VersionArt.TILE_W + 10) * 2 + 8 + 24 + 64;
		dialog.getDialogPane().setPrefWidth(paneWidth);
		dialog.getDialogPane().setMinWidth(paneWidth);
		boolean creating = initialName == null || initialName.isBlank();
		ButtonType confirm = new ButtonType(creating ? "Create profile" : "Save", ButtonBar.ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);

		Node okButton = dialog.getDialogPane().lookupButton(confirm);
		okButton.disableProperty().bind(nameField.textProperty().isEmpty());

		dialog.setResultConverter(button -> {
			if (button != confirm) {
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

	/**
	 * The versions as picture tiles (VersionArt) instead of a drop-down - picking one sets the (hidden) box the
	 * dialog reads its result from.
	 */
	private static Node versionTiles(ComboBox<String> versionBox) {
		FlowPane tiles = new FlowPane(8, 8);
		// each tile button is the picture + 3 px padding + 2 px border on both sides; two per row with the 8 px gap
		tiles.setPrefWrapLength((VersionArt.TILE_W + 10) * 2 + 8 + 12);
		ToggleGroup group = new ToggleGroup();
		GameVersion[] all = GameVersion.values();
		for (int i = all.length - 1; i >= 0; i--) {
			String id = all[i].id();
			ToggleButton button = new ToggleButton();
			button.setGraphic(VersionArt.tile(id, i == all.length - 1, VersionArt.TILE_W));
			button.setToggleGroup(group);
			button.setUserData(id);
			button.setStyle(tileStyle(false));
			button.selectedProperty().addListener((obs, was, now) -> button.setStyle(tileStyle(now)));
			button.setOnAction(e -> {
				if (!button.isSelected()) {
					button.setSelected(true); // a choice can't be un-picked
				}
				versionBox.setValue(id);
			});
			if (id.equals(versionBox.getValue())) {
				button.setSelected(true);
			}
			tiles.getChildren().add(button);
		}
		return tiles;
	}

	private static String tileStyle(boolean selected) {
		return "-fx-padding: 3; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-width: 2; -fx-cursor: hand;"
				+ (selected ? " -fx-background-color: rgba(255,59,68,0.18); -fx-border-color: #ff3b44;"
						: " -fx-background-color: transparent; -fx-border-color: rgba(255,255,255,0.08);");
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
