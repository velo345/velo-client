package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.launch.UpdateService;

/**
 * "Repair Velo": for when something is broken and nobody knows why - puts back the Velo mod in every profile, clears
 * Fabric's cache, fetches the current mod build and (second button) reinstalls the launcher itself. Everything it does
 * is written to update.log. Reached from Settings > Updates & system and from the launch-failure dialog.
 */
public final class RepairDialog {

	private RepairDialog() {
	}

	public static void show(Stage owner) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Repair Velo Client");
		dialog.setHeaderText("Repair Velo Client");
		Label summary = new Label("Puts the current Velo mod back into every profile, clears Fabric's cache (it rebuilds itself on the"
				+ " next start) and downloads the current mod build again. Your worlds, settings and other mods aren't touched."
				+ "\n\n\"Repair everything\" also reinstalls the launcher - it closes and starts again by itself.");
		summary.setWrapText(true);
		summary.setPrefWidth(460);
		Label status = new Label();
		status.setWrapText(true);
		Label detail = new Label();
		detail.getStyleClass().add("version-tag");
		ProgressBar progress = new ProgressBar();
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		progress.setManaged(false);
		Button openLog = new Button("Open the log");
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
		VBox body = new VBox(10, summary, progress, detail, status, openLog);
		body.setPadding(new Insets(4, 0, 0, 0));
		dialog.getDialogPane().setContent(body);

		ButtonType modsType = new ButtonType("Repair mods", ButtonBar.ButtonData.OTHER);
		ButtonType allType = new ButtonType("Repair everything", ButtonBar.ButtonData.OK_DONE);
		ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
		dialog.getDialogPane().getButtonTypes().addAll(modsType, allType, closeType);
		DialogStyling.apply(dialog);
		Button mods = (Button) dialog.getDialogPane().lookupButton(modsType);
		Button all = (Button) dialog.getDialogPane().lookupButton(allType);
		Button close = (Button) dialog.getDialogPane().lookupButton(closeType);
		for (Button button : new Button[] {mods, all}) {
			boolean launcherToo = button == all;
			button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
				event.consume();
				mods.setDisable(true);
				all.setDisable(true);
				close.setDisable(true);
				progress.setVisible(true);
				progress.setManaged(true);
				progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
				openLog.setVisible(false);
				openLog.setManaged(false);
				Thread worker = new Thread(() -> {
					try {
						boolean restarting = UpdateService.repair(launcherToo, text -> Platform.runLater(() -> status.setText(text)),
								t -> Platform.runLater(() -> {
									progress.setProgress(t.fraction() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : t.fraction());
									detail.setText(UpdateDialog.describe(t));
								}));
						if (restarting) {
							Thread.sleep(800);
							Platform.runLater(() -> {
								Platform.exit();
								System.exit(0);
							});
							return;
						}
						Platform.runLater(() -> {
							progress.setVisible(false);
							progress.setManaged(false);
							detail.setText("");
							close.setDisable(false);
							openLog.setVisible(true);
							openLog.setManaged(true);
						});
					} catch (Exception e) {
						LauncherLog.warn("Repair failed", e);
						Platform.runLater(() -> {
							progress.setVisible(false);
							progress.setManaged(false);
							status.setText("Repair failed: " + e.getMessage());
							mods.setDisable(false);
							all.setDisable(false);
							close.setDisable(false);
							openLog.setVisible(true);
							openLog.setManaged(true);
						});
					}
				}, "velo-repair");
				worker.setDaemon(true);
				worker.start();
			});
		}
		dialog.showAndWait();
	}
}
