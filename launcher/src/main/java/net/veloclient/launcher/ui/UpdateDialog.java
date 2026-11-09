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
 * "Update available" dialog: release notes, then one button that pulls the new mod jars
 * and, if the launcher itself is newer, downloads and starts its installer and closes the launcher
 * so it can be replaced. Mod-only updates need no restart - they apply on the next game launch.
 */
public final class UpdateDialog {

	private UpdateDialog() {
	}

	public static void show(Stage owner, UpdateService.Release release) {
		show(owner, release, null);
	}

	/**
	 * {@code required}: non-null when this version is below the owner's minimum - the dialog says the update is needed
	 * to keep playing (with the owner's note, if any).
	 */
	public static void show(Stage owner, UpdateService.Release release, String required) {
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
				? "You have " + AppVersion.VERSION + ". Updating downloads the new launcher and mod, then restarts the launcher."
				: "Updated mod jars for " + String.join(", ", release.modAssets().keySet().stream()
						.filter(k -> !k.endsWith("-addons")).toList()) + ". They're used from your next game launch - no restart needed.");
		summary.setWrapText(true);
		if (required != null) {
			dialog.setHeaderText("Update required: Velo Client " + release.version());
			summary.setText("This update is needed to keep playing" + (required.isBlank() ? "." : " - " + required) + "\n" + summary.getText());
		}
		TextArea notes = new TextArea(release.notes().isBlank() ? "No release notes." : release.notes());
		notes.setEditable(false);
		notes.setWrapText(true);
		notes.setPrefRowCount(14);
		notes.setPrefColumnCount(56);
		Label status = new Label();
		status.setWrapText(true);
		Label detail = new Label();
		detail.getStyleClass().add("version-tag");
		Button openLog = new Button("Open the update log");
		openLog.setVisible(false);
		openLog.setManaged(false);
		openLog.setOnAction(e -> {
			try {
				java.nio.file.Path dir = UpdateService.logFile().getParent();
				java.nio.file.Files.createDirectories(dir);
				String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
				new ProcessBuilder(os.contains("win") ? "explorer.exe" : os.contains("mac") ? "open" : "xdg-open", dir.toString()).start();
			} catch (Exception ex) {
				status.setText("The log is at " + UpdateService.logFile());
			}
		});
		ProgressBar progress = new ProgressBar();
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		progress.setManaged(false);
		VBox body = new VBox(10, summary, notes, progress, detail, status, openLog);
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
			openLog.setVisible(false);
			openLog.setManaged(false);
			java.util.function.Consumer<UpdateService.Transfer> track = t -> Platform.runLater(() -> {
				progress.setProgress(t.fraction() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : t.fraction());
				detail.setText(describe(t));
			});
			Thread worker = new Thread(() -> {
				try {
					if (!release.modAssets().isEmpty()) {
						UpdateService.installModUpdatesTracked(release, text -> Platform.runLater(() -> status.setText(text)), track);
					}
					if (launcher) {
						boolean started = UpdateService.runInstallerTracked(release, text -> Platform.runLater(() -> status.setText(text)), track);
						if (started) {
							String os = System.getProperty("os.name", "").toLowerCase();
							boolean windows = os.contains("win");
							// macOS: the disk image opens in Finder - the player drags the app over the old one, so nothing to relaunch.
							boolean mac = os.contains("mac") || os.contains("darwin");
							Platform.runLater(() -> status.setText(windows ? "Installing - Velo Client closes and starts again by itself when it's done."
									: mac ? "Drag Velo Client Launcher into Applications (replace the old one) - Velo Client closes."
									: "Installed - restarting Velo Client..."));
							if (!windows && !mac) {
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
						detail.setText("");
						status.setText("Update failed: " + e.getMessage() + "\nPressing Update again continues where the download stopped.");
						openLog.setVisible(true);
						openLog.setManaged(true);
						update.setText("Try again");
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

	/** "12.3 / 98.0 MB  ·  4.2 MB/s  ·  about 20 s left" (+ the attempt when it's a retry). */
	public static String describe(UpdateService.Transfer t) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(java.util.Locale.ROOT, "%.1f", t.done() / 1048576.0));
		if (t.total() > 0) {
			sb.append(String.format(java.util.Locale.ROOT, " / %.1f", t.total() / 1048576.0));
		}
		sb.append(" MB");
		if (t.bytesPerSecond() > 0) {
			sb.append(String.format(java.util.Locale.ROOT, "  \u00b7  %.1f MB/s", t.bytesPerSecond() / 1048576.0));
			if (t.total() > 0) {
				long left = (long) Math.ceil((t.total() - t.done()) / t.bytesPerSecond());
				sb.append("  \u00b7  about ").append(left >= 90 ? (left + 30) / 60 + " min" : left + " s").append(" left");
			}
		}
		if (t.attempt() > 1) {
			sb.append("  \u00b7  try ").append(t.attempt());
		}
		return sb.toString();
	}
}
