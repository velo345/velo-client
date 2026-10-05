package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.social.NewsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shown when Minecraft closes with an error: what went wrong in one line, and a one-click bug
 * report (crash report, log, mods, system info, plus an optional note from the player) to the Velo
 * team. Replaces the old plain error box for game crashes.
 */
public final class CrashReportDialog {

	private CrashReportDialog() {
	}

	/** Crash report written during this run (newer than {@code startedAt}), or null. */
	private static Path crashReport(Instance instance, long startedAt) {
		Path dir = InstancePaths.gameDir(instance.id()).resolve("crash-reports");
		if (!Files.isDirectory(dir)) {
			return null;
		}
		try (var files = Files.list(dir)) {
			return files.filter(p -> p.toString().endsWith(".txt")).filter(p -> modified(p) >= startedAt - 2000)
					.max(Comparator.comparingLong(CrashReportDialog::modified)).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	public static void show(Stage owner, Instance instance, int exitCode, long startedAt, Path runLog) {
		Path crashFile = crashReport(instance, startedAt);
		String crash = crashFile == null ? null : read(crashFile, 400_000);
		String log = runLog != null && Files.exists(runLog) ? tail(runLog, 3000) : null;
		Path latestLog = InstancePaths.gameDir(instance.id()).resolve("logs").resolve("latest.log");
		if (Files.exists(latestLog) && modified(latestLog) >= startedAt) {
			log = tail(latestLog, 3000);
		}
		String cause = cause(crash, log);

		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Minecraft crashed");
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

		Label icon = new Label("!");
		icon.getStyleClass().add("crash-icon");
		Label headline = new Label(instance.name() + " crashed");
		headline.getStyleClass().add("error-dialog-title");
		Label sub = new Label(cause != null ? cause : "Minecraft closed with error code " + exitCode + ".");
		sub.getStyleClass().add("crash-cause");
		sub.setWrapText(true);
		VBox titles = new VBox(4, headline, sub);
		HBox.setHgrow(titles, Priority.ALWAYS);
		HBox header = new HBox(14, icon, titles);
		header.setAlignment(Pos.TOP_LEFT);

		Label askLabel = new Label("Help us fix it - what were you doing when it crashed? (optional)");
		askLabel.getStyleClass().add("news-field-label");
		TextArea message = new TextArea();
		message.setPromptText("e.g. \"I joined a server and opened the world map\"");
		message.setWrapText(true);
		message.setPrefRowCount(3);
		CheckBox includeLog = new CheckBox("Include the crash report and game log (recommended)");
		includeLog.setSelected(true);

		Map<String, String> system = system(instance, exitCode);
		List<String> mods = mods(instance);
		StringBuilder sent = new StringBuilder();
		system.forEach((k, v) -> sent.append(k).append(": ").append(v).append('\n'));
		sent.append("Mods: ").append(mods.size()).append(" files\n");
		sent.append(crash != null ? "Crash report: " + crashFile.getFileName() + "\n" : "No crash report file was written.\n");
		sent.append(log != null ? "Game log: last " + log.lines().count() + " lines\n" : "");
		TextArea sentArea = new TextArea(sent.toString());
		sentArea.setEditable(false);
		sentArea.setPrefRowCount(7);
		sentArea.getStyleClass().add("mono-area");
		TitledPane what = new TitledPane("What gets sent", sentArea);
		what.setExpanded(false);

		Label status = new Label();
		status.getStyleClass().add("settings-row-hint");
		status.setWrapText(true);
		Button send = new Button("Send report");
		send.getStyleClass().add("primary-button");
		Button copy = new Button("Copy details");
		copy.getStyleClass().add("glass-button");
		String details = crash != null ? crash : log;
		copy.setDisable(details == null);
		copy.setOnAction(e -> {
			ClipboardContent content = new ClipboardContent();
			content.putString(details);
			Clipboard.getSystemClipboard().setContent(content);
			copy.setText("Copied");
		});
		Button folder = new Button("Open folder");
		folder.getStyleClass().add("glass-button");
		Path folderPath = crashFile != null ? crashFile.getParent() : InstancePaths.gameDir(instance.id()).resolve("logs");
		folder.setOnAction(e -> openFolder(folderPath));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(8, copy, folder, spacer, send);
		actions.setAlignment(Pos.CENTER_LEFT);

		String crashText = crash;
		String logText = log;
		send.setOnAction(e -> {
			JsonObject report = new JsonObject();
			report.addProperty("kind", "crash");
			report.addProperty("source", "launcher");
			report.addProperty("title", cause != null ? cause : "Exit code " + exitCode);
			report.addProperty("message", message.getText().trim());
			JsonObject sys = new JsonObject();
			system.forEach(sys::addProperty);
			report.add("system", sys);
			JsonArray modList = new JsonArray();
			mods.forEach(modList::add);
			report.add("mods", modList);
			if (includeLog.isSelected()) {
				report.addProperty("crash", crashText);
				report.addProperty("log", logText);
			}
			send.setDisable(true);
			status.setText("Sending...");
			NewsView.run(() -> NewsApi.sendReport(report), id -> {
				status.setText("Thanks! Your report (#" + id + ") reached the Velo team.");
				send.setText("Sent");
				markHandled(instance, crashFile);
			}, error -> {
				send.setDisable(false);
				status.setText("Couldn't send it: " + error);
			});
		});

		VBox root = new VBox(14, header, askLabel, message, includeLog, what, actions, status);
		root.setPadding(new Insets(6));
		root.setPrefWidth(560);
		root.getStyleClass().add("crash-dialog");
		dialog.getDialogPane().setContent(root);
		dialog.setOnHidden(e -> markHandled(instance, crashFile));
		dialog.show();
	}

	/** Tells the mod this crash was already dealt with, so it doesn't ask again on the next start. */
	private static void markHandled(Instance instance, Path crashFile) {
		if (crashFile == null) {
			return;
		}
		try {
			Path marker = crashFile.getParent().resolve(".velo-handled");
			String name = crashFile.getFileName().toString();
			List<String> lines = Files.exists(marker) ? new ArrayList<>(Files.readAllLines(marker)) : new ArrayList<>();
			if (!lines.contains(name)) {
				lines.add(name);
				Files.write(marker, lines);
			}
		} catch (IOException ignored) {
			// Worst case the game asks once more.
		}
	}

	/** The one line that says what broke: the crash report's description + first exception, or the last error in the log. */
	static String cause(String crash, String log) {
		if (crash != null) {
			String description = null;
			String exception = null;
			for (String line : crash.lines().toList()) {
				if (description == null && line.startsWith("Description: ")) {
					description = line.substring("Description: ".length()).trim();
				} else if (description != null && exception == null && !line.isBlank()) {
					exception = line.trim();
					break;
				}
			}
			if (exception != null) {
				return (description != null ? description + ": " : "") + shorten(exception);
			}
			if (description != null) {
				return description;
			}
		}
		if (log != null) {
			List<String> lines = log.lines().toList();
			for (int i = lines.size() - 1; i >= 0; i--) {
				String line = lines.get(i);
				if (line.matches("^[\\w.$]+(Exception|Error)(: .*)?$")) {
					return shorten(line);
				}
			}
		}
		return null;
	}

	private static String shorten(String text) {
		return text.length() > 220 ? text.substring(0, 220) + "..." : text;
	}

	private static Map<String, String> system(Instance instance, int exitCode) {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("Velo Launcher", AppVersion.VERSION);
		map.put("Minecraft", instance.mcVersion());
		map.put("Profile", instance.name());
		map.put("Exit code", String.valueOf(exitCode));
		map.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
		map.put("Java (launcher)", System.getProperty("java.version") + " " + System.getProperty("java.vendor", ""));
		map.put("CPU cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
		if (instance.ramMaxMb() != null) {
			map.put("Game RAM", instance.ramMaxMb() + " MB");
		}
		try {
			var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			map.put("System RAM", os.getTotalMemorySize() / (1024 * 1024) + " MB (" + os.getFreeMemorySize() / (1024 * 1024) + " MB free)");
		} catch (Throwable ignored) {
			// Not a HotSpot JVM.
		}
		for (String mod : mods(instance)) {
			if (mod.toLowerCase(Locale.ROOT).startsWith("velo-client-")) {
				map.put("Velo Client", mod.replaceFirst("(?i)^velo-client-", "").replaceFirst("\\.jar$", ""));
			}
		}
		return map;
	}

	private static List<String> mods(Instance instance) {
		Path dir = InstancePaths.modsDir(instance.id());
		try (var files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static String read(Path file, int maxChars) {
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			return text.length() > maxChars ? text.substring(0, maxChars) : text;
		} catch (IOException e) {
			return null;
		}
	}

	private static String tail(Path file, int lines) {
		try {
			List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
			return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
		} catch (IOException e) {
			try {
				// Not valid UTF-8 everywhere (some mods log raw bytes) - read leniently.
				String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
				List<String> all = text.lines().toList();
				return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
			} catch (IOException ignored) {
				return null;
			}
		}
	}

	private static void openFolder(Path folder) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] command = os.contains("win") ? new String[] {"explorer.exe", folder.toString()}
				: os.contains("mac") ? new String[] {"open", folder.toString()} : new String[] {"xdg-open", folder.toString()};
		try {
			new ProcessBuilder(command).start();
		} catch (IOException ignored) {
			// Best-effort.
		}
	}
}
