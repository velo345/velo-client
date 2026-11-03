package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.social.NewsApi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * "Report a bug" from anywhere in the launcher (the bug icon in the top bar, Ctrl+Shift+B, or the search): what went
 * wrong, where, and - unless unticked - the launcher log, so the team can see what happened. Lands in the same inbox
 * as crash reports.
 */
public final class BugReportDialog {

	private static final String[][] AREAS = {{"launcher", "Launcher"}, {"game", "In-game"}, {"cosmetics", "Cosmetics"},
			{"store", "Store & coins"}, {"mods", "Mods & profiles"}, {"other", "Something else"}};

	private BugReportDialog() {
	}

	public static void show(Window owner) {
		show(owner, null, null, null);
	}

	public static void show(Window owner, String presetTitle, JsonObject context) {
		show(owner, presetTitle, context, null);
	}

	/** {@code context} / {@code doctor} (nullable) go with the report - which page was open, what the Doctor found and tried. */
	public static void show(Window owner, String presetTitle, JsonObject context, JsonObject doctor) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Report a bug");
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

		Label heading = new Label("Report a bug");
		heading.getStyleClass().add("error-dialog-title");
		Label sub = new Label("Tell us what went wrong - it goes straight to the Velo team.");
		sub.getStyleClass().add("t-muted");

		ToggleGroup areaGroup = new ToggleGroup();
		FlowPane areas = new FlowPane(6, 6);
		for (String[] area : AREAS) {
			ToggleButton chip = new ToggleButton(area[1]);
			chip.setUserData(area[0]);
			chip.setToggleGroup(areaGroup);
			chip.getStyleClass().setAll("button", "fchip");
			chip.selectedProperty().addListener((o, was, now) -> {
				chip.getStyleClass().remove("fchip-active");
				if (now) {
					chip.getStyleClass().add("fchip-active");
				}
			});
			areas.getChildren().add(chip);
		}
		areaGroup.getToggles().get(0).setSelected(true);

		TextField title = new TextField(presetTitle == null ? "" : presetTitle);
		title.setPromptText("In one line: what's broken?");
		TextArea message = new TextArea();
		message.setPromptText("What did you do, what happened, what did you expect? Steps to make it happen again help a lot.");
		message.setWrapText(true);
		message.setPrefRowCount(5);
		CheckBox includeLog = new CheckBox("Include the launcher log and system info (recommended)");
		includeLog.setSelected(true);

		Label status = new Label();
		status.getStyleClass().add("settings-row-hint");
		status.setWrapText(true);
		Button send = new Button("Send report");
		send.getStyleClass().add("primary-button");
		send.disableProperty().bind(title.textProperty().isEmpty().and(message.textProperty().isEmpty()));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(8, status, spacer, send);
		actions.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(status, Priority.ALWAYS);

		send.setOnAction(e -> {
			JsonObject report = new JsonObject();
			report.addProperty("kind", "bug");
			report.addProperty("source", "launcher");
			String area = (String) areaGroup.getSelectedToggle().getUserData();
			String t = title.getText().strip();
			report.addProperty("title", t.isEmpty() ? firstLine(message.getText()) : t);
			report.addProperty("message", message.getText().strip());
			report.addProperty("area", area);
			if (includeLog.isSelected()) {
				JsonObject sys = new JsonObject();
				sys.addProperty("Velo Launcher", AppVersion.VERSION + (AppVersion.DEV ? " (dev)" : ""));
				sys.addProperty("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
				sys.addProperty("Java (launcher)", System.getProperty("java.version"));
				report.add("system", sys);
				StringBuilder log = new StringBuilder(orEmpty(tail(VeloPaths.logs().resolve("launcher.log"), 600, 90_000)));
				// The run before this one too: people often report after restarting the launcher.
				String previousRun = tail(VeloPaths.logs().resolve("launcher.log.1"), 300, 45_000);
				if (previousRun != null && !previousRun.isBlank()) {
					log.append("\n\n==== Previous launcher run (launcher.log.1) ====\n").append(previousRun);
				}
				// "The game doesn't start" reports used to carry only the launcher log: add the game side of the profile
				// that ran last - its log, its newest crash report and its mods.
				lastGame(report, sys, log);
				report.addProperty("log", log.toString());
			}
			if (context != null) {
				report.add("context", context);
			}
			if (doctor != null) {
				report.add("doctor", doctor);
				report.addProperty("area", "mods");
			}
			send.disableProperty().unbind();
			send.setDisable(true);
			status.setText("Sending...");
			NewsView.run(() -> NewsApi.sendReport(report), id -> {
				status.setText("Thanks! Report #" + id + " reached the Velo team.");
				send.setText("Sent");
			}, error -> {
				send.setDisable(false);
				status.setText("Couldn't send it: " + error);
			});
		});

		VBox root = new VBox(12, heading, sub, areas, title, message, includeLog, actions);
		root.setPadding(new Insets(6));
		root.setPrefWidth(520);
		dialog.getDialogPane().setContent(root);
		dialog.show();
		title.requestFocus();
	}

