package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.CapePhysicsPresetData;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/** Import-a-cape dialog: PNG picker (native file chooser), name field, and the five physics parameters. */
public final class CapeImportDialog {

	public record Result(String name, Path pngFile, CapePhysicsPresetData preset) {
	}

	private CapeImportDialog() {
	}

	public static Optional<Result> show(Stage owner) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Choose a cape texture (PNG, 2:1 ratio, " + CapeLibrary.TEXTURE_WIDTH + "x" + CapeLibrary.TEXTURE_HEIGHT
				+ " up to " + CapeLibrary.MAX_TEXTURE_WIDTH + "x" + CapeLibrary.MAX_TEXTURE_HEIGHT + ")");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return Optional.empty();
		}

		Dialog<Result> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Import Cape");
		dialog.setHeaderText("Configure \"" + file.getName() + "\"");
		DialogStyling.apply(dialog);

		TextField nameField = new TextField(stripExtension(file.getName()));
		Slider stiffness = presetSlider(0, 1, 0.6);
		Slider gravity = presetSlider(0, 3, 1.0);
		Slider damping = presetSlider(0, 1, 0.9);
		Slider wind = presetSlider(0, 2, 0.5);
		Slider segments = presetSlider(2, 16, 6);
		segments.setSnapToTicks(true);
		segments.setMajorTickUnit(1);
		segments.setBlockIncrement(1);

		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(10);
		grid.setPadding(new Insets(16));
		int row = 0;
		grid.addRow(row++, new Label("Name"), nameField);
		grid.addRow(row++, new Label("Stiffness"), stiffness);
		grid.addRow(row++, new Label("Gravity Multiplier"), gravity);
		grid.addRow(row++, new Label("Damping"), damping);
		grid.addRow(row++, new Label("Wind Responsiveness"), wind);
		grid.addRow(row++, new Label("Segments (perf vs fidelity)"), segments);

		dialog.getDialogPane().setContent(grid);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		dialog.setResultConverter(button -> {
			if (button != ButtonType.OK) {
				return null;
			}
			CapePhysicsPresetData preset = new CapePhysicsPresetData(
					(float) stiffness.getValue(), (float) gravity.getValue(), (float) damping.getValue(),
					(float) wind.getValue(), (int) Math.round(segments.getValue()));
			return new Result(nameField.getText().isBlank() ? "Cape" : nameField.getText(), file.toPath(), preset);
		});

		return dialog.showAndWait();
	}

	private static Slider presetSlider(double min, double max, double value) {
		Slider slider = new Slider(min, max, value);
		slider.setShowTickLabels(false);
		slider.setPrefWidth(220);
		return slider;
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}
}
