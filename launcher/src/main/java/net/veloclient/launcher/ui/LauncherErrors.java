package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.social.NewsApi;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When something breaks in the launcher itself, the player can tell us in one click: an error while it runs shows
 * a small card ("Something went wrong - Send bug report"), once per distinct error; a failure while starting shows
 * a small window instead of nothing at all. Reports go to the same place as game crash reports (Owner panel ›
 * Reports), with the stack trace and the end of the launcher log.
 */
public final class LauncherErrors {

	private static StackPane layers;
	private static final Set<String> SHOWN = ConcurrentHashMap.newKeySet();

	private LauncherErrors() {
	}

	/** Catches errors on every thread from now on (they're still logged as before). */
	public static void install(StackPane rootLayers) {
		layers = rootLayers;
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
			if (previous != null) {
				previous.uncaughtException(thread, error);
			}
			offer(error);
		});
	}

	/** Shows the "send a bug report" card for {@code error} (once per distinct error per run). */
	public static void offer(Throwable error) {
		String key = signature(error);
		if (!SHOWN.add(key) || layers == null) {
			return;
		}
		Platform.runLater(() -> showCard(error));
	}

	private static String signature(Throwable error) {
		StackTraceElement[] stack = error.getStackTrace();
		return error.getClass().getName() + (stack.length > 0 ? "@" + stack[0] : "");
	}

	private static void showCard(Throwable error) {
		Label title = new Label("Something went wrong");
		title.getStyleClass().add("t-strong");
		Label text = new Label("The launcher hit an error. Sending a report helps us fix it.");
		text.getStyleClass().add("t-muted");
		text.setWrapText(true);
		Button send = new Button("Send bug report");
		send.getStyleClass().addAll("primary-button", "button-sm");
		Button dismiss = new Button("Dismiss");
		dismiss.getStyleClass().addAll("ghost-button", "button-sm");
		HBox actions = new HBox(8, send, dismiss);
		StackPane badge = new StackPane(Icons.icon("bug", 18, Color.web("#FF7A7E")));
		badge.setMinSize(36, 36);
		badge.setMaxSize(36, 36);
		badge.setStyle("-fx-background-color: rgba(255,90,95,0.14); -fx-background-radius: 10;");
		VBox texts = new VBox(4, title, text, actions);
		VBox.setMargin(actions, new Insets(6, 0, 0, 0));
		HBox card = new HBox(12, badge, texts);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(14, 16, 14, 14));
		card.setMaxSize(380, Region.USE_PREF_SIZE);
		card.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 24, 0.2, 0, 8);");
		StackPane.setAlignment(card, Pos.BOTTOM_RIGHT);
		StackPane.setMargin(card, new Insets(0, 24, 110, 0));
		layers.getChildren().add(card);
		dismiss.setOnAction(e -> layers.getChildren().remove(card));
		send.setOnAction(e -> {
			layers.getChildren().remove(card);
			showReportDialog(layers.getScene() == null ? null : (Stage) layers.getScene().getWindow(), error, false);
		});
	}

	/** A small report form: what were you doing, include the log, send. */
	public static void showReportDialog(Stage owner, Throwable error, boolean startup) {
		Stage dialog = new Stage();
		if (owner != null) {
			dialog.initOwner(owner);
		}
		dialog.setTitle("Send a bug report");
		Label heading = new Label(startup ? "Velo Launcher couldn't start" : "Send a bug report");
		heading.getStyleClass().add("t-title-sm");
		Label sub = new Label(startup ? "Something broke while it was opening. Send us a report and we'll fix it."
				: "Tell us what you were doing - the error details are attached.");
		sub.getStyleClass().add("t-muted");
		sub.setWrapText(true);
		TextArea message = new TextArea();
		message.setPromptText("What were you doing? (optional)");
		message.setPrefRowCount(3);
		message.setWrapText(true);
		CheckBox includeLog = new CheckBox("Include the launcher log");
		includeLog.setSelected(true);
		TextArea details = new TextArea(stackTrace(error));
		details.setEditable(false);
		details.setPrefRowCount(6);
		details.setStyle("-fx-font-family: 'JetBrains Mono Medium'; -fx-font-size: 11px;");
		Label status = new Label();
		status.getStyleClass().add("t-muted");
		status.setWrapText(true);
		Button send = new Button("Send report");
		send.getStyleClass().add("primary-button");
		Button logs = new Button("Open logs folder");
		logs.getStyleClass().add("ghost-button");
		logs.setOnAction(e -> openFolder(VeloPaths.logs()));
		Button close = new Button(startup ? "Quit" : "Close");
		close.getStyleClass().add("ghost-button");
		close.setOnAction(e -> {
			dialog.close();
			if (startup) {
				Platform.exit();
			}
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(8, logs, spacer, close, send);
		send.setOnAction(e -> {
			send.setDisable(true);
			status.setText("Sending...");
			JsonObject report = report(error, message.getText().trim(), includeLog.isSelected(), startup);
			Thread thread = new Thread(() -> {
				try {
					String id = NewsApi.sendReport(report);
					Platform.runLater(() -> {
						status.setText("Thanks! Your report (#" + id + ") reached the Velo team.");
						send.setText("Sent");
					});
				} catch (Exception ex) {
					Platform.runLater(() -> {
						send.setDisable(false);
						status.setText("Couldn't send it: " + ex.getMessage());
					});
				}
			}, "velo-bug-report");
			thread.setDaemon(true);
			thread.start();
		});
		VBox root = new VBox(12, heading, sub, message, includeLog, new Label("Error details"), details, status, actions);
		root.setPadding(new Insets(22));
		root.getStyleClass().add("root");
		root.setStyle("-fx-background-color: #121216;");
		Scene scene = new Scene(root, 560, startup ? 520 : 500);
		try {
			scene.getStylesheets().add(LauncherErrors.class.getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		} catch (RuntimeException ignored) {
			// Unstyled is fine here.
		}
		dialog.setScene(scene);
		dialog.show();
	}

	private static JsonObject report(Throwable error, String message, boolean includeLog, boolean startup) {
		JsonObject report = new JsonObject();
		report.addProperty("kind", "bug");
		report.addProperty("source", "launcher");
		report.addProperty("title", (startup ? "Launcher didn't start: " : "Launcher error: ") + error.getClass().getSimpleName()
				+ (error.getMessage() == null ? "" : " - " + error.getMessage()));
		report.addProperty("message", message);
		JsonObject system = new JsonObject();
		system.addProperty("version", AppVersion.VERSION);
		system.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
		system.addProperty("java", System.getProperty("java.version"));
		report.add("system", system);
		report.addProperty("crash", stackTrace(error));
		if (includeLog) {
			report.addProperty("log", logTail(400));
		}
		return report;
	}

	private static String stackTrace(Throwable error) {
		StringWriter out = new StringWriter();
		error.printStackTrace(new PrintWriter(out));
		return out.toString();
	}

	private static String logTail(int lines) {
		try {
			Path file = VeloPaths.logs().resolve("launcher.log");
			List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
			return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
		} catch (Exception e) {
			return "(log unreadable: " + e + ")";
		}
	}

	private static void openFolder(Path folder) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			new ProcessBuilder(os.contains("win") ? new String[] {"explorer", folder.toString()}
					: os.contains("mac") ? new String[] {"open", folder.toString()} : new String[] {"xdg-open", folder.toString()}).start();
		} catch (Exception e) {
			LauncherLog.warn("Couldn't open " + folder, e);
		}
	}
}