	private static String firstLine(String text) {
		String line = text.strip().lines().findFirst().orElse("Bug report");
		return line.length() > 80 ? line.substring(0, 80) + "..." : line;
	}

	private static void lastGame(JsonObject report, JsonObject sys, StringBuilder log) {
		try {
			net.veloclient.launcher.instance.Instance newest = null;
			long newestAt = 0;
			for (var instance : net.veloclient.launcher.instance.InstanceStore.loadAll()) {
				Path latest = net.veloclient.launcher.instance.InstancePaths.gameDir(instance.id()).resolve("logs").resolve("latest.log");
				if (java.nio.file.Files.exists(latest)) {
					long at = java.nio.file.Files.getLastModifiedTime(latest).toMillis();
					if (at > newestAt) {
						newestAt = at;
						newest = instance;
					}
				}
			}
			if (newest == null) {
				return;
			}
			Path dir = net.veloclient.launcher.instance.InstancePaths.gameDir(newest.id());
			sys.addProperty("Profile", newest.name());
			sys.addProperty("Minecraft", newest.mcVersion());
			log.append("\n\n==== Game log of \"").append(newest.name()).append("\" (latest.log) ====\n");
			log.append(orEmpty(tail(dir.resolve("logs").resolve("latest.log"), 1500, 220_000)));
			// The start before (Minecraft keeps it as logs/<date>-<n>.log.gz): the broken start is often not the last one.
			Path previous = newest(dir.resolve("logs"), ".log.gz", 1, 7).stream().findFirst().orElse(null);
			if (previous != null) {
				log.append("\n\n==== Previous game log (").append(previous.getFileName()).append(") ====\n");
				log.append(orEmpty(tail(previous, 600, 90_000)));
			}
			// The two newest crash reports of the last week, from the top - that's where the exception is (the end is
			// only the mod list). Vanilla never writes them into latest.log.
			for (Path crash : newest(dir.resolve("crash-reports"), ".txt", 2, 7)) {
				log.append("\n\n==== Crash report (").append(crash.getFileName()).append(") ====\n");
				log.append(orEmpty(head(crash, 300, 60_000)));
			}
			Path mods = dir.resolve("mods");
			if (java.nio.file.Files.isDirectory(mods)) {
				com.google.gson.JsonArray list = new com.google.gson.JsonArray();
				try (var files = java.nio.file.Files.list(mods)) {
					files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".jar") || n.endsWith(".disabled")).sorted().forEach(list::add);
				}
				report.add("mods", list);
			}
		} catch (Exception e) {
			log.append("\n(couldn't attach the game log: ").append(e).append(")");
		}
	}

	/** The newest {@code count} files ending in {@code suffix} changed within {@code days}, newest first. */
	static List<Path> newest(Path dir, String suffix, int count, int days) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		long since = System.currentTimeMillis() - days * 24L * 3600_000L;
		try (var files = Files.list(dir)) {
			return files.filter(f -> f.getFileName().toString().endsWith(suffix) && f.toFile().lastModified() >= since)
					.sorted(java.util.Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
					.limit(count).toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	/**
	 * All lines of a log, .gz or not, never failing on its encoding: Windows writes logs in the system code page
	 * (Cp1252 on French/German PCs), where strict UTF-8 reading threw and the log went missing from the report.
	 */
	private static List<String> lines(Path file) throws java.io.IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (file.getFileName().toString().endsWith(".gz")) {
			try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
				bytes = in.readAllBytes();
			}
		}
		var decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
				.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
		return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().lines().toList();
	}

	private static String tail(Path file, int lines, int maxChars) {
		try {
			if (!Files.exists(file)) {
				return null;
			}
			List<String> all = lines(file);
			String text = String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
			return text.length() > maxChars ? text.substring(text.length() - maxChars) : text;
		} catch (Exception e) {
			return "(couldn't read " + file.getFileName() + ": " + e + ")";
		}
	}

	private static String head(Path file, int lines, int maxChars) {
		try {
			List<String> all = lines(file);
			String text = String.join("\n", all.subList(0, Math.min(lines, all.size())));
			return text.length() > maxChars ? text.substring(0, maxChars) : text;
		} catch (Exception e) {
			return "(couldn't read " + file.getFileName() + ": " + e + ")";
		}
	}

	private static String orEmpty(String text) {
		return text == null ? "" : text;
	}
}
