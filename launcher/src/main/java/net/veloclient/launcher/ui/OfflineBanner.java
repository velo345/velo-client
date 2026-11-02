package net.veloclient.launcher.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import net.veloclient.launcher.Connectivity;

/**
 * The strip under the top bar while there's no internet: what still works (playing singleplayer / LAN with the saved
 * account) and what waits for the connection (servers, Store, friends, mod downloads), with "Try again". It slides
 * down when the connection drops and folds away by itself when it's back.
 */
public final class OfflineBanner {

	private OfflineBanner() {
	}

	public static Node build(Runnable retry) {
		Node icon = Icons.icon("globe", 15, Color.web("#f7c04a"), 2.2);
		Label title = new Label("You're offline");
		title.getStyleClass().add("offline-title");
		Label text = new Label("Singleplayer and LAN still work with your saved account. Servers, Store, friends and mod downloads come back when you're online.");
		text.getStyleClass().add("offline-text");
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		Button again = new Button("Try again");
		again.getStyleClass().add("offline-retry");
		again.setOnAction(e -> {
			again.setDisable(true);
			again.setText("Checking...");
			retry.run();
			Timeline reset = new Timeline(new KeyFrame(Duration.seconds(4), ev -> {
				again.setDisable(false);
				again.setText("Try again");
			}));
			reset.play();
		});
		HBox row = new HBox(10, icon, title, text, again);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("offline-banner");
		StackPane holder = new StackPane(row);
		// Height animates between 0 and the row's height; the clip keeps the folding row from drawing outside.
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(holder.widthProperty());
		clip.heightProperty().bind(holder.heightProperty());
		holder.setClip(clip);
		holder.setMinHeight(0);
		holder.setPrefHeight(0);
		holder.setMaxHeight(Region.USE_PREF_SIZE);
		Runnable sync = () -> {
			boolean show = Connectivity.offline();
			double target = show ? Math.max(40, row.prefHeight(-1)) : 0;
			new Timeline(new KeyFrame(Duration.millis(show ? 340 : 240),
					new KeyValue(holder.prefHeightProperty(), target, show ? UiMotion.EASE_OUT : Interpolator.EASE_IN))).play();
		};
		Connectivity.onChange(() -> Platform.runLater(sync));
		Platform.runLater(sync);
		return holder;
	}
}
