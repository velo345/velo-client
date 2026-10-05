package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Rotating news banner: each slide is a post's cover with its tag, title and summary over a dark
 * fade. Slides change every few seconds with a crossfade + small slide (paused while hovered),
 * with dots and arrows to jump around. Used big on the News page and compact on Home.
 */
final class NewsCarousel extends StackPane {

	private final List<JsonObject> posts;
	private final Consumer<JsonObject> onOpen;
	private final boolean compact;
	private final StackPane stage = new StackPane();
	private final HBox dots = new HBox(6);
	private final Timeline timer;
	private int index;
	private boolean hovered;

	NewsCarousel(List<JsonObject> posts, boolean compact, Consumer<JsonObject> onOpen) {
		this.posts = new ArrayList<>(posts);
		this.onOpen = onOpen;
		this.compact = compact;
		getStyleClass().addAll("news-carousel", compact ? "news-carousel-compact" : "news-carousel-large");
		setMinSize(0, 0);
		stage.setMinSize(0, 0);
		getChildren().add(stage);

		dots.setAlignment(Pos.CENTER_RIGHT);
		dots.setPickOnBounds(false);
		dots.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(dots, compact ? Pos.TOP_RIGHT : Pos.BOTTOM_RIGHT);
		StackPane.setMargin(dots, compact ? new Insets(12, 12, 0, 0) : new Insets(0, 22, 22, 0));
		if (this.posts.size() > 1) {
			getChildren().add(dots);
			if (!compact) {
				Button prev = arrow("‹", -1);
				Button next = arrow("›", 1);
				StackPane.setAlignment(prev, Pos.CENTER_LEFT);
				StackPane.setAlignment(next, Pos.CENTER_RIGHT);
				StackPane.setMargin(prev, new Insets(0, 0, 0, 12));
				StackPane.setMargin(next, new Insets(0, 12, 0, 0));
				getChildren().addAll(prev, next);
				prev.visibleProperty().bind(hoverProperty());
				next.visibleProperty().bind(hoverProperty());
			}
		}
		setOnMouseEntered(e -> hovered = true);
		setOnMouseExited(e -> hovered = false);

		timer = new Timeline(new KeyFrame(Duration.seconds(compact ? 6 : 7), e -> {
			if (!hovered && this.posts.size() > 1) {
				show(index + 1, 1);
			}
		}));
		timer.setCycleCount(Timeline.INDEFINITE);
		// Stops when the page goes away (removed from the scene), so old pages don't keep ticking.
		sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				timer.stop();
			} else {
				timer.play();
			}
		});
		if (!this.posts.isEmpty()) {
			stage.getChildren().add(slide(this.posts.get(0)));
			updateDots();
		}
	}

	private Button arrow(String glyph, int step) {
		Button button = new Button(glyph);
		button.getStyleClass().add("news-carousel-arrow");
		button.setOnAction(e -> show(index + step, step));
		return button;
	}

	private void show(int target, int direction) {
		if (posts.isEmpty()) {
			return;
		}
		int next = Math.floorMod(target, posts.size());
		if (next == index && stage.getChildren().size() == 1) {
			return;
		}
		index = next;
		Node old = stage.getChildren().isEmpty() ? null : stage.getChildren().get(stage.getChildren().size() - 1);
		Node incoming = slide(posts.get(index));
		incoming.setOpacity(0);
		incoming.setTranslateX(direction * 26);
		stage.getChildren().add(incoming);
		FadeTransition fadeIn = new FadeTransition(Duration.millis(420), incoming);
		fadeIn.setToValue(1);
		TranslateTransition slideIn = new TranslateTransition(Duration.millis(420), incoming);
		slideIn.setToX(0);
		slideIn.setInterpolator(UiMotion.EASE_OUT);
		ParallelTransition in = new ParallelTransition(fadeIn, slideIn);
		in.setOnFinished(e -> {
			if (old != null) {
				stage.getChildren().remove(old);
			}
		});
		in.play();
		updateDots();
	}

	private void updateDots() {
		dots.getChildren().clear();
		for (int i = 0; i < posts.size(); i++) {
			Region dot = new Region();
			dot.getStyleClass().add(i == index ? "news-dot-active" : "news-dot");
			int target = i;
			dot.setOnMouseClicked(e -> {
				e.consume();
				show(target, target > index ? 1 : -1);
			});
			dots.getChildren().add(dot);
		}
	}

	private Node slide(JsonObject post) {
		String accent = NewsUi.str(post, "accent");
		StackPane cover = NewsUi.cover(NewsUi.str(post, "cover"), accent, compact ? null : NewsUi.str(post, "tag"), compact ? 16 : 22);
		Region shade = new Region();
		shade.getStyleClass().add("news-slide-shade");
		shade.setMouseTransparent(true);

		Label tag = NewsUi.tag(NewsUi.str(post, "tag"), accent);
		Label when = new Label(NewsUi.date(NewsUi.num(post, "publishedAt")));
		when.getStyleClass().add("news-slide-date");
		HBox meta = new HBox(8, tag, when);
		meta.setAlignment(Pos.CENTER_LEFT);
		String version = NewsUi.str(post, "version");
		if (version != null && !version.isBlank()) {
			Label v = new Label("v" + version.replaceFirst("^v", ""));
			v.getStyleClass().add("news-version");
			meta.getChildren().add(1, v);
		}

		Label title = new Label(NewsUi.str(post, "title"));
		title.getStyleClass().add(compact ? "news-slide-title-compact" : "news-slide-title");
		title.setWrapText(true);
		VBox text = new VBox(compact ? 4 : 8, meta, title);
		String summary = NewsUi.str(post, "summary");
		if (summary != null && !summary.isBlank()) {
			Label sub = new Label(summary);
			sub.getStyleClass().add(compact ? "news-slide-summary-compact" : "news-slide-summary");
			sub.setWrapText(true);
			sub.setMaxHeight(compact ? 34 : 60);
			text.getChildren().add(sub);
		}
		if (!compact) {
			Button read = new Button("Read more  →");
			read.getStyleClass().add("primary-button");
			read.setOnAction(e -> onOpen.accept(post));
			VBox.setMargin(read, new Insets(6, 0, 0, 0));
			text.getChildren().add(read);
			text.setMaxWidth(560);
		}
		text.setAlignment(Pos.BOTTOM_LEFT);
		text.setPickOnBounds(false);
		StackPane.setAlignment(text, Pos.BOTTOM_LEFT);
		StackPane.setMargin(text, compact ? new Insets(0, 14, 14, 16) : new Insets(0, 28, 26, 30));
		text.setMaxHeight(Region.USE_PREF_SIZE);

		StackPane slide = new StackPane(cover, shade, text);
		slide.setMinSize(0, 0);
		slide.setCursor(javafx.scene.Cursor.HAND);
		slide.setOnMouseClicked(e -> onOpen.accept(post));
		return slide;
	}
}
