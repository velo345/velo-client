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

	private InstanceSettingsDialog() {
	}

	public static Optional<Instance> show(Stage owner, Instance instance) {
		Dialog<Instance> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(instance.name() + " - Settings");
		dialog.setHeaderText(instance.name() + " - RAM & JVM Settings");
		DialogStyling.apply(dialog);

		int systemMemoryMb = totalSystemMemoryMb();
		boolean initiallyAuto = instance.ramMaxMb() == null || net.veloclient.launcher.launch.MemoryPlanner.isLegacyDefault(instance);
		net.veloclient.launcher.launch.MemoryPlanner.Plan autoPlan = net.veloclient.launcher.launch.MemoryPlanner.plan(instance.withSettings(null, null, instance.extraJvmArgs()));
		int initialMax = instance.ramMaxMb() != null && !initiallyAuto ? instance.ramMaxMb() : autoPlan.maxMb();
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

		CheckBox automatic = new CheckBox("Automatic memory (recommended) - currently " + autoPlan.summary());
		automatic.setSelected(initiallyAuto);
		Label autoWhy = new Label(String.join(" - ", autoPlan.reasons()));
		autoWhy.getStyleClass().add("version-tag");
		autoWhy.setWrapText(true);
		Runnable syncSliders = () -> {
			minSlider.setDisable(automatic.isSelected());
			maxSlider.setDisable(automatic.isSelected());
		};
		automatic.setOnAction(e -> syncSliders.run());
		syncSliders.run();

		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(12);
		grid.setPadding(new Insets(16));
		grid.add(automatic, 0, 0, 3, 1);
		grid.add(autoWhy, 0, 1, 3, 1);
		grid.addRow(2, new Label("Minimum RAM"), minSlider, minLabel);
		grid.addRow(3, new Label("Maximum RAM"), maxSlider, maxLabel);
		grid.addRow(4, new Label("Extra JVM args"), extraArgsField);
		Label systemInfo = new Label("This computer has " + systemMemoryMb + " MB of RAM.");
		systemInfo.getStyleClass().add("version-tag");
		grid.addRow(5, systemInfo);
		PerformanceModsInstaller.State perfState = PerformanceModsInstaller.readState(instance.id());
		CheckBox perfMods = new CheckBox("Install performance mods (Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast, ModernFix)");
		perfMods.setSelected(perfState.enabled);
		grid.add(perfMods, 0, 6, 3, 1);

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
			String extraArgs = extraArgsField.getText().trim();
			if (automatic.isSelected()) {
				return instance.withSettings(null, null, extraArgs.isEmpty() ? null : extraArgs);
			}
			int min = (int) Math.round(minSlider.getValue());
			int max = (int) Math.max(min, Math.round(maxSlider.getValue()));
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
