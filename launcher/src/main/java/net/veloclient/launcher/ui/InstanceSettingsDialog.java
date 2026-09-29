package net.veloclient.launcher.ui;

import com.sun.management.OperatingSystemMXBean;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.launch.PerformanceModsInstaller;

import java.lang.management.ManagementFactory;
import java.util.Optional;

/** Per-instance RAM allocation and extra JVM arguments, opened from the profile card's gear icon. */
public final class InstanceSettingsDialog {

	private static final int DEFAULT_MAX_MB = 4096;

	private InstanceSettingsDialog() {
	}

	public static Optional<Instance> show(Stage owner, Instance instance) {
		Dialog<Instance> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(instance.name() + " - Settings");
		dialog.setHeaderText(instance.name() + " - RAM & JVM Settings");
		DialogStyling.apply(dialog);

		int systemMemoryMb = totalSystemMemoryMb();
		int initialMax = instance.ramMaxMb() != null ? instance.ramMaxMb() : DEFAULT_MAX_MB;
		// Unset min defaults to max, matching GameLauncher (-Xms = -Xmx avoids heap-resize stutter);
		// showing 1024 here used to save that low value the first time anyone pressed OK.
		int initialMin = instance.ramMinMb() != null ? instance.ramMinMb() : initialMax;

		Slider minSlider = new Slider(512, systemMemoryMb, initialMin);
		Label minLabel = new Label(initialMin + " MB");
		minSlider.valueProperty().addListener((obs, o, n) -> minLabel.setText(Math.round(n.doubleValue()) + " MB"));

		Slider maxSlider = new Slider(1024, systemMemoryMb, initialMax);
		Label maxLabel = new Label(initialMax + " MB");
		maxSlider.valueProperty().addListener((obs, o, n) -> maxLabel.setText(Math.round(n.doubleValue()) + " MB"));

		TextField extraArgsField = new TextField(instance.extraJvmArgs() != null ? instance.extraJvmArgs() : "");
		extraArgsField.setPromptText("e.g. -XX:+UseG1GC");

		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(12);
		grid.setPadding(new Insets(16));
		grid.addRow(0, new Label("Minimum RAM"), minSlider, minLabel);
		grid.addRow(1, new Label("Maximum RAM"), maxSlider, maxLabel);
		grid.addRow(2, new Label("Extra JVM args"), extraArgsField);
		Label systemInfo = new Label("This computer has " + systemMemoryMb + " MB of RAM.");
		systemInfo.getStyleClass().add("version-tag");
		grid.addRow(3, systemInfo);
		PerformanceModsInstaller.State perfState = PerformanceModsInstaller.readState(instance.id());
		CheckBox perfMods = new CheckBox("Install performance mods (Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast, ModernFix)");
		perfMods.setSelected(perfState.enabled);
		grid.add(perfMods, 0, 4, 3, 1);

		dialog.getDialogPane().setContent(grid);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		dialog.setResultConverter(button -> {
			if (button != ButtonType.OK) {
				return null;
			}
			if (perfState.enabled != perfMods.isSelected()) {
				perfState.enabled = perfMods.isSelected();
				PerformanceModsInstaller.writeState(instance.id(), perfState);
			}
			int min = (int) Math.round(minSlider.getValue());
			int max = (int) Math.max(min, Math.round(maxSlider.getValue()));
			String extraArgs = extraArgsField.getText().trim();
			return instance.withSettings(min, max, extraArgs.isEmpty() ? null : extraArgs);
		});

		return dialog.showAndWait();
	}

	private static int totalSystemMemoryMb() {
		try {
			var bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
			return (int) (bean.getTotalMemorySize() / (1024 * 1024));
		} catch (Exception e) {
			return 16384;
		}
	}
}
