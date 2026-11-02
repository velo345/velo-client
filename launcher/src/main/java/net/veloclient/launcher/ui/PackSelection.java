package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Select several mods / packs at once and enable, disable or delete them together. "Select" in the toolbar turns
 * check boxes on (they pop in row by row), clicking a row ticks it, shift-click ticks a range; a bar slides up from
 * the bottom with the actions. Deleted files go to the profile's backups folder, so nothing is lost for good.
 */
final class PackSelection {

	private static final String DISABLED = ".disabled";

	private final Stage owner;
	private final Instance instance;
	private final InstalledAssetStore.Kind kind;
	private final Path folder;
	private final String noun;
	private Runnable refresh = () -> { };

	private boolean active;
	private boolean justEntered;
	/** Enabled file names (the key a file keeps whether or not it's disabled). */
	private final Set<String> selected = new LinkedHashSet<>();
	/** Rows of the current list in order, for select-all, shift ranges and the leave animation. */
	private final Map<String, HBox> rows = new LinkedHashMap<>();
	private String lastClicked;
	private int decorated;

	private final HBox bar;
	private final Label count = new Label();
	private final Button toggleAll = new Button("Select all");
	private final Button enable = new Button("Enable");
	private final Button disable = new Button("Disable");
	private final Button delete = new Button("Delete");
	final Button modeButton = new Button("Select");

	PackSelection(Stage owner, Instance instance, InstalledAssetStore.Kind kind, Path folder, String noun) {
		this.owner = owner;
		this.instance = instance;
		this.kind = kind;
		this.folder = folder;
		this.noun = noun;
		modeButton.setGraphic(Icons.icon("check", 14, Color.web("#E4E4E8"), 2.2));
		modeButton.setOnAction(e -> setActive(!active));

		count.getStyleClass().add("select-bar-count");
		toggleAll.getStyleClass().add("select-bar-link");
		toggleAll.setOnAction(e -> {
			if (selected.size() == rows.size()) {
				selected.clear();
			} else {
				selected.addAll(rows.keySet());
			}
			syncRows();
		});
		enable.setGraphic(Icons.icon("play", 13, Color.web("#7dd3a0"), 2.2));
		disable.setGraphic(Icons.icon("pause", 13, Color.web("#E4E4E8"), 2.2));
		delete.setGraphic(Icons.icon("trash", 13, Color.web("#ff8a8a"), 2.2));
		enable.getStyleClass().add("select-bar-button");
		disable.getStyleClass().add("select-bar-button");
		delete.getStyleClass().addAll("select-bar-button", "select-bar-danger");
		enable.setOnAction(e -> setEnabled(true));
		disable.setOnAction(e -> setEnabled(false));
		delete.setOnAction(e -> deleteSelected());
		Button close = new Button();
		close.setGraphic(Icons.icon("close", 14, Color.web("#A9A9B3"), 2.2));
		close.getStyleClass().addAll("icon-button", "icon-button-flat");
		close.setOnAction(e -> setActive(false));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		bar = new HBox(8, count, toggleAll, spacer, enable, disable, delete, close);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.getStyleClass().add("select-bar");
		bar.setMaxWidth(640);
		bar.setMaxHeight(Region.USE_PREF_SIZE);
		bar.setVisible(false);
		bar.setManaged(false);
	}

	void setRefresh(Runnable refresh) {
		this.refresh = refresh;
	}

	/** The floating action bar - put it in a StackPane over the list, aligned to the bottom. */
	Node bar() {
		return bar;
	}

	boolean active() {
		return active;
	}

