package net.veloclient.launcher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.RunningInstanceManager.RunningInstance;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The workspace shown when clicking a running instance in the sidebar's
 * "Running" section: top tabs for a live-tailed log and a read-only summary
 * of which mods are actually active for this run, plus a Stop button - the
 * in-launcher equivalent of "this game's task manager entry".
 */
public final class RunningInstanceView {

	private RunningInstanceView() {
	}

	public static Node build(RunningInstance running, LauncherTheme theme, Runnable onBack, Runnable onStopped) {
		VBox root = new VBox(14);

		HBox header = new HBox(12);
		header.setAlignment(Pos.CENTER_LEFT);
		Button back = new Button("< Back");
		back.setOnAction(e -> onBack.run());
		Label title = new Label(running.instance().name());
		title.setFont(Font.font("System", FontWeight.BOLD, 20));
		title.setTextFill(text(theme));
		String startedAt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(running.startedAtEpochMillis()));
		String target = running.serverAddress() != null ? running.serverAddress() : "Singleplayer";
		Label started = new Label("Running since " + startedAt + "  ·  " + target);
		started.getStyleClass().add("version-tag");
		started.setTextFill(text(theme));
		HBox spacer = new HBox();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button stop = new Button("Stop");
		stop.getStyleClass().add("title-menu-button");
		stop.setOnAction(e -> {
			running.stop();
			stop.setDisable(true);
			stop.setText("Stopping...");
			onStopped.run();
		});
		header.getChildren().addAll(back, title, started, spacer, stop);
		root.getChildren().add(header);

		TabPane tabs = new TabPane();
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getTabs().add(new Tab("Logs", buildLogsTab(running, theme)));
		tabs.getTabs().add(new Tab("Mods", buildModsTab(running.instance(), theme)));
		VBox.setVgrow(tabs, Priority.ALWAYS);
		root.getChildren().add(tabs);

		return root;
	}

	// ---- Logs tab: live-tails the run's own launcher log file ----

	private static Node buildLogsTab(RunningInstance running, LauncherTheme theme) {
		VBox box = new VBox(8);
		box.setPadding(new Insets(10, 0, 0, 0));

		TextArea logArea = new TextArea();
		logArea.setEditable(false);
		logArea.setWrapText(false);
		logArea.getStyleClass().add("error-details-area");
		VBox.setVgrow(logArea, Priority.ALWAYS);

		long[] lastSize = {0};
		Runnable poll = () -> {
			try {
				if (!Files.exists(running.logFile())) {
					return;
				}
				long size = Files.size(running.logFile());
				if (size == lastSize[0]) {
					return;
				}
				lastSize[0] = size;
				String content = Files.readString(running.logFile(), StandardCharsets.UTF_8);
				logArea.setText(content);
				logArea.positionCaret(content.length());
			} catch (IOException ignored) {
				// The file may not exist yet right after launch - just try again next tick.
			}
		};
		poll.run();

		Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> poll.run()));
		timeline.setCycleCount(Timeline.INDEFINITE);
		timeline.play();
		// Stops polling once this tab's node leaves the scene (navigated away from), so a
		// forgotten timer doesn't keep tailing a closed instance's log forever in the background.
		logArea.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				timeline.stop();
			}
		});

		box.getChildren().add(logArea);
		return box;
	}

	// ---- Mods tab: read-only summary of what's actually enabled for this instance right now ----

	private static Node buildModsTab(Instance instance, LauncherTheme theme) {
		VBox list = new VBox(8);
		list.setPadding(new Insets(10, 0, 0, 0));

		List<InstalledAsset> mods = InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD);
		java.util.Set<String> enabledFilenames;
		try (var stream = Files.list(InstancePaths.modsDir(instance.id()))) {
			enabledFilenames = stream.map(p -> p.getFileName().toString())
					.filter(name -> !name.endsWith(".disabled"))
					.collect(java.util.stream.Collectors.toSet());
		} catch (IOException e) {
			enabledFilenames = java.util.Set.of();
		}
		final java.util.Set<String> enabled = enabledFilenames;

		List<InstalledAsset> active = mods.stream().filter(m -> enabled.contains(m.filename())).toList();
		if (active.isEmpty()) {
			Label empty = new Label("No identified mods are currently enabled for this profile.");
			empty.getStyleClass().add("section-subtitle");
			empty.setTextFill(text(theme));
			list.getChildren().add(empty);
		} else {
			for (InstalledAsset mod : active) {
				HBox row = new HBox(10);
				row.setAlignment(Pos.CENTER_LEFT);
				Label name = new Label(mod.title());
				name.setFont(Font.font("System", FontWeight.BOLD, 13));
				name.setTextFill(text(theme));
				Label version = new Label("v" + mod.versionNumber());
				version.getStyleClass().add("version-tag");
				version.setTextFill(text(theme));
				row.getChildren().addAll(name, version);
				list.getChildren().add(row);
			}
		}

		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
