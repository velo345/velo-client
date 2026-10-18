package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.RunningInstanceManager;
import net.veloclient.launcher.launch.UpdateService;

/**
 * "Update available" dialog: release notes, then one button that pulls the new mod and addons jars
 * and, if the launcher itself is newer, downloads and starts its installer and closes the launcher
 * so it can be replaced. Mod-only updates need no restart - they apply on the next game launch.
 */
public final class UpdateDialog {

	private UpdateDialog() {
	}

	public static void show(Stage owner, UpdateService.Release release) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		boolean launcher = release.launcherNewer() && release.installer() != null;
		dialog.setTitle("Update Velo Client");
		dialog.setHeaderText(launcher ? "Velo Client " + release.version() + " is available"
				: "New Velo Client mod build (" + release.version() + ")");

		if (!launcher && release.launcherNewer() == false && !UpdateService.packaged()
				&& UpdateService.compare(release.version(), AppVersion.VERSION) > 0) {
			// Started from the source tree: there is no installed launcher to replace.
			dialog.setHeaderText("Velo Client " + release.version() + " is out");
		}
		Label summary = new Label(launcher
				? "You have " + AppVersion.VERSION + ". Updating downloads the new launcher, mod and addons, then restarts the launcher."
				: "Updated mod and addons jars for " + String.join(", ", release.modAssets().keySet().stream()
						.filter(k -> !k.endsWith("-addons")).toList()) + ". They're used from your next game launch - no restart needed.");
		summary.setWrapText(true);
		TextArea notes = new TextArea(release.notes().isBlank() ? "No release notes." : release.notes());
		notes.setEditable(false);
		notes.setWrapText(true);
		notes.setPrefRowCount(14);
		notes.setPrefColumnCount(56);
		Label status = new Label();
		status.setWrapText(true);
		ProgressBar progress = new ProgressBar();
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		progress.setManaged(false);
		VBox body = new VBox(10, summary, notes, progress, status);
		body.setPadding(new Insets(4, 0, 0, 0));
		dialog.getDialogPane().setContent(body);

		ButtonType updateType = new ButtonType(launcher ? "Update & restart" : "Update", ButtonBar.ButtonData.OK_DONE);
		ButtonType laterType = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
		dialog.getDialogPane().getButtonTypes().addAll(updateType, laterType);
		DialogStyling.apply(dialog);
		Button update = (Button) dialog.getDialogPane().lookupButton(updateType);
		Button later = (Button) dialog.getDialogPane().lookupButton(laterType);
		update.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
			event.consume();
			if (launcher && !RunningInstanceManager.running().isEmpty()) {
				status.setText("Close your running game first - the installer replaces files it may still use.");
				return;
			}
			update.setDisable(true);
			later.setDisable(true);
			progress.setVisible(true);
			progress.setManaged(true);
			Thread worker = new Thread(() -> {
				try {
					if (!release.modAssets().isEmpty()) {
						UpdateService.installModUpdates(release, text -> Platform.runLater(() -> status.setText(text)),
								f -> Platform.runLater(() -> progress.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f)));
					}
					if (launcher) {
						boolean started = UpdateService.runInstaller(release, text -> Platform.runLater(() -> status.setText(text)),
								f -> Platform.runLater(() -> progress.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f)));
						if (started) {
							boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
							Platform.runLater(() -> status.setText(windows ? "The installer takes over now - Velo Client closes." : "Installed - restarting Velo Client..."));
							if (!windows) {
								// The package manager finished replacing the files: start the new launcher once this one is gone.
								UpdateService.relaunch();
							}
							Thread.sleep(800);
							Platform.runLater(() -> {
								Platform.exit();
								System.exit(0);
							});
							return;
						}
					}
					Platform.runLater(() -> {
						progress.setVisible(false);
						progress.setManaged(false);
						status.setText(launcher ? "Mod updated. This system has no launcher installer - get it from " + release.pageUrl()
								: !UpdateService.packaged() ? "Mods updated. This launcher runs from the source folder - update it with git pull."
								: "Done - your next game launch uses the new build.");
						later.setText("Close");
						later.setDisable(false);
					});
				} catch (Exception e) {
					LauncherLog.warn("Update failed", e);
					Platform.runLater(() -> {
						progress.setVisible(false);
						progress.setManaged(false);
						status.setText("Update failed: " + e.getMessage());
						update.setDisable(false);
						later.setDisable(false);
					});
				}
			}, "velo-updater");
			worker.setDaemon(true);
			worker.start();
		});
		dialog.showAndWait();
	}
}
