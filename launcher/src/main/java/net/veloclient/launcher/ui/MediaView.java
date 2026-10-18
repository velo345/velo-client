package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.media.MjpegAvi;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Clips & Screenshots from every profile in one gallery: clips (saved in-game with the Clips
 * module's key, or automatically after a fight) play right here with scrubbing, screenshots open
 * full size. Export, copy, open folder and delete from the viewer.
 */
public final class MediaView {

	private record Item(Path file, boolean clip, long modified, Path thumbnail, JsonObject meta, Instance profile) {
	}

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
			.withZone(ZoneId.systemDefault());
	private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-media");
		thread.setDaemon(true);
		return thread;
	});

	private final Stage owner;
	private final StackPane root = new StackPane();
	private final FlowPane grid = new FlowPane(14, 14);
	private final ToggleButton clipsTab = new ToggleButton("Clips");
	private final ToggleButton screenshotsTab = new ToggleButton("Screenshots");
	private final ComboBox<String> profileFilter = new ComboBox<>();
	private final Label hint = new Label();
	private List<Item> items = List.of();
	private Node gallery;
	private AnimationTimer playback;

	private MediaView(Stage owner) {
		this.owner = owner;
		// Leaving the page (another sidebar entry) must not leave a clip playing in the background.
		root.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				stopPlayback();
			}
		});
	}

	public static Node build(Stage owner) {
		return new MediaView(owner).buildGallery();
	}

	private Node buildGallery() {
		Label title = new Label("Clips & Screenshots");
		title.getStyleClass().add("page-title");
		ToggleGroup group = new ToggleGroup();
		clipsTab.setToggleGroup(group);
		screenshotsTab.setToggleGroup(group);
		clipsTab.setSelected(true);
		clipsTab.getStyleClass().add("glass-button");
		screenshotsTab.getStyleClass().add("glass-button");
		group.selectedToggleProperty().addListener((o, a, b) -> {
			if (b == null) {
				a.setSelected(true);
			} else {
				render();
			}
		});
		profileFilter.getItems().add("All profiles");
		for (Instance instance : InstanceStore.loadAll()) {
			profileFilter.getItems().add(instance.name());
		}
		profileFilter.setValue("All profiles");
		profileFilter.valueProperty().addListener((o, a, b) -> render());
		Button refresh = new Button("Refresh");
		refresh.getStyleClass().add("glass-button");
		refresh.setOnAction(e -> load());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox top = new HBox(10, title, spacer, clipsTab, screenshotsTab, profileFilter, refresh);
		top.setAlignment(Pos.CENTER_LEFT);
		hint.getStyleClass().add("settings-row-hint");
		hint.setWrapText(true);

		grid.setPadding(new Insets(4, 4, 16, 4));
		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox box = new VBox(12, top, hint, scroll);
		gallery = box;
		root.getChildren().setAll(box);
		load();
		return root;
	}

	private void load() {
		hint.setText("Loading...");
		LOADER.execute(() -> {
			List<Item> found = new ArrayList<>();
			for (Instance instance : InstanceStore.loadAll()) {
				Path game = InstancePaths.gameDir(instance.id());
				found.addAll(scan(game.resolve("velo-clips"), true, instance));
				found.addAll(scan(game.resolve("screenshots"), false, instance));
			}
			found.sort(Comparator.comparingLong(Item::modified).reversed());
			Platform.runLater(() -> {
				items = found;
				render();
			});
		});
	}

	private static List<Item> scan(Path dir, boolean clip, Instance profile) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return clip ? n.endsWith(".avi") : n.endsWith(".png") || n.endsWith(".jpg");
			}).map(p -> {
				long modified = 0;
				try {
					modified = Files.getLastModifiedTime(p).toMillis();
				} catch (Exception ignored) {
					// Unknown.
				}
				Path thumb = p;
				JsonObject meta = null;
				if (clip) {
					String base = p.getFileName().toString().replaceFirst("\\.avi$", "");
					thumb = p.resolveSibling(base + ".jpg");
					try {
						Path json = p.resolveSibling(base + ".json");
						if (Files.exists(json)) {
							meta = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
						}
					} catch (Exception ignored) {
						// No sidecar.
					}
				}
				return new Item(p, clip, modified, thumb, meta, profile);
			}).toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	private List<Item> shown() {
		boolean clips = clipsTab.isSelected();
		String profile = profileFilter.getValue();
		return items.stream().filter(i -> i.clip() == clips)
				.filter(i -> profile == null || profile.equals("All profiles") || i.profile().name().equals(profile)).toList();
	}

	private void render() {
		List<Item> list = shown();
		grid.getChildren().clear();
		if (list.isEmpty()) {
			hint.setText(clipsTab.isSelected()
					? "No clips yet. Turn on the Clips module in-game (R-Shift > Quality of Life) and press F8 to save the last 30 seconds,"
							+ " or let Fight Recap save your won fights automatically."
					: "No screenshots yet - press F2 in-game.");
			return;
		}
		hint.setText(list.size() + (clipsTab.isSelected() ? " clips" : " screenshots") + " - click one to open it.");
		for (Item item : list) {
			grid.getChildren().add(tile(item));
		}
	}

	private Node tile(Item item) {
		ImageView thumb = new ImageView();
		thumb.setFitWidth(232);
		thumb.setFitHeight(130);
		thumb.setPreserveRatio(false);
		thumb.setSmooth(true);
		if (Files.exists(item.thumbnail())) {
			thumb.setImage(new Image(item.thumbnail().toUri().toString(), 464, 260, true, true, true));
		}
		StackPane frame = new StackPane(thumb);
		frame.setStyle("-fx-background-color: #0b0b0f; -fx-background-radius: 8;");
		frame.setMinSize(232, 130);
		frame.setMaxSize(232, 130);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(232, 130);
		clip.setArcWidth(16);
		clip.setArcHeight(16);
		frame.setClip(clip);
		if (item.clip()) {
			Label play = new Label("▶");
			play.setStyle("-fx-text-fill: white; -fx-font-size: 20; -fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 30;"
					+ " -fx-padding: 6 12 6 14;");
			Label length = new Label(item.meta() != null && item.meta().has("seconds")
					? Math.round(item.meta().get("seconds").getAsDouble()) + "s" : "clip");
			length.setStyle("-fx-text-fill: white; -fx-background-color: rgba(0,0,0,0.7); -fx-background-radius: 4; -fx-padding: 1 5;");
			StackPane.setAlignment(length, Pos.BOTTOM_RIGHT);
			StackPane.setMargin(length, new Insets(6));
			frame.getChildren().addAll(play, length);
		}
		Label name = new Label(label(item));
		name.getStyleClass().add("news-card-title");
		name.setMaxWidth(232);
		Label meta = new Label(WHEN.format(Instant.ofEpochMilli(item.modified())) + "  ·  " + item.profile().name());
		meta.getStyleClass().add("news-card-date");
		meta.setMaxWidth(232);
		VBox card = new VBox(6, frame, name, meta);
		card.getStyleClass().add("report-row");
		card.setPadding(new Insets(8));
		card.setOnMouseClicked(e -> openViewer(item));
		card.setStyle("-fx-cursor: hand;");
		return card;
	}

	private static String label(Item item) {
		if (item.clip() && item.meta() != null && item.meta().has("recap")) {
			JsonObject recap = item.meta().getAsJsonObject("recap");
			String outcome = str(recap, "outcome");
			return (outcome.equals("WON") ? "Won vs " : outcome.equals("LOST") ? "Lost vs " : "Fight vs ") + str(recap, "opponent");
		}
		if (item.clip() && item.meta() != null && item.meta().has("server")) {
			return "Clip on " + item.meta().get("server").getAsString();
		}
		String name = item.file().getFileName().toString();
		return name.substring(0, name.lastIndexOf('.'));
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	// ---- Viewer ----

	private void openViewer(Item item) {
		stopPlayback();
		List<Item> siblings = shown();
		Button back = BackButton.create(null, null);
		back.setOnAction(e -> closeViewer());
		Label title = new Label(label(item));
		title.getStyleClass().add("page-title");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button folder = new Button("Open folder");
		folder.getStyleClass().add("glass-button");
		folder.setOnAction(e -> openFolder(item.file().getParent()));
		Button export = new Button(item.clip() ? "Export clip" : "Save as...");
		export.getStyleClass().add("glass-button");
		export.setOnAction(e -> export(item));
		Button delete = new Button("Delete");
		delete.getStyleClass().add("danger-ghost-button");
		delete.setOnAction(e -> {
			if (Confirm.ask(owner, "Delete " + (item.clip() ? "clip" : "screenshot") + "?", "This can't be undone.")) {
				delete(item);
				closeViewer();
				load();
			}
		});
		HBox top = new HBox(10, back, title, spacer, folder, export);
		top.setAlignment(Pos.CENTER_LEFT);

		ImageView view = new ImageView();
		view.setPreserveRatio(true);
		view.setSmooth(true);
		StackPane stage = new StackPane(view);
		stage.setStyle("-fx-background-color: #0b0b0f; -fx-background-radius: 10;");
		stage.setMinSize(0, 0);
		view.fitWidthProperty().bind(stage.widthProperty().subtract(8));
		view.fitHeightProperty().bind(stage.heightProperty().subtract(8));
		VBox.setVgrow(stage, Priority.ALWAYS);
		HBox.setHgrow(stage, Priority.ALWAYS);

		HBox controls = new HBox(10);
		controls.setAlignment(Pos.CENTER_LEFT);
		Node body = stage;
		if (item.clip()) {
			Label loading = new Label("Loading clip...");
			loading.setStyle("-fx-text-fill: #9aa0b0;");
			stage.getChildren().add(loading);
			Button play = new Button("Pause");
			play.getStyleClass().add("primary-button");
			Slider seek = new Slider(0, 1, 0);
			HBox.setHgrow(seek, Priority.ALWAYS);
			Label time = new Label("0.0s");
			time.getStyleClass().add("settings-row-hint");
			controls.getChildren().addAll(play, seek, time, delete);
			LOADER.execute(() -> {
				try {
					MjpegAvi.Clip clip = MjpegAvi.read(item.file());
					Platform.runLater(() -> {
						stage.getChildren().remove(loading);
						startPlayback(clip, view, play, seek, time, stage);
					});
				} catch (Exception ex) {
					Platform.runLater(() -> loading.setText("Couldn't open this clip: " + ex.getMessage()));
				}
			});
			if (item.meta() != null && item.meta().has("recap")) {
				body = new HBox(14, stage, recapPanel(item.meta().getAsJsonObject("recap")));
				VBox.setVgrow(body, Priority.ALWAYS);
			}
		} else {
			view.setImage(new Image(item.file().toUri().toString(), true));
			Button previous = new Button("← Previous");
			previous.getStyleClass().add("glass-button");
			Button next = new Button("Next →");
			next.getStyleClass().add("glass-button");
			int index = siblings.indexOf(item);
			previous.setOnAction(e -> openViewer(siblings.get(Math.floorMod(index - 1, siblings.size()))));
			next.setOnAction(e -> openViewer(siblings.get(Math.floorMod(index + 1, siblings.size()))));
			Button copy = new Button("Copy image");
			copy.getStyleClass().add("glass-button");
			copy.setOnAction(e -> {
				ClipboardContent content = new ClipboardContent();
				content.putImage(new Image(item.file().toUri().toString()));
				Clipboard.getSystemClipboard().setContent(content);
				copy.setText("Copied");
			});
			Label counter = new Label((index + 1) + " / " + siblings.size() + "  ·  " + WHEN.format(Instant.ofEpochMilli(item.modified())));
			counter.getStyleClass().add("settings-row-hint");
			Region gap = new Region();
			HBox.setHgrow(gap, Priority.ALWAYS);
			controls.getChildren().addAll(previous, next, counter, gap, copy, delete);
		}
		VBox viewer = new VBox(12, top, body, controls);
		viewer.setFocusTraversable(true);
		viewer.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ESCAPE) {
				closeViewer();
			} else if (!item.clip() && (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT)) {
				int index = siblings.indexOf(item);
				openViewer(siblings.get(Math.floorMod(index + (e.getCode() == KeyCode.LEFT ? -1 : 1), siblings.size())));
			}
		});
		root.getChildren().setAll(viewer);
		viewer.requestFocus();
	}

	private Node recapPanel(JsonObject recap) {
		String outcome = str(recap, "outcome");
		Label headline = new Label(outcome.equals("WON") ? "Victory" : outcome.equals("LOST") ? "Defeat" : "Fight");
		headline.setStyle("-fx-font-size: 18; -fx-font-weight: bold; -fx-text-fill: "
				+ (outcome.equals("WON") ? "#6FE39A" : outcome.equals("LOST") ? "#FF5555" : "#FFC53D") + ";");
		Label opponent = new Label("vs " + str(recap, "opponent"));
		opponent.getStyleClass().add("settings-row-hint");
		VBox panel = new VBox(6, headline, opponent);
		panel.getStyleClass().add("settings-card");
		panel.setPrefWidth(220);
		panel.setMinWidth(220);
		int dealt = num(recap, "hitsDealt");
		int attacks = num(recap, "attacks");
		String[][] rows = {
				{"Duration", String.format(Locale.ROOT, "%.1fs", dbl(recap, "durationMillis") / 1000)},
				{"Hits dealt", String.valueOf(dealt)},
				{"Hits taken", String.valueOf(num(recap, "hitsTaken"))},
				{"Best combo", String.valueOf(num(recap, "bestCombo"))},
				{"Crits", String.valueOf(num(recap, "crits"))},
				{"Accuracy", attacks <= 0 ? "-" : Math.min(100, Math.round(dealt * 100f / attacks)) + "%"},
				{"Avg reach", dbl(recap, "averageReach") <= 0 ? "-" : String.format(Locale.ROOT, "%.2f", dbl(recap, "averageReach"))},
				{"Max reach", dbl(recap, "maxReach") <= 0 ? "-" : String.format(Locale.ROOT, "%.2f", dbl(recap, "maxReach"))},
				{"Damage dealt", String.format(Locale.ROOT, "%.1f ❤", dbl(recap, "damageDealt") / 2)},
				{"Damage taken", String.format(Locale.ROOT, "%.1f ❤", dbl(recap, "damageTaken") / 2)},
		};
		for (String[] row : rows) {
			Label key = new Label(row[0]);
			key.getStyleClass().add("settings-row-hint");
			Label value = new Label(row[1]);
			value.getStyleClass().add("settings-row-label");
			Region gap = new Region();
			HBox.setHgrow(gap, Priority.ALWAYS);
			panel.getChildren().add(new HBox(8, key, gap, value));
		}
		return panel;
	}

	private static int num(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsInt() : 0;
	}

	private static double dbl(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsDouble() : 0;
	}

	private void startPlayback(MjpegAvi.Clip clip, ImageView view, Button play, Slider seek, Label time, StackPane stage) {
		if (clip.frames().isEmpty()) {
			return;
		}
		int last = clip.frames().size() - 1;
		double[] position = {0};
		boolean[] playing = {true};
		boolean[] dragging = {false};
		int[] shown = {-1};
		play.setOnAction(e -> {
			if (!playing[0] && position[0] >= last) {
				position[0] = 0;
			}
			playing[0] = !playing[0];
			play.setText(playing[0] ? "Pause" : "Play");
		});
		stage.setOnMouseClicked(e -> play.fire());
		seek.valueChangingProperty().addListener((o, a, b) -> dragging[0] = b);
		seek.valueProperty().addListener((o, a, b) -> {
			if (dragging[0] || seek.isPressed()) {
				position[0] = b.doubleValue() * last;
			}
		});
		playback = new AnimationTimer() {
			private long lastNanos;

			@Override
			public void handle(long now) {
				if (lastNanos != 0 && playing[0] && !dragging[0]) {
					position[0] += (now - lastNanos) / 1e9 * clip.fps();
					if (position[0] >= last) {
						position[0] = last;
						playing[0] = false;
						play.setText("Play");
					}
				}
				lastNanos = now;
				int index = (int) Math.round(position[0]);
				if (index != shown[0]) {
					shown[0] = index;
					view.setImage(new Image(new ByteArrayInputStream(clip.frames().get(index))));
				}
				if (!dragging[0] && !seek.isPressed()) {
					seek.setValue(last == 0 ? 0 : position[0] / last);
				}
				time.setText(String.format(Locale.ROOT, "%.1f / %.1fs", position[0] / clip.fps(), clip.seconds()));
			}
		};
		playback.start();
	}

	private void stopPlayback() {
		if (playback != null) {
			playback.stop();
			playback = null;
		}
	}

	private void closeViewer() {
		stopPlayback();
		root.getChildren().setAll(gallery);
	}

	private void export(Item item) {
		FileChooser chooser = new FileChooser();
		chooser.setInitialFileName(item.file().getFileName().toString());
		chooser.setInitialDirectory(Path.of(System.getProperty("user.home")).toFile());
		var target = chooser.showSaveDialog(owner);
		if (target == null) {
			return;
		}
		try {
			Files.copy(item.file(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			LauncherLog.warn("Export failed", e);
			ErrorDialog.show(owner, "Export failed", e.getMessage(), null, null);
		}
	}

	private static void delete(Item item) {
		try {
			Files.deleteIfExists(item.file());
			if (item.clip()) {
				String base = item.file().getFileName().toString().replaceFirst("\\.avi$", "");
				Files.deleteIfExists(item.file().resolveSibling(base + ".jpg"));
				Files.deleteIfExists(item.file().resolveSibling(base + ".json"));
			}
		} catch (Exception e) {
			LauncherLog.warn("Couldn't delete " + item.file(), e);
		}
	}

	private static void openFolder(Path folder) {
		try {
			Files.createDirectories(folder);
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
