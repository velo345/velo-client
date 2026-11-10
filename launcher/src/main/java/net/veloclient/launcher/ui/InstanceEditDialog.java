package net.veloclient.launcher.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
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

	static final double RAIL_W = 600;

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
		iconPicker.setPrefWrapLength(RAIL_W);
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
		content.setPrefWidth(RAIL_W);
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
		// a fixed estimate: content.prefHeight() before CSS was ~70 px, so only the name field showed ("komplett leer")
		scroll.setPrefViewportHeight(Math.min(430, Math.max(260, screenH - 230)));
		dialog.getDialogPane().setContent(scroll);
		// wide enough for two version tiles side by side (the pane didn't follow the content's preferred width)
		double paneWidth = RAIL_W + 64;
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
	private static final double RAIL_TILE_W = 172;

	private static Node versionTiles(ComboBox<String> versionBox) {
		// 2026-11: one smooth horizontal rail (newest first) instead of a two-column wall the scroll pane cut off -
		// the dialog looked empty because only the name field fit in its first viewport.
		HBox tiles = new HBox(10);
		tiles.setPadding(new Insets(2));
		ToggleGroup group = new ToggleGroup();
		GameVersion[] all = GameVersion.values();
		ToggleButton[] selected = new ToggleButton[1];
		for (int i = all.length - 1; i >= 0; i--) {
			String id = all[i].id();
			ToggleButton button = new ToggleButton();
			button.setGraphic(VersionArt.tile(id, i == all.length - 1, RAIL_TILE_W));
			button.setToggleGroup(group);
			button.setUserData(id);
			button.setMinWidth(Region.USE_PREF_SIZE);
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
				selected[0] = button;
			}
			tiles.getChildren().add(button);
		}
		ScrollPane rail = new ScrollPane(tiles);
		rail.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		rail.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		rail.setFitToHeight(true);
		rail.setPannable(true);
		rail.getStyleClass().add("version-rail");
		rail.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
		double[] target = {0};
		Timeline[] glide = new Timeline[1];
		java.util.function.DoubleConsumer glideTo = to -> {
			target[0] = Math.max(0, Math.min(1, to));
			if (glide[0] != null) {
				glide[0].stop();
			}
			glide[0] = new Timeline(new KeyFrame(Duration.millis(380),
					new KeyValue(rail.hvalueProperty(), target[0], Interpolator.SPLINE(0.2, 0.8, 0.25, 1))));
			glide[0].play();
		};
		java.util.function.DoubleSupplier step = () -> {
			double overflow = tiles.getWidth() - rail.getViewportBounds().getWidth();
			return overflow <= 0 ? 1 : (RAIL_TILE_W + 20) * 2 / overflow;
		};
		// the wheel scrolls sideways, smoothly
		rail.addEventFilter(ScrollEvent.SCROLL, e -> {
			double delta = Math.abs(e.getDeltaX()) > Math.abs(e.getDeltaY()) ? e.getDeltaX() : e.getDeltaY();
			if (delta == 0) {
				return;
			}
			double from = glide[0] != null && glide[0].getStatus() == javafx.animation.Animation.Status.RUNNING ? target[0] : rail.getHvalue();
			glideTo.accept(from - Math.signum(delta) * step.getAsDouble() / 2);
			e.consume();
		});
		Button left = arrow("chevron-left", () -> glideTo.accept(rail.getHvalue() - step.getAsDouble()));
		Button right = arrow("chevron-right", () -> glideTo.accept(rail.getHvalue() + step.getAsDouble()));
		left.visibleProperty().bind(rail.hvalueProperty().greaterThan(0.01));
		right.visibleProperty().bind(rail.hvalueProperty().lessThan(0.99));
		StackPane.setAlignment(left, Pos.CENTER_LEFT);
		StackPane.setAlignment(right, Pos.CENTER_RIGHT);
		StackPane.setMargin(left, new Insets(0, 0, 0, 6));
		StackPane.setMargin(right, new Insets(0, 6, 0, 0));
		StackPane holder = new StackPane(rail, left, right);
		holder.setMinWidth(0);
		holder.setPrefWidth(RAIL_W);
		holder.setMaxWidth(RAIL_W);
		// the picked version in view (editing an older profile)
		if (selected[0] != null) {
			javafx.application.Platform.runLater(() -> {
				double overflow = tiles.getWidth() - rail.getViewportBounds().getWidth();
				if (overflow > 0) {
					rail.setHvalue(Math.max(0, Math.min(1, (selected[0].getBoundsInParent().getMinX() - 40) / overflow)));
				}
			});
		}
		Label hint = new Label("Newest first - Velo picks the right loader (Fabric or Ornithe) for you.");
		hint.getStyleClass().add("t-muted");
		hint.setStyle("-fx-font-size: 12px;");
		return new VBox(8, holder, hint);
	}

	private static Button arrow(String icon, Runnable action) {
		Button button = new Button();
		button.setGraphic(Icons.icon(icon, 14, Color.web("#F4EFEE"), 2.4));
		button.getStyleClass().add("icon-button");
		button.setStyle("-fx-background-color: rgba(12,10,10,0.82); -fx-background-radius: 9; -fx-border-color: rgba(255,255,255,0.12);"
				+ " -fx-border-radius: 9; -fx-min-width: 32; -fx-min-height: 32; -fx-max-width: 32; -fx-max-height: 32; -fx-cursor: hand;");
		button.setOnAction(e -> action.run());
		return button;
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
