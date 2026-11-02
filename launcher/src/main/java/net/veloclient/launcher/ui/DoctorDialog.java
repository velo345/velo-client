package net.veloclient.launcher.ui;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import net.veloclient.launcher.doctor.Diagnosis;
import net.veloclient.launcher.doctor.Doctor;
import net.veloclient.launcher.instance.Instance;

/**
 * Before starting: the Doctor found something that would crash the game. "Fix all & play" fixes it and starts right
 * away; "Play anyway" starts as it is; Cancel stays in the launcher.
 */
public final class DoctorDialog {

	private DoctorDialog() {
	}

	/** True = start the game now. */
	public static boolean confirmLaunch(Stage owner, Instance instance, Diagnosis diagnosis) {
		Dialog<Boolean> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Velo Doctor");
		DialogStyling.apply(dialog);
		ButtonType play = new ButtonType("Play anyway", ButtonBar.ButtonData.OTHER);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, play);
		boolean[] start = {false};
		DoctorPanel[] panel = new DoctorPanel[1];
		panel[0] = DoctorPanel.create(instance, DoctorPanel.Mode.PRELAUNCH, diagnosis, () -> Doctor.check(instance), new DoctorPanel.Host() {
			@Override
			public void fixed(boolean all) {
				Diagnosis now = panel[0].diagnosis();
				boolean stillCrashes = now != null && now.issues().stream().anyMatch(i -> i.severity() == Diagnosis.Severity.CRASH);
				if (!stillCrashes) {
					// Fixed: straight into the game, no extra click.
					start[0] = true;
					dialog.setResult(true);
					dialog.close();
				}
			}

			@Override
			public void report(Diagnosis d) {
				BugReportDialog.show(owner, "Doctor couldn't fix: " + instance.name(), null, Doctor.reportData(instance, d));
			}
		});
		panel[0].node().setStyle("-fx-pref-width: 620;");
		dialog.getDialogPane().setContent(panel[0].node());
		dialog.setResultConverter(button -> button == play || start[0]);
		boolean go = dialog.showAndWait().orElse(start[0]);
		Diagnosis left = panel[0].diagnosis();
		if (go && left != null && left.issues().stream().anyMatch(i -> i.severity() == Diagnosis.Severity.CRASH)) {
			Doctor.playingAnyway(instance, left);
		}
		return go;
	}
}
