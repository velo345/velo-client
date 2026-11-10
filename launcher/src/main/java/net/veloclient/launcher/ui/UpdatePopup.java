package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The update pop-up (2026-11): every new version shows up as this window over the launcher - built from the version's
 * news post (cover, title, summary, its section headings as highlights) instead of a corner card or a bell dot that
 * read like a raw chat message. Falls back to the release notes when there is no post for the version.
 */
public final class UpdatePopup {

	/** A button along the bottom; {@code primary} is the red one. The runnable gets the open pop-up. */
	public record Action(String label, boolean primary, java.util.function.Consumer<Handle> run) {
	}

	/** The open pop-up: change its status line, lock its buttons while working, close it. */
	public interface Handle {
		void status(String text);

		void busy(boolean busy);

		void close();
	}

	private UpdatePopup() {
	}

	/**
	 * @param post the version's news post, or null
	 * @param overline "UPDATE READY" / "UPDATE AVAILABLE" / "INSTALLED"
	 * @param versions "1.3.1 → 1.3.2"
	 */
	public static Handle show(StackPane layers, JsonObject post, String overline, String versions, String fallbackTitle,
			String fallbackText, String statusText, List<Action> actions) {
		Region scrim = new Region();
		scrim.getStyleClass().add("update-scrim");

		VBox card = new VBox();
		card.getStyleClass().add("update-card");
		card.setMaxSize(760, Region.USE_PREF_SIZE);
		card.setPrefWidth(760);

		String accent = post == null ? null : NewsUi.str(post, "accent");
		StackPane cover = NewsUi.cover(post == null ? "" : NewsUi.str(post, "cover"), accent, null, 0);
		cover.setMinHeight(post == null ? 120 : 280);
		cover.setPrefHeight(post == null ? 120 : 280);
		cover.setMaxHeight(post == null ? 120 : 280);
		Region fade = new Region();
		fade.getStyleClass().add("update-cover-fade");
		fade.setMouseTransparent(true);
		Button close = new Button();
		close.setGraphic(Icons.icon("close", 15, Color.web("#F4EFEE"), 2.2));
		close.getStyleClass().addAll("icon-button", "update-close");
		StackPane.setAlignment(close, Pos.TOP_RIGHT);
		StackPane.setMargin(close, new Insets(12));
		StackPane top = new StackPane(cover, fade, close);
		javafx.scene.shape.Rectangle topClip = new javafx.scene.shape.Rectangle();
		topClip.widthProperty().bind(top.widthProperty());
		topClip.heightProperty().bind(top.heightProperty().add(20));
		topClip.setArcWidth(32);
		topClip.setArcHeight(32);
		top.setClip(topClip);

		Label over = new Label(overline);
		over.getStyleClass().add("update-overline");
		Label fromTo = new Label(versions);
		fromTo.getStyleClass().add("update-versions");
		HBox overRow = new HBox(12, over, fromTo);
		overRow.setAlignment(Pos.CENTER_LEFT);
		String titleText = post != null && !NewsUi.str(post, "title").isBlank() ? NewsUi.str(post, "title") : fallbackTitle;
		Label title = new Label(titleText);
		title.getStyleClass().add("update-title");
		title.setWrapText(true);
		VBox body = new VBox(18);
		body.setPadding(new Insets(6, 32, 26, 32));
		body.getChildren().add(new VBox(10, overRow, title));
		String summary = post != null ? NewsUi.str(post, "summary") : fallbackText;
		if (summary != null && !summary.isBlank()) {
			Label text = new Label(summary.length() > 260 ? summary.substring(0, 257) + "..." : summary);
			text.getStyleClass().add("update-summary");
			text.setWrapText(true);
			body.getChildren().add(text);
		}

		List<String> highlights = highlights(post);
		if (!highlights.isEmpty()) {
			GridPane grid = new GridPane();
			grid.setHgap(10);
			grid.setVgap(10);
			javafx.scene.layout.ColumnConstraints half = new javafx.scene.layout.ColumnConstraints();
			half.setPercentWidth(50);
			grid.getColumnConstraints().addAll(half, half);
			for (int i = 0; i < highlights.size(); i++) {
				StackPane icon = new StackPane(Icons.icon("check", 14, Color.web("#FF6B6F"), 2.4));
				icon.getStyleClass().add("update-point-icon");
				Label point = new Label(highlights.get(i));
				point.getStyleClass().add("update-point-text");
				point.setWrapText(true);
				HBox tile = new HBox(12, icon, point);
				tile.setAlignment(Pos.CENTER_LEFT);
				tile.getStyleClass().add("update-point");
				tile.setMaxWidth(Double.MAX_VALUE);
				grid.add(tile, i % 2, i / 2);
			}
			body.getChildren().add(grid);
		}

		Region square = new Region();
		square.getStyleClass().add("status-square");
		Label status = new Label(statusText);
		status.getStyleClass().add("update-status");
		status.setGraphic(square);
		status.setGraphicTextGap(9);
		status.setWrapText(true);
		HBox.setHgrow(status, Priority.ALWAYS);
		status.setMaxWidth(Double.MAX_VALUE);
		HBox footer = new HBox(10, status);
		footer.setAlignment(Pos.CENTER_LEFT);
		footer.getStyleClass().add("update-footer");
		body.getChildren().add(footer);
		card.getChildren().addAll(top, body);

		StackPane overlay = new StackPane(scrim, card);
		overlay.getStyleClass().add("update-overlay");
		StackPane.setAlignment(card, Pos.CENTER);
		List<Button> buttons = new ArrayList<>();
		Handle[] handle = new Handle[1];
		handle[0] = new Handle() {
			@Override
			public void status(String text) {
				status.setText(text);
			}

			@Override
			public void busy(boolean busy) {
				buttons.forEach(b -> b.setDisable(busy));
				close.setDisable(busy);
			}

			@Override
			public void close() {
				FadeTransition out = new FadeTransition(Duration.millis(160), overlay);
				out.setToValue(0);
				out.setOnFinished(e -> layers.getChildren().remove(overlay));
				out.play();
			}
		};
		for (Action action : actions) {
			Button button = new Button(action.label());
			button.getStyleClass().add(action.primary() ? "primary-button" : "ghost-button");
			button.setMinWidth(Region.USE_PREF_SIZE);
			button.setOnAction(e -> action.run().accept(handle[0]));
			buttons.add(button);
			footer.getChildren().add(button);
		}
		close.setOnAction(e -> handle[0].close());

		layers.getChildren().add(overlay);
		overlay.setOpacity(0);
		card.setScaleX(0.96);
		card.setScaleY(0.96);
		FadeTransition in = new FadeTransition(Duration.millis(200), overlay);
		in.setToValue(1);
		ScaleTransition grow = new ScaleTransition(Duration.millis(320), card);
		grow.setToX(1);
		grow.setToY(1);
		grow.setInterpolator(UiMotion.BACK_OUT_SOFT);
		new ParallelTransition(in, grow).play();
		UiSounds.tab();
		return handle[0];
	}

	/** The post's section headings ("Exact export", "Servers in Velo's own style"...), up to four. */
	private static List<String> highlights(JsonObject post) {
		List<String> out = new ArrayList<>();
		if (post == null || !post.has("blocks") || !post.get("blocks").isJsonArray()) {
			return out;
		}
		for (JsonElement element : post.getAsJsonArray("blocks")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject block = element.getAsJsonObject();
			if ("heading".equals(NewsUi.str(block, "type")) && !NewsUi.str(block, "text").isBlank()) {
				out.add(NewsUi.str(block, "text"));
				if (out.size() == 4) {
					break;
				}
			}
		}
		return out;
	}

	/** The news post written for {@code version} (its "version" field), if the feed has one. */
	public static JsonObject postFor(JsonObject feed, String version) {
		if (feed == null || version == null || !feed.has("posts") || !feed.get("posts").isJsonArray()) {
			return null;
		}
		for (JsonElement element : feed.getAsJsonArray("posts")) {
			if (element.isJsonObject() && version.equals(NewsUi.str(element.getAsJsonObject(), "version"))) {
				return element.getAsJsonObject();
			}
		}
		return null;
	}
}
