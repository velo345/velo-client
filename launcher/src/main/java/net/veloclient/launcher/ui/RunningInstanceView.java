package net.veloclient.launcher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.RunningInstanceManager.RunningInstance;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A running game's page (2026-11-26 glass redesign; it used to be a raw text box): a header card with a live status
 * and uptime, the server, the version and warning / error counts, a Stop button - then Logs (a real log viewer: every
 * line coloured by level, search, All / Warnings / Errors, Follow, Copy, Open folder) and Mods (the active mods).
 */
public final class RunningInstanceView {

	private static final Pattern LINE = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)(?:\\.\\d+)?\\] \\[([^\\]]*?)/(\\w+)\\]:? ?(.*)$");
	/** The launcher's own log: "[03:22:51.417] [INFO] [launch] message" (the tag is optional). */
	private static final Pattern LAUNCHER_LINE = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)(?:\\.\\d+)?\\] \\[(INFO|WARN|WARNING|ERROR|FATAL|DEBUG|TRACE)\\] (?:\\[([^\\]]{1,24})\\] )?(.*)$");
	private static final int MAX_LINES = 20_000;

	/** One log line: time, thread and level when it starts an entry; follow-up lines (stack traces) take the entry's level. */
	private record Line(String time, String thread, String level, String message) {
		boolean warn() {
			return level.equals("WARN");
		}

		boolean error() {
			return level.equals("ERROR") || level.equals("FATAL");
		}
	}

	private RunningInstanceView() {
	}

	public static Node build(RunningInstance running, LauncherTheme theme, Runnable onBack, Runnable onStopped) {
		VBox root = new VBox(14);
		root.getStyleClass().add("run-page");

		ObservableList<Line> lines = FXCollections.observableArrayList();
		int[] counts = new int[2];   // warnings, errors

		// ---- header card ----
		VBox head = new VBox(14);
		head.getStyleClass().addAll("card", "run-head");
		HBox top = new HBox(14);
		top.setAlignment(Pos.CENTER_LEFT);
		Button back = BackButton.create(null, null);
		back.setOnAction(e -> onBack.run());
		VBox titles = new VBox(4);
		Label title = new Label(running.instance().name());
		title.getStyleClass().add("run-title");
		HBox status = new HBox(8);
		status.setAlignment(Pos.CENTER_LEFT);
		Region dot = new Region();
		dot.getStyleClass().add("run-dot");
		Label state = new Label("Running");
		state.getStyleClass().add("run-state");
		String startedAt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(running.startedAtEpochMillis()));
		Label uptime = new Label();
		uptime.getStyleClass().add("run-sub");
		status.getChildren().addAll(dot, state, uptime);
		titles.getChildren().addAll(title, status);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button stop = new Button("Stop game");
		stop.setGraphic(Icons.icon("stop", 13, Color.web("#ff6b6b")));
		stop.getStyleClass().add("run-stop");
		stop.setOnAction(e -> {
			running.stop();
			stop.setDisable(true);
			stop.setText("Stopping...");
			state.setText("Stopping");
			dot.getStyleClass().add("run-dot-stopping");
			onStopped.run();
		});
		top.getChildren().addAll(back, titles, spacer, stop);

		HBox stats = new HBox(10);
		String target = running.serverAddress() != null ? running.serverAddress() : "Singleplayer";
		Label warnValue = new Label("0");
		Label errorValue = new Label("0");
		stats.getChildren().addAll(
				stat("server", running.serverAddress() != null ? "SERVER" : "PLAYING", new Label(target), null),
				stat("layers", "MINECRAFT", new Label(running.instance().mcVersion() + " · Fabric"), null),
				stat("clock", "STARTED", new Label(startedAt), null),
				stat("warning", "WARNINGS", warnValue, "run-stat-warn"),
				stat("warning", "ERRORS", errorValue, "run-stat-error"));
		head.getChildren().addAll(top, stats);

		Runnable tick = () -> {
			long secs = Math.max(0, (System.currentTimeMillis() - running.startedAtEpochMillis()) / 1000);
			uptime.setText("for " + (secs >= 3600 ? secs / 3600 + " h " + (secs % 3600) / 60 + " min" : secs >= 60 ? secs / 60 + " min " + secs % 60 + " s" : secs + " s"));
			if (!running.isAlive() && !state.getText().equals("Closed")) {
				state.setText("Closed");
				dot.getStyleClass().add("run-dot-off");
				stop.setDisable(true);
				stop.setText("Closed");
			}
			warnValue.setText(String.valueOf(counts[0]));
			errorValue.setText(String.valueOf(counts[1]));
		};
		tick.run();

		// ---- segmented tabs ----
		HBox seg = new HBox(4);
		seg.getStyleClass().add("run-seg");
		seg.setMaxWidth(Region.USE_PREF_SIZE);
		Button logsTab = new Button("Logs");
		Button modsTab = new Button("Mods");
		for (Button b : List.of(logsTab, modsTab)) {
			b.getStyleClass().addAll("segmented-button", "run-seg-button");
		}
		seg.getChildren().addAll(logsTab, modsTab);

		StackPane body = new StackPane();
		VBox.setVgrow(body, Priority.ALWAYS);
		Node logs = logViewer(running::logFile, lines, counts);
		Node[] mods = {null};
		Runnable showLogs = () -> {
			logsTab.getStyleClass().add("segmented-button-active");
			modsTab.getStyleClass().remove("segmented-button-active");
			body.getChildren().setAll(logs);
		};
		logsTab.setOnAction(e -> showLogs.run());
		modsTab.setOnAction(e -> {
			modsTab.getStyleClass().add("segmented-button-active");
			logsTab.getStyleClass().remove("segmented-button-active");
			if (mods[0] == null) {
				mods[0] = buildMods(running.instance());
			}
			body.getChildren().setAll(mods[0]);
			UiMotion.enter(mods[0]);
		});
		showLogs.run();

		root.getChildren().addAll(head, seg, body);

		Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick.run()));
		clock.setCycleCount(Timeline.INDEFINITE);
		clock.play();
		root.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				clock.stop();
			} else {
				clock.play();
			}
		});
		return root;
	}

	private static Node stat(String icon, String label, Label value, String extraClass) {
		VBox box = new VBox(3);
		box.getStyleClass().add("run-stat");
		if (extraClass != null) {
			box.getStyleClass().add(extraClass);
		}
		HBox cap = new HBox(6);
		cap.setAlignment(Pos.CENTER_LEFT);
		Label l = new Label(label);
		l.getStyleClass().add("run-stat-label");
		cap.getChildren().addAll(Icons.icon(icon, 11, Color.web("#8e8890")), l);
		value.getStyleClass().add("run-stat-value");
		value.setMaxWidth(260);
		box.getChildren().addAll(cap, value);
		HBox.setHgrow(box, Priority.SOMETIMES);
		return box;
	}

	// ---- Logs ----

	/** The log viewer on its own (the Tester window's Logs page uses it too): {@code file} may change - it starts over then. */
	public static Node logViewer(java.util.function.Supplier<Path> file) {
		return logViewer(file, FXCollections.observableArrayList(), new int[2]);
	}

	private static Node logViewer(java.util.function.Supplier<Path> source, ObservableList<Line> lines, int[] counts) {
		VBox card = new VBox(10);
		card.getStyleClass().addAll("card", "log-card");

		HBox bar = new HBox(8);
		bar.setAlignment(Pos.CENTER_LEFT);
		TextField search = new TextField();
		search.setPromptText("Search the log");
		search.getStyleClass().add("log-search");
		HBox.setHgrow(search, Priority.ALWAYS);
		search.setMaxWidth(360);
		ToggleButton all = chip("All");
		ToggleButton warns = chip("Warnings");
		ToggleButton errors = chip("Errors");
		javafx.scene.control.ToggleGroup level = new javafx.scene.control.ToggleGroup();
		for (ToggleButton b : List.of(all, warns, errors)) {
			b.setToggleGroup(level);
		}
		all.setSelected(true);
		level.selectedToggleProperty().addListener((o, a, n) -> {
			if (n == null) {
				a.setSelected(true);   // one is always picked
			}
		});
		ToggleButton follow = chip("Follow");
		follow.setSelected(true);
		Region gap = new Region();
		HBox.setHgrow(gap, Priority.ALWAYS);
		Button copy = new Button("Copy");
		copy.setGraphic(Icons.icon("copy", 13, Color.web("#d8d4d6")));
		copy.getStyleClass().add("log-action");
		Button folder = new Button("Open folder");
		folder.setGraphic(Icons.icon("folder", 13, Color.web("#d8d4d6")));
		folder.getStyleClass().add("log-action");
		bar.getChildren().addAll(search, all, warns, errors, follow, gap, copy, folder);

		FilteredList<Line> shown = new FilteredList<>(lines, l -> true);
		ListView<Line> list = new ListView<>(shown);
		list.getStyleClass().add("log-list");
		list.setCellFactory(v -> new LogCell());
		list.setFixedCellSize(20);
		list.setFocusTraversable(false);
		VBox.setVgrow(list, Priority.ALWAYS);
		Label empty = new Label("Waiting for the game's first lines...");
		empty.getStyleClass().add("log-empty");
		list.setPlaceholder(empty);

		Runnable refilter = () -> {
			String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
			boolean w = warns.isSelected();
			boolean er = errors.isSelected();
			shown.setPredicate(l -> (!w || l.warn() || l.error()) && (!er || l.error())
					&& (q.isEmpty() || l.message().toLowerCase(Locale.ROOT).contains(q) || l.thread().toLowerCase(Locale.ROOT).contains(q)));
			if (follow.isSelected() && !shown.isEmpty()) {
				list.scrollTo(shown.size() - 1);
			}
		};
		search.textProperty().addListener((o, a, b) -> refilter.run());
		level.selectedToggleProperty().addListener((o, a, b) -> refilter.run());
		follow.selectedProperty().addListener((o, a, on) -> {
			if (on && !shown.isEmpty()) {
				list.scrollTo(shown.size() - 1);
			}
		});

		copy.setOnAction(e -> {
			StringBuilder sb = new StringBuilder();
			for (Line l : shown) {
				sb.append(l.time().isEmpty() ? "" : "[" + l.time() + "] [" + l.thread() + "/" + l.level() + "]: ").append(l.message()).append('\n');
			}
			ClipboardContent content = new ClipboardContent();
			content.putString(sb.toString());
			javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
			Toasts.show(copy, "check", "#5ad17a", shown.size() == lines.size() ? "Log copied" : shown.size() + " lines copied");
		});
		folder.setOnAction(e -> {
			Path f = source.get();
			if (f != null && f.getParent() != null) {
				openFolder(f.getParent());
			}
		});

		// ---- tailing: only what's new since the last look, read off the FX thread ----
		long[] offset = {0};
		String[] carry = {""};
		Path[] reading0 = {null};
		String[] lastLevel = {"INFO"};
		boolean[] reading = {false};
		Runnable poll = () -> {
			if (reading[0]) {
				return;
			}
			reading[0] = true;
			Thread.ofVirtual().start(() -> {
				List<Line> fresh = new ArrayList<>();
				boolean[] restart = {false};
				try {
					Path file = source.get();
					if (!java.util.Objects.equals(file, reading0[0])) {
						// another file (Tester window tabs): start over
						reading0[0] = file;
						offset[0] = 0;
						carry[0] = "";
						restart[0] = true;
					}
					if (file != null && Files.exists(file)) {
						long size = Files.size(file);
						if (size < offset[0]) {
							offset[0] = 0;   // the file started over
						}
						if (size > offset[0]) {
							try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
								long from = Math.max(offset[0], size - 4_000_000L);   // a huge log: the last 4 MB
								raf.seek(from);
								byte[] buf = new byte[(int) (size - from)];
								raf.readFully(buf);
								offset[0] = size;
								String text = carry[0] + new String(buf, StandardCharsets.UTF_8);
								int end = text.lastIndexOf('\n');
								carry[0] = end < 0 ? text : text.substring(end + 1);
								if (end >= 0) {
									for (String raw : text.substring(0, end).split("\r?\n")) {
										fresh.add(parse(raw, lastLevel));
									}
								}
							}
						}
					}
				} catch (IOException ignored) {
					// not there yet right after launch - next tick
				}
				Platform.runLater(() -> {
					reading[0] = false;
					if (restart[0]) {
						lines.clear();
						counts[0] = 0;
						counts[1] = 0;
					}
					if (fresh.isEmpty()) {
						return;
					}
					for (Line l : fresh) {
						if (!l.time().isEmpty() || !l.thread().isEmpty()) {
							if (l.warn()) {
								counts[0]++;
							} else if (l.error()) {
								counts[1]++;
							}
						}
					}
					lines.addAll(fresh);
					if (lines.size() > MAX_LINES) {
						lines.remove(0, lines.size() - MAX_LINES);
					}
					if (follow.isSelected() && !shown.isEmpty()) {
						list.scrollTo(shown.size() - 1);
					}
				});
			});
		};
		poll.run();
		Timeline timeline = new Timeline(new KeyFrame(Duration.millis(700), e -> poll.run()));
		timeline.setCycleCount(Timeline.INDEFINITE);
		timeline.play();
		card.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				timeline.stop();
			} else {
				timeline.play();
			}
		});
		// scrolling up yourself stops following; back at the bottom it follows again
		list.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
			if (e.getDeltaY() > 0) {
				follow.setSelected(false);
			}
		});

		card.getChildren().addAll(bar, list);
		return card;
	}

	private static Line parse(String raw, String[] lastLevel) {
		Matcher m = LINE.matcher(raw);
		if (m.matches()) {
			String lvl = m.group(3).toUpperCase(Locale.ROOT);
			lastLevel[0] = lvl;
			return new Line(m.group(1), m.group(2), lvl, m.group(4));
		}
		Matcher l = LAUNCHER_LINE.matcher(raw);
		if (l.matches()) {
			String lvl = l.group(2).equals("WARNING") ? "WARN" : l.group(2);
			lastLevel[0] = lvl;
			return new Line(l.group(1), l.group(3) == null ? "launcher" : l.group(3), lvl, l.group(4));
		}
		// Java's own logging (JavaFX CSS warnings and such), also in German: "WARNUNG: ..." / "SCHWERWIEGEND: ..."
		int colon = raw.indexOf(':');
		if (colon > 3 && colon < 16) {
			String head = raw.substring(0, colon);
			String lvl = switch (head) {
				case "WARNING", "WARNUNG" -> "WARN";
				case "SEVERE", "SCHWERWIEGEND" -> "ERROR";
				default -> null;
			};
			if (lvl != null) {
				lastLevel[0] = lvl;
				return new Line("", "java", lvl, raw.substring(colon + 1).trim());
			}
		}
		return new Line("", "", lastLevel[0], raw);
	}

	private static ToggleButton chip(String text) {
		ToggleButton b = new ToggleButton(text);
		b.getStyleClass().add("log-chip");
		return b;
	}

	/** A log line in fixed columns: time and thread quiet, the level as a coloured tag, the message in mono. */
	private static final class LogCell extends ListCell<Line> {
		private final Label time = new Label();
		private final Label thread = new Label();
		private final Label level = new Label();
		private final Label message = new Label();
		private final HBox row = new HBox(10, time, thread, level, message);

		LogCell() {
			getStyleClass().add("log-cell");
			time.getStyleClass().add("log-time");
			thread.getStyleClass().add("log-thread");
			time.setMinWidth(58);
			time.setPrefWidth(58);
			thread.setMinWidth(104);
			thread.setPrefWidth(104);
			thread.setMaxWidth(104);
			thread.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
			level.setMinWidth(Region.USE_PREF_SIZE);
			message.setMinWidth(0);
			row.setAlignment(Pos.CENTER_LEFT);
			setPrefWidth(0);
		}

		@Override
		protected void updateItem(Line l, boolean empty) {
			super.updateItem(l, empty);
			getStyleClass().removeAll("log-cell-warn", "log-cell-error");
			if (empty || l == null) {
				setGraphic(null);
				return;
			}
			boolean head = !l.time().isEmpty() || !l.thread().isEmpty();   // an entry's first line (not a stack trace line)
			time.setText(head ? l.time() : "");
			thread.setText(head ? l.thread() : "");
			boolean tag = head && !l.level().equals("INFO");
			level.setText(tag ? l.level() : "");
			level.setVisible(tag);
			level.setManaged(tag);
			level.getStyleClass().setAll("label", "log-level", "log-level-" + l.level().toLowerCase(Locale.ROOT));
			message.setText(l.message());
			message.getStyleClass().setAll("label", "log-msg", l.error() ? "log-msg-error" : l.warn() ? "log-msg-warn" : "log-msg-info");
			if (head && l.error()) {
				getStyleClass().add("log-cell-error");
			} else if (head && l.warn()) {
				getStyleClass().add("log-cell-warn");
			}
			setGraphic(row);
		}
	}

	// ---- Mods ----

	private static Node buildMods(Instance instance) {
		VBox list = new VBox(6);
		list.setPadding(new Insets(2));
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
		List<InstalledAsset> active = mods.stream().filter(m -> enabled.contains(m.filename()))
				.sorted(java.util.Comparator.comparing(m -> m.title().toLowerCase(Locale.ROOT))).toList();
		Label head = new Label(active.size() == 1 ? "1 mod active" : active.size() + " mods active");
		head.getStyleClass().add("run-mods-head");
		list.getChildren().add(head);
		if (active.isEmpty()) {
			Label empty = new Label("No mods from the browser are active in this profile.");
			empty.getStyleClass().add("log-empty");
			list.getChildren().add(empty);
		}
		for (InstalledAsset mod : active) {
			HBox row = new HBox(12);
			row.getStyleClass().add("run-mod-row");
			row.setAlignment(Pos.CENTER_LEFT);
			VBox text = new VBox(2);
			Label name = new Label(mod.title());
			name.getStyleClass().add("run-mod-name");
			Label desc = new Label(mod.description() == null ? "" : mod.description());
			desc.getStyleClass().add("run-mod-desc");
			desc.setMaxWidth(700);
			text.getChildren().addAll(name, desc);
			HBox.setHgrow(text, Priority.ALWAYS);
			Label version = new Label(mod.versionNumber());
			version.getStyleClass().add("run-mod-version");
			row.getChildren().addAll(text, version);
			list.getChildren().add(row);
		}
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().addAll("card", "log-card", "run-mods");
		return scroll;
	}

	private static void openFolder(Path folder) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.contains("win")) {
				new ProcessBuilder("explorer", folder.toString()).start();
			} else if (os.contains("mac")) {
				new ProcessBuilder("open", folder.toString()).start();
			} else {
				new ProcessBuilder("xdg-open", folder.toString()).start();
			}
		} catch (Exception e) {
			LauncherLog.warn("Couldn't open " + folder, e);
		}
	}
}
