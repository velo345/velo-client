package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/** Short confirmations ("Added to your Locker") that slide in at the bottom of the window and fade away. */
public final class Toasts {

	private Toasts() {
	}

	/** Shows {@code message} over the window {@code anyNode} is in (needs the window's StackPane root). */
	public static void show(Node anyNode, String message) {
		show(anyNode, "check", "#3DDC97", message);
	}

	public static void show(Node anyNode, String icon, String color, String message) {
		if (anyNode == null || anyNode.getScene() == null || !(anyNode.getScene().getRoot() instanceof StackPane layers)) {
			return;
		}
		StackPane badge = new StackPane(Icons.icon(icon, 15, Color.web(color), 2.4));
		badge.setMinSize(28, 28);
		badge.setMaxSize(28, 28);
		badge.setStyle("-fx-background-color: " + color + "24; -fx-background-radius: 8;");
		Label text = new Label(message);
		text.getStyleClass().add("t-strong");
		HBox toast = new HBox(10, badge, text);
		toast.getStyleClass().add("toast");
		toast.setAlignment(Pos.CENTER_LEFT);
		toast.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		toast.setMouseTransparent(true);
		StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
		StackPane.setMargin(toast, new Insets(0, 0, 100, 0));
		layers.getChildren().add(toast);
		toast.setOpacity(0);
		toast.setTranslateY(16);
		FadeTransition in = new FadeTransition(Duration.millis(220), toast);
		in.setToValue(1);
		TranslateTransition up = new TranslateTransition(Duration.millis(260), toast);
		up.setToY(0);
		up.setInterpolator(UiMotion.EASE_OUT);
		FadeTransition out = new FadeTransition(Duration.millis(260), toast);
		out.setToValue(0);
		SequentialTransition all = new SequentialTransition(new ParallelTransition(in, up), new PauseTransition(Duration.seconds(2.6)), out);
		all.setOnFinished(e -> {
			Parent parent = toast.getParent();
			if (parent instanceof StackPane stack) {
				stack.getChildren().remove(toast);
			}
		});
		all.play();
	}
}
