package net.veloclient.launcher.ui.glass;

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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import net.veloclient.launcher.ui.UiMotion;

import java.util.Random;

/**
 * Home's player arriving (2026-11-25): a light ring opens on the ground, pixel sparks rise from the feet, the player grows
 * up out of it with a small overshoot, then the name tag drops in. Everything extra is removed again when it's done.
 */
public final class PlayerEntrance {

	private PlayerEntrance() {
	}

	public static void play(StackPane stage, Node player, Node tag) {
		player.setOpacity(0);
		player.setScaleX(0.82);
		player.setScaleY(0.82);
		player.setTranslateY(46);

		Region glow = new Region();
		glow.getStyleClass().add("spawn-glow");
		glow.setMouseTransparent(true);
		glow.setMaxSize(320, 120);
		StackPane.setAlignment(glow, Pos.BOTTOM_CENTER);
		glow.setOpacity(0);
		Region ring = new Region();
		ring.getStyleClass().add("spawn-ring");
		ring.setMouseTransparent(true);
		ring.setMaxSize(260, 52);
		StackPane.setAlignment(ring, Pos.BOTTOM_CENTER);
		ring.setScaleX(0.15);
		ring.setScaleY(0.15);
		Pane sparks = new Pane();
		sparks.setMouseTransparent(true);
		sparks.setPickOnBounds(false);
		stage.getChildren().add(0, glow);
		stage.getChildren().add(1, ring);
		stage.getChildren().add(sparks);

		Timeline ringOpen = new Timeline(
				new KeyFrame(Duration.ZERO, new KeyValue(ring.opacityProperty(), 1)),
				new KeyFrame(Duration.millis(900), new KeyValue(ring.scaleXProperty(), 1.7, Interpolator.SPLINE(0.2, 0.8, 0.3, 1)),
						new KeyValue(ring.scaleYProperty(), 1.7, Interpolator.SPLINE(0.2, 0.8, 0.3, 1)),
						new KeyValue(ring.opacityProperty(), 0, Interpolator.EASE_IN)));
		Timeline glowPulse = new Timeline(
				new KeyFrame(Duration.millis(150), new KeyValue(glow.opacityProperty(), 1, Interpolator.EASE_OUT)),
				new KeyFrame(Duration.millis(1300), new KeyValue(glow.opacityProperty(), 0, Interpolator.EASE_IN)));

		FadeTransition fade = new FadeTransition(Duration.millis(420), player);
		fade.setToValue(1);
		ScaleTransition grow = new ScaleTransition(Duration.millis(760), player);
		grow.setToX(1);
		grow.setToY(1);
		// no overshoot past full size: the bounce pushed the head over the top of the stage (cut off)
		grow.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.25, 1));
		TranslateTransition rise = new TranslateTransition(Duration.millis(760), player);
		rise.setToY(0);
		rise.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.25, 1));
		// ...then a wave hello
		arriveWave(player);
		ParallelTransition arrive = new ParallelTransition(fade, grow, rise);
		arrive.setDelay(Duration.millis(160));

		// pixel sparks: little squares rising from the feet and fading, in two colours
		Random random = new Random();
		stage.layout();
		double w = stage.getWidth(), h = stage.getHeight();
		for (int i = 0; i < 22; i++) {
			Region spark = new Region();
			spark.getStyleClass().add(i % 3 == 0 ? "spawn-spark-white" : "spawn-spark");
			double size = 3 + random.nextInt(3) * 2;
			spark.resize(size, size);
			spark.setManaged(false);
			double x = w / 2 + (random.nextDouble() - 0.5) * 170;
			spark.relocate(x, h - 26 - random.nextDouble() * 18);
			spark.setOpacity(0);
			sparks.getChildren().add(spark);
			double up = 120 + random.nextDouble() * Math.max(60, h * 0.55);
			Timeline t = new Timeline(
					new KeyFrame(Duration.ZERO, new KeyValue(spark.opacityProperty(), 0)),
					new KeyFrame(Duration.millis(120), new KeyValue(spark.opacityProperty(), 1)),
					new KeyFrame(Duration.millis(900 + random.nextInt(600)), new KeyValue(spark.translateYProperty(), -up, Interpolator.EASE_OUT),
							new KeyValue(spark.translateXProperty(), (random.nextDouble() - 0.5) * 40),
							new KeyValue(spark.opacityProperty(), 0, Interpolator.EASE_IN)));
			t.setDelay(Duration.millis(80 + random.nextInt(380)));
			t.play();
		}

		ringOpen.play();
		glowPulse.play();
		arrive.play();
		if (tag != null) {
			tag.setOpacity(0);
			tag.setTranslateY(-10);
			FadeTransition tagFade = new FadeTransition(Duration.millis(320), tag);
			tagFade.setToValue(1);
			TranslateTransition tagDrop = new TranslateTransition(Duration.millis(420), tag);
			tagDrop.setToY(0);
			tagDrop.setInterpolator(UiMotion.BACK_OUT_SOFT);
			ParallelTransition tagIn = new ParallelTransition(tagFade, tagDrop);
			tagIn.setDelay(Duration.millis(620));
			tagIn.play();
		}
		Timeline cleanup = new Timeline(new KeyFrame(Duration.millis(2400), e -> stage.getChildren().removeAll(glow, ring, sparks)));
		cleanup.play();
	}

	/** Waves the player's hand (a viewer from PlayerSkin3DView.createIdleViewer) once it has arrived. */
	private static void arriveWave(Node player) {
		javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(Duration.millis(950));
		later.setOnFinished(e -> wave(player));
		later.play();
	}

	/** A wave of the right hand, if {@code player} is an idle viewer (otherwise nothing). */
	public static void wave(Node player) {
		if (player != null && player.getProperties().get("velo.wave") instanceof Runnable wave) {
			wave.run();
		}
	}
}
