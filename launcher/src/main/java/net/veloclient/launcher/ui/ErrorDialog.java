package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A deliberately-styled replacement for a bland default {@code Alert} when a
 * launch fails or Minecraft crashes - surfaces what actually went wrong (a
 * crash report excerpt, a launcher log tail, or an exception) instead of the
 * game window just silently vanishing, with a scrollable monospace details
 * area and quick access to the underlying files. This does not attempt to
 * diagnose or resolve mod/shader incompatibilities - it just makes sure a
 * failure is visible and inspectable rather than invisible.
 */
public final class ErrorDialog {

	private ErrorDialog() {
	}

	public static void show(Stage owner, String title, String message, String details, Path folderToOpen) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(title);
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

		VBox root = new VBox(12);
		root.setPadding(new Insets(4));
		root.setPrefSize(520, 380);

		HBox header = new HBox(10);
		Label icon = new Label("⚠");
		icon.getStyleClass().add("error-dialog-icon");
		Label headline = new Label(title);
		headline.getStyleClass().add("error-dialog-title");
		headline.setWrapText(true);
		header.getChildren().addAll(icon, headline);

		Label messageLabel = new Label(message != null ? message : "");
		messageLabel.getStyleClass().add("error-dialog-message");
		messageLabel.setWrapText(true);

		root.getChildren().addAll(header, messageLabel);

		if (details != null && !details.isBlank()) {
			TextArea detailsArea = new TextArea(details);
			detailsArea.getStyleClass().add("error-details-area");
			detailsArea.setEditable(false);
			detailsArea.setWrapText(false);
			VBox.setVgrow(detailsArea, Priority.ALWAYS);
			root.getChildren().add(detailsArea);

			HBox actions = new HBox(8);
			Button copyButton = new Button("Copy Details");
			copyButton.setOnAction(e -> {
				ClipboardContent content = new ClipboardContent();
				content.putString(details);
				Clipboard.getSystemClipboard().setContent(content);
			});
			actions.getChildren().add(copyButton);
			if (folderToOpen != null) {
				Button openFolderButton = new Button("Open Folder");
				openFolderButton.setOnAction(e -> openInFileManager(folderToOpen));
				actions.getChildren().add(openFolderButton);
			}
			root.getChildren().add(actions);
		}

		dialog.getDialogPane().setContent(root);
		dialog.showAndWait();
	}

	/** Looks for the most recent crash report in the instance's game directory, falling back to this run's own launcher log tail if none exists yet. */
	public static void showLaunchFailure(Stage owner, Instance instance, int exitCode, boolean crashedEarly, Path runLogFile) {
		Path gameDir = InstancePaths.gameDir(instance.id());
		Path crashReportsDir = gameDir.resolve("crash-reports");
		String details = null;
		Path folderToOpen = InstancePaths.logsDir(instance.id());

		Path latestCrashReport = findLatestCrashReport(crashReportsDir);
		if (latestCrashReport != null) {
			details = readHead(latestCrashReport, 80);
			folderToOpen = crashReportsDir;
		} else if (runLogFile != null && Files.exists(runLogFile)) {
			details = readTail(runLogFile, 80);
		}

		String message = crashedEarly
				? instance.name() + " crashed shortly after starting (exit code " + exitCode + "). This is usually an "
						+ "incompatibility between installed mods/shaders rather than a Velo Client bug - check the "
						+ "details below for the actual cause."
				: instance.name() + " exited unexpectedly with code " + exitCode + ".";

		show(owner, "Minecraft exited unexpectedly", message, details, folderToOpen);
	}

	private static Path findLatestCrashReport(Path crashReportsDir) {
		if (!Files.isDirectory(crashReportsDir)) {
			return null;
		}
		try (var files = Files.list(crashReportsDir)) {
			List<Path> reports = files.filter(p -> p.toString().endsWith(".txt")).toList();
			return reports.stream().max(Comparator.comparingLong(ErrorDialog::lastModified)).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static long lastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static String readHead(Path file, int maxLines) {
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			return String.join("\n", lines.subList(0, Math.min(maxLines, lines.size())));
		} catch (IOException e) {
			return "Couldn't read " + file + ": " + e.getMessage();
		}
	}

	private static String readTail(Path file, int maxLines) {
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			return String.join("\n", lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
		} catch (IOException e) {
			return "Couldn't read " + file + ": " + e.getMessage();
		}
	}

	private static void openInFileManager(Path folder) {
		try {
			Files.createDirectories(folder);
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String[] command = os.contains("win") ? new String[] {"explorer.exe", folder.toString()}
					: os.contains("mac") ? new String[] {"open", folder.toString()}
					: new String[] {"xdg-open", folder.toString()};
			new ProcessBuilder(command).start();
		} catch (IOException ignored) {
			// Best-effort.
		}
	}
}