	/** Esc leaves selection mode, Ctrl+A selects everything listed, Delete deletes. */
	void installKeys(Node root) {
		root.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
			if (!active) {
				return;
			}
			if (e.getCode() == KeyCode.ESCAPE) {
				setActive(false);
				e.consume();
			} else if (e.getCode() == KeyCode.A && e.isShortcutDown()) {
				selected.addAll(rows.keySet());
				syncRows();
				e.consume();
			} else if (e.getCode() == KeyCode.DELETE && !selected.isEmpty()) {
				deleteSelected();
				e.consume();
			}
		});
	}

	void setActive(boolean on) {
		if (active == on) {
			return;
		}
		active = on;
		justEntered = on;
		selected.clear();
		modeButton.getStyleClass().remove("select-mode-on");
		if (on) {
			modeButton.getStyleClass().add("select-mode-on");
		}
		showBar(on);
		refresh.run();
	}

	/** Called before the list is rebuilt. */
	void beginRows() {
		rows.clear();
		decorated = 0;
	}

	/** Adds the check box to a freshly built row (rows of launcher-managed files get none). */
	void decorate(HBox row, Path file, boolean selectable) {
		if (!active) {
			return;
		}
		String key = key(file);
		if (!selectable) {
			Region gap = new Region();
			gap.setMinWidth(22);
			row.getChildren().add(0, gap);
			return;
		}
		rows.put(key, row);
		CheckBox check = new CheckBox();
		check.getStyleClass().add("select-check");
		check.setSelected(selected.contains(key));
		check.setOnAction(e -> {
			toggle(key, check.isSelected(), false);
		});
		row.getChildren().add(0, check);
		row.setCursor(javafx.scene.Cursor.HAND);
		row.setOnMouseClicked(e -> {
			if (e.getTarget() instanceof Node target && isControl(target, row)) {
				return;
			}
			toggle(key, !selected.contains(key), e.isShiftDown());
		});
		styleRow(row, selected.contains(key));
		if (justEntered && decorated < 30) {
			// The boxes pop in one after another when selection mode turns on.
			check.setScaleX(0);
			check.setScaleY(0);
			ScaleTransition pop = new ScaleTransition(Duration.millis(220), check);
			pop.setToX(1);
			pop.setToY(1);
			pop.setInterpolator(UiMotion.BACK_OUT);
			pop.setDelay(Duration.millis(decorated * 14L));
			pop.play();
		}
		decorated++;
	}

	/** Called after the rows were added. */
	void endRows() {
		justEntered = false;
		selected.retainAll(rows.keySet());
		updateBar();
	}

	private static boolean isControl(Node target, Node row) {
		for (Node n = target; n != null && n != row; n = n.getParent()) {
			if (n instanceof javafx.scene.control.ButtonBase || n instanceof javafx.scene.control.ComboBoxBase<?>) {
				return true;
			}
		}
		return false;
	}

	private void toggle(String key, boolean on, boolean range) {
		if (range && lastClicked != null && rows.containsKey(lastClicked)) {
			List<String> keys = new ArrayList<>(rows.keySet());
			int a = keys.indexOf(lastClicked);
			int b = keys.indexOf(key);
			for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
				selected.add(keys.get(i));
			}
		} else if (on) {
			selected.add(key);
		} else {
			selected.remove(key);
		}
		lastClicked = key;
		syncRows();
	}

	private void syncRows() {
		rows.forEach((key, row) -> {
			boolean on = selected.contains(key);
			if (!row.getChildren().isEmpty() && row.getChildren().get(0) instanceof CheckBox check) {
				check.setSelected(on);
			}
			styleRow(row, on);
		});
		updateBar();
	}

	private static void styleRow(HBox row, boolean on) {
		row.getStyleClass().remove("mod-row-selected");
		if (on) {
			row.getStyleClass().add("mod-row-selected");
		}
	}

	private void updateBar() {
		int n = selected.size();
		String text = n == 0 ? "Pick " + noun : n + " selected";
		if (!text.equals(count.getText())) {
			count.setText(text);
			ScaleTransition bump = new ScaleTransition(Duration.millis(160), count);
			bump.setFromX(1.12);
			bump.setFromY(1.12);
			bump.setToX(1);
			bump.setToY(1);
			bump.play();
		}
		toggleAll.setText(n > 0 && n == rows.size() ? "Select none" : "Select all");
		enable.setDisable(n == 0);
		disable.setDisable(n == 0);
		delete.setDisable(n == 0);
	}

	private void showBar(boolean show) {
		if (show) {
			bar.setVisible(true);
			bar.setManaged(true);
			bar.setOpacity(0);
			bar.setTranslateY(60);
		}
		FadeTransition fade = new FadeTransition(Duration.millis(show ? 220 : 160), bar);
		fade.setToValue(show ? 1 : 0);
		TranslateTransition slide = new TranslateTransition(Duration.millis(show ? 320 : 180), bar);
		slide.setToY(show ? 0 : 60);
		slide.setInterpolator(show ? Interpolator.SPLINE(0.16, 1, 0.3, 1) : Interpolator.EASE_IN);
		ParallelTransition move = new ParallelTransition(fade, slide);
		if (!show) {
			move.setOnFinished(e -> {
				if (!active) {
					bar.setVisible(false);
					bar.setManaged(false);
				}
			});
		}
		move.play();
	}

	private static String key(Path file) {
		String name = file.getFileName().toString();
		return name.endsWith(DISABLED) ? name.substring(0, name.length() - DISABLED.length()) : name;
	}

	/** The file on disk for a key, enabled or disabled; null if it's gone. */
	private Path fileFor(String key) {
		Path on = folder.resolve(key);
		if (Files.exists(on)) {
			return on;
		}
		Path off = folder.resolve(key + DISABLED);
		return Files.exists(off) ? off : null;
	}

	private void setEnabled(boolean on) {
		int changed = 0;
		for (String key : List.copyOf(selected)) {
			Path file = fileFor(key);
			if (file == null) {
				continue;
			}
			Path target = folder.resolve(on ? key : key + DISABLED);
			if (!file.equals(target)) {
				try {
					Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
					changed++;
				} catch (Exception ignored) {
					// That one keeps its state - the list shows the truth after the refresh.
				}
			}
		}
		// Rows dim / light up together before the list is rebuilt.
		for (String key : selected) {
			HBox row = rows.get(key);
			if (row != null) {
				FadeTransition fade = new FadeTransition(Duration.millis(180), row);
				fade.setToValue(on ? 1 : 0.5);
				fade.play();
			}
		}
		Toasts.show(bar, (on ? "Enabled " : "Disabled ") + changed + " " + (changed == 1 ? singular() : noun));
		Timeline later = new Timeline(new KeyFrame(Duration.millis(200), e -> refresh.run()));
		later.play();
	}

	private void deleteSelected() {
		int n = selected.size();
		if (n == 0 || !Confirm.ask(owner, "Delete " + n + " " + (n == 1 ? singular() : noun) + "?",
				"They're moved to this profile's backups folder, so you can bring them back if you need them.")) {
			return;
		}
		List<String> keys = List.copyOf(selected);
		// Rows slide out and collapse, then the files go.
		List<javafx.animation.Animation> out = new ArrayList<>();
		for (String key : keys) {
			HBox row = rows.get(key);
			if (row == null) {
				continue;
			}
			double height = row.getHeight();
			row.setMinHeight(Region.USE_PREF_SIZE);
			Timeline collapse = new Timeline(
					new KeyFrame(Duration.ZERO, new KeyValue(row.opacityProperty(), row.getOpacity()), new KeyValue(row.translateXProperty(), 0),
							new KeyValue(row.prefHeightProperty(), height)),
					new KeyFrame(Duration.millis(260), new KeyValue(row.opacityProperty(), 0, Interpolator.EASE_IN),
							new KeyValue(row.translateXProperty(), 40, Interpolator.EASE_IN), new KeyValue(row.prefHeightProperty(), 0, Interpolator.EASE_IN)));
			out.add(collapse);
		}
		ParallelTransition all = new ParallelTransition(out.toArray(new javafx.animation.Animation[0]));
		all.setOnFinished(e -> {
			Path backups = InstancePaths.dir(instance.id()).resolve("backups").resolve(folder.getFileName().toString());
			int deleted = 0;
			for (String key : keys) {
				Path file = fileFor(key);
				if (file == null) {
					continue;
				}
				try {
					Files.createDirectories(backups);
					Files.move(file, backups.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					InstalledAssetStore.forget(instance.id(), kind, key);
					deleted++;
				} catch (Exception ignored) {
					// Stays in the list.
				}
			}
			selected.clear();
			Toasts.show(bar, "Deleted " + deleted + " " + (deleted == 1 ? singular() : noun) + " - kept in backups");
			refresh.run();
		});
		all.play();
	}

	private String singular() {
		return noun.endsWith("s") ? noun.substring(0, noun.length() - 1) : noun;
	}
}
