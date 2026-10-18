package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The home screen's search box for profiles and saved servers. Focus it and every option is listed; type and the list narrows
 * by profile name, Minecraft version (e.g. "26.2") or server name/address. Arrow keys + Enter or a click choose.
 */
public final class HomeSearch {

	/** One suggestion: a profile or a server. {@code keywords} are extra searchable text (version, address...). */
	public record Entry(String title, String subtitle, boolean server, String keywords, Runnable choose, java.util.function.Supplier<javafx.scene.Node> icon) {
	}

	private static final int MAX_ROWS = 9;

	private HomeSearch() {
	}

	public static TextField create(Supplier<List<Entry>> entries) {
		TextField field = new TextField();
		field.setPromptText("Search profiles & servers...");
		field.setPrefWidth(250);
		field.setMinWidth(150);
		Popup popup = new Popup();
		popup.setAutoHide(true);
		popup.setHideOnEscape(true);
		VBox list = new VBox(2);
		list.setPadding(new Insets(6));
		list.setStyle("-fx-background-color: #17171d; -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.14); -fx-border-radius: 10;");
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
		popup.getContent().add(scroll);
		int[] selected = {0};
		List<Entry>[] shown = new List[] {List.of()};

		Runnable refresh = () -> {
			String query = field.getText().strip().toLowerCase(Locale.ROOT);
			List<Entry> matches = new ArrayList<>();
			for (Entry entry : entries.get()) {
				if (query.isEmpty() || matches(entry, query)) {
					matches.add(entry);
				}
			}
			shown[0] = matches;
			selected[0] = Math.min(selected[0], Math.max(0, matches.size() - 1));
			list.getChildren().clear();
			if (matches.isEmpty()) {
				Label none = new Label("Nothing matches \"" + field.getText().strip() + "\"");
				none.getStyleClass().add("settings-row-hint");
				none.setPadding(new Insets(6, 10, 6, 10));
				list.getChildren().add(none);
			}
			for (int i = 0; i < matches.size(); i++) {
				list.getChildren().add(row(matches.get(i), i == selected[0], () -> {
					popup.hide();
					field.clear();
				}, field, popup));
			}
			double rows = Math.max(1, Math.min(MAX_ROWS, matches.size()));
			scroll.setPrefViewportHeight(rows * 46 + 12);
			scroll.setPrefViewportWidth(Math.max(280, field.getWidth()));
			if (!popup.isShowing() && field.getScene() != null) {
				var bounds = field.localToScreen(field.getBoundsInLocal());
				if (bounds != null) {
					popup.show(field, bounds.getMinX(), bounds.getMaxY() + 4);
				}
			}
		};
		field.focusedProperty().addListener((o, was, now) -> {
			if (now) {
				Platform.runLater(refresh);
			}
		});
		field.setOnMouseClicked(e -> {
			if (!popup.isShowing()) {
				refresh.run();
			}
		});
		field.textProperty().addListener((o, a, b) -> {
			selected[0] = 0;
			refresh.run();
		});
		field.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
				if (!popup.isShowing()) {
					refresh.run();
				} else if (!shown[0].isEmpty()) {
					selected[0] = Math.floorMod(selected[0] + (e.getCode() == KeyCode.DOWN ? 1 : -1), shown[0].size());
					refresh.run();
					double position = shown[0].size() <= MAX_ROWS ? 0 : selected[0] / (double) (shown[0].size() - 1);
					scroll.setVvalue(position);
				}
				e.consume();
			} else if (e.getCode() == KeyCode.ENTER) {
				if (!shown[0].isEmpty()) {
					Entry entry = shown[0].get(selected[0]);
					popup.hide();
					field.clear();
					entry.choose().run();
				}
				e.consume();
			} else if (e.getCode() == KeyCode.ESCAPE) {
				popup.hide();
				e.consume();
			}
		});
		return field;
	}

	private static boolean matches(Entry entry, String query) {
		String haystack = (entry.title() + " " + entry.subtitle() + " " + entry.keywords()).toLowerCase(Locale.ROOT);
		for (String token : query.split("\\s+")) {
			if (!haystack.contains(token)) {
				return false;
			}
		}
		return true;
	}

	private static HBox row(Entry entry, boolean highlighted, Runnable afterChoose, TextField field, Popup popup) {
		// The server's own icon (Velo logo until it loads / when it has none) or the profile's icon.
		javafx.scene.layout.StackPane badge = new javafx.scene.layout.StackPane();
		badge.setMinSize(32, 32);
		badge.setPrefSize(32, 32);
		badge.setMaxSize(32, 32);
		javafx.scene.Node icon = entry.icon() == null ? null : entry.icon().get();
		if (icon != null) {
			badge.getChildren().add(icon);
		}
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(32, 32);
		clip.setArcWidth(10);
		clip.setArcHeight(10);
		badge.setClip(clip);
		Label title = new Label(entry.title());
		title.setStyle("-fx-font-weight: 700; -fx-text-fill: white;");
		Label subtitle = new Label(entry.subtitle());
		subtitle.getStyleClass().add("settings-row-hint");
		VBox text = new VBox(1, title, subtitle);
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox row = new HBox(10, badge, text, grow);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(6, 10, 6, 8));
		String base = highlighted ? "rgba(255,255,255,0.12)" : "transparent";
		row.setStyle("-fx-background-radius: 8; -fx-background-color: " + base + ";");
		row.setOnMouseEntered(e -> row.setStyle("-fx-background-radius: 8; -fx-background-color: rgba(255,255,255,0.16);"));
		row.setOnMouseExited(e -> row.setStyle("-fx-background-radius: 8; -fx-background-color: " + base + ";"));
		row.setOnMouseClicked(e -> {
			afterChoose.run();
			entry.choose().run();
		});
		return row;
	}
}
