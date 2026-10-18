package net.veloclient.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Loading placeholders in the shape of what's coming - rows, cards, a detail page - instead of an empty area or a
 * "Loading..." line. They pulse softly while shown; the animation only runs while the skeleton is in a window, so a
 * skeleton that gets replaced costs nothing.
 */
public final class Skeleton {

	private Skeleton() {
	}

	/** {@code rows} list rows: icon, title + subtitle, a button on the right (mods, servers, friends, reports...). */
	public static Node list(int rows) {
		VBox box = new VBox(10);
		box.setPadding(new Insets(6, 2, 6, 2));
		for (int i = 0; i < rows; i++) {
			HBox row = new HBox(12, block(40, 40, 10), lines(i), grow(), block(78, 28, 8));
			row.setAlignment(Pos.CENTER_LEFT);
			row.setPadding(new Insets(10, 12, 10, 12));
			row.getStyleClass().add("skeleton-card");
			box.getChildren().add(row);
		}
		return pulse(box);
	}

	/** {@code count} cards of {@code w} x {@code h} in a wrapping grid (store, cosmetics, worlds...). */
	public static Node grid(int count, double w, double h) {
		FlowPane pane = new FlowPane(14, 14);
		pane.setPadding(new Insets(6, 2, 6, 2));
		for (int i = 0; i < count; i++) {
			VBox card = new VBox(8, block(w - 24, h - 66, 8), block((w - 24) * (i % 2 == 0 ? 0.7 : 0.5), 12, 6), block((w - 24) * 0.35, 10, 5));
			card.setPadding(new Insets(12));
			card.setPrefSize(w, h);
			card.setMinSize(w, h);
			card.setMaxSize(w, h);
			card.getStyleClass().add("skeleton-card");
			pane.getChildren().add(card);
		}
		return pulse(pane);
	}

	/** A detail page: a hero block and a few paragraphs. */
	public static Node detail() {
		VBox box = new VBox(12, block(-1, 160, 12), block(260, 22, 8), block(-1, 12, 6), block(-1, 12, 6), block(420, 12, 6),
				block(140, 34, 10));
		box.setPadding(new Insets(6, 2, 6, 2));
		return pulse(box);
	}

	/** Text-line placeholders: a title and a shorter subtitle, widths varied per row so it doesn't look like a barcode. */
	private static Node lines(int seed) {
		double[] widths = {0.55, 0.42, 0.63, 0.48, 0.38, 0.58};
		VBox lines = new VBox(7, block(320 * widths[seed % widths.length], 13, 6), block(420 * widths[(seed + 3) % widths.length], 10, 5));
		lines.setAlignment(Pos.CENTER_LEFT);
		return lines;
	}

	private static Region grow() {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		return spacer;
	}

	/** A rounded block; width -1 = fill the width. */
	private static Region block(double w, double h, double radius) {
		Region r = new Region();
		r.getStyleClass().add("skeleton-block");
		r.setStyle("-fx-background-radius: " + radius + ";");
		r.setMinHeight(h);
		r.setPrefHeight(h);
		r.setMaxHeight(h);
		if (w >= 0) {
			r.setMinWidth(w);
			r.setPrefWidth(w);
			r.setMaxWidth(w);
		} else {
			r.setMaxWidth(Double.MAX_VALUE);
		}
		return r;
	}

	private static Node pulse(Region root) {
		root.setMouseTransparent(true);
		FadeTransition fade = new FadeTransition(Duration.millis(900), root);
		fade.setFromValue(1.0);
		fade.setToValue(0.45);
		fade.setAutoReverse(true);
		fade.setCycleCount(Animation.INDEFINITE);
		root.sceneProperty().addListener((o, was, scene) -> {
			if (scene != null) {
				fade.play();
			} else {
				fade.stop();
			}
		});
		return root;
	}
}
