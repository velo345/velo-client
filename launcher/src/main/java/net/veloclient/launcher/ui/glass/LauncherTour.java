package net.veloclient.launcher.ui.glass;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.util.Duration;
import net.veloclient.launcher.data.VeloPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * A short guided tour of the launcher (2026-11-26), right after getting started: the window darkens except for one part
 * at a time, a glass card next to it says what it is. Back / Next, Esc or "Skip tour" ends it. Runs once (a marker in
 * config/), Settings can start it again.
 */
public final class LauncherTour {

	/** One stop: what to light up ({@code target} may give null = a card in the middle), its title and words, and what to
	 *  open first ({@code before}, e.g. go to Home). */
	public record Step(String title, String text, Supplier<Node> target, Runnable before) {
	}

	private static Pane open;

	private LauncherTour() {
	}

	private static Path marker() {
		return VeloPaths.config().resolve("launcher-tour-done");
	}

	public static boolean done() {
		return Files.exists(marker());
	}

	private static void markDone() {
		try {
			Files.createDirectories(marker().getParent());
			Files.writeString(marker(), String.valueOf(System.currentTimeMillis()));
		} catch (Exception ignored) {
			// it may show once more - fine
		}
	}

	/** The pulse ring: {@code t} 0..1 grows it up to 12 px outside the spotlight while it fades out. */
	private static void placePulse(Rectangle pulse, double x, double y, double w, double h, double t) {
		double grow = 12 * t;
		pulse.setVisible(w > 1);
		pulse.setX(x - grow);
		pulse.setY(y - grow);
		pulse.setWidth(w + grow * 2);
		pulse.setHeight(h + grow * 2);
		pulse.setArcWidth(28 + grow * 2);
		pulse.setArcHeight(28 + grow * 2);
		pulse.setOpacity(0.7 * (1 - t));
	}

	public static boolean running() {
		return open != null;
	}

	/** Starts the tour over {@code layers} (no-op while one runs). {@code startAt}: the first step (dev screenshots). */
	public static void start(StackPane layers, List<Step> steps, int startAt) {
		if (open != null || steps.isEmpty()) {
			return;
		}
		Pane root = new Pane();
		root.getStyleClass().add("tour-root");
		root.setPickOnBounds(true);
		open = root;
		// the spotlight: where the hole is (animated between steps)
		DoubleProperty hx = new SimpleDoubleProperty(0);
		DoubleProperty hy = new SimpleDoubleProperty(0);
		DoubleProperty hw = new SimpleDoubleProperty(0);
		DoubleProperty hh = new SimpleDoubleProperty(0);
		Rectangle ring = new Rectangle();
		ring.getStyleClass().add("tour-ring");
		ring.setArcWidth(28);
		ring.setArcHeight(28);
		ring.setManaged(false);
		ring.setMouseTransparent(true);
		// a soft "here": a second ring that keeps growing out of the first and fading
		Rectangle pulse = new Rectangle();
		pulse.getStyleClass().add("tour-pulse");
		pulse.setManaged(false);
		pulse.setMouseTransparent(true);
		DoubleProperty pulseT = new SimpleDoubleProperty(0);
		Timeline pulsing = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(pulseT, 0)),
				new KeyFrame(Duration.millis(1500), new KeyValue(pulseT, 1, Interpolator.SPLINE(0.25, 0.6, 0.35, 1))));
		pulsing.setCycleCount(Timeline.INDEFINITE);
		Pane scrimHolder = new Pane();
		scrimHolder.setManaged(false);
		Runnable[] redrawScrim = new Runnable[1];
		redrawScrim[0] = () -> {
			double w = Math.max(1, root.getWidth());
			double h = Math.max(1, root.getHeight());
			Rectangle all = new Rectangle(w, h);
			Shape scrim;
			if (hw.get() > 1 && hh.get() > 1) {
				Rectangle hole = new Rectangle(hx.get(), hy.get(), hw.get(), hh.get());
				hole.setArcWidth(28);
				hole.setArcHeight(28);
				scrim = Shape.subtract(all, hole);
			} else {
				scrim = all;
			}
			scrim.setFill(Color.rgb(6, 4, 8, 0.66));
			scrimHolder.getChildren().setAll(scrim);
			ring.setX(hx.get());
			ring.setY(hy.get());
			ring.setWidth(hw.get());
			ring.setHeight(hh.get());
			ring.setVisible(hw.get() > 1);
			placePulse(pulse, hx.get(), hy.get(), hw.get(), hh.get(), pulseT.get());
		};
		pulseT.addListener((o, a, b) -> placePulse(pulse, hx.get(), hy.get(), hw.get(), hh.get(), b.doubleValue()));
		pulsing.play();
		for (DoubleProperty p : List.of(hx, hy, hw, hh)) {
			p.addListener((o, a, b) -> redrawScrim[0].run());
		}
		root.widthProperty().addListener((o, a, b) -> redrawScrim[0].run());
		root.heightProperty().addListener((o, a, b) -> redrawScrim[0].run());

		// the card
		Label counter = new Label();
		counter.getStyleClass().add("tour-counter");
		Label title = new Label();
		title.getStyleClass().add("tour-title");
		title.setWrapText(true);
		Label text = new Label();
		text.getStyleClass().add("tour-text");
		text.setWrapText(true);
		Button skip = new Button("Skip tour");
		skip.getStyleClass().setAll("button", "tour-skip");
		Button back = new Button("Back");
		back.getStyleClass().setAll("button", "tour-back");
		Button next = new Button("Next");
		next.getStyleClass().setAll("button", "tour-next");
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox buttons = new HBox(8, skip, grow, back, next);
		buttons.setAlignment(Pos.CENTER_LEFT);
		VBox words = new VBox(8, counter, title, text);
		VBox card = new VBox(8, words, buttons);
		card.getStyleClass().addAll("glass", "tour-card");
		card.setPadding(new Insets(18, 20, 16, 20));
		card.setPrefWidth(330);
		card.setMaxWidth(330);
		card.setManaged(false);
		root.getChildren().addAll(scrimHolder, pulse, ring, card);
		layers.getChildren().add(root);
		root.resizeRelocate(0, 0, layers.getWidth(), layers.getHeight());
		layers.widthProperty().addListener((o, a, b) -> root.resize(layers.getWidth(), layers.getHeight()));
		layers.heightProperty().addListener((o, a, b) -> root.resize(layers.getWidth(), layers.getHeight()));

		int[] index = {Math.max(0, Math.min(steps.size() - 1, startAt))};
		boolean[] shownOnce = {false};
		Runnable[] show = new Runnable[1];
		Runnable end = () -> {
			markDone();
			FadeTransition out = new FadeTransition(Duration.millis(220), root);
			out.setToValue(0);
			out.setOnFinished(e -> {
				pulsing.stop();
				layers.getChildren().remove(root);
				open = null;
			});
			out.play();
		};
		show[0] = () -> {
			Step step = steps.get(index[0]);
			if (step.before() != null) {
				step.before().run();
			}
			// wait a frame for the page that `before` opened to lay out
			Platform.runLater(() -> Platform.runLater(() -> {
				Node target = step.target() == null ? null : step.target().get();
				double w = root.getWidth();
				double h = root.getHeight();
				double tx;
				double ty;
				double tw;
				double th;
				if (target != null && target.getScene() != null && target.isVisible()) {
					Bounds b = root.sceneToLocal(target.localToScene(target.getLayoutBounds()));
					tx = b.getMinX() - 8;
					ty = b.getMinY() - 8;
					tw = b.getWidth() + 16;
					th = b.getHeight() + 16;
				} else {
					tx = w / 2;
					ty = h / 2;
					tw = 0;
					th = 0;
				}
				new Timeline(new KeyFrame(Duration.millis(320),
						new KeyValue(hx, tx, Interpolator.SPLINE(0.2, 0.8, 0.25, 1)), new KeyValue(hy, ty, Interpolator.SPLINE(0.2, 0.8, 0.25, 1)),
						new KeyValue(hw, tw, Interpolator.SPLINE(0.2, 0.8, 0.25, 1)), new KeyValue(hh, th, Interpolator.SPLINE(0.2, 0.8, 0.25, 1)))).play();
				boolean first = !shownOnce[0];
				shownOnce[0] = true;
				Runnable fill = () -> {
					counter.setText((index[0] + 1) + " / " + steps.size());
					title.setText(step.title());
					text.setText(step.text());
					back.setVisible(index[0] > 0);
					next.setText(index[0] == steps.size() - 1 ? "Let's go" : "Next");
				};
				// where the card goes for this step (measured with the new words)
				final double ftx = tx, fty = ty, ftw = tw, fth = th, fw = w, fh = h;
				java.util.function.Supplier<double[]> where = () -> {
					card.applyCss();
					double cw = 330;
					double ch = card.prefHeight(cw);
					double cx;
					double cy;
					if (ftw <= 0) {
						cx = (fw - cw) / 2;
						cy = (fh - ch) / 2;
					} else if (ftx + ftw + 16 + cw < fw - 12) {
						cx = ftx + ftw + 16;            // right of it
						cy = fty + fth / 2 - ch / 2;
					} else if (ftx - 16 - cw > 12) {
						cx = ftx - 16 - cw;            // left of it
						cy = fty + fth / 2 - ch / 2;
					} else if (fty - 16 - ch > 12) {
						cx = ftx + ftw / 2 - cw / 2;    // above
						cy = fty - 16 - ch;
					} else {
						cx = ftx + ftw / 2 - cw / 2;    // below
						cy = fty + fth + 16;
					}
					return new double[] {Math.max(12, Math.min(fw - cw - 12, cx)), Math.max(12, Math.min(fh - ch - 12, cy)), cw, ch};
				};
				Interpolator glide = Interpolator.SPLINE(0.2, 0.8, 0.25, 1);
				if (first) {
					fill.run();
					double[] r = where.get();
					card.resizeRelocate(r[0], r[1], r[2], r[3]);
					card.setOpacity(0);
					card.setTranslateY(8);
					FadeTransition in = new FadeTransition(Duration.millis(220), card);
					in.setToValue(1);
					in.setDelay(Duration.millis(120));
					in.play();
					javafx.animation.TranslateTransition rise = new javafx.animation.TranslateTransition(Duration.millis(320), card);
					rise.setToY(0);
					rise.setDelay(Duration.millis(120));
					rise.setInterpolator(glide);
					rise.play();
				} else {
					// the old words fade out, the card glides (and resizes) to the new spot, the new words fade in
					FadeTransition out = new FadeTransition(Duration.millis(110), words);
					out.setToValue(0);
					out.setOnFinished(ev -> {
						fill.run();
						double[] r = where.get();
						double fromH = card.getHeight();
						DoubleProperty height = new SimpleDoubleProperty(fromH);
						height.addListener((o, a2, b2) -> card.resize(r[2], b2.doubleValue()));
						new Timeline(new KeyFrame(Duration.millis(340), new KeyValue(card.layoutXProperty(), r[0], glide),
								new KeyValue(card.layoutYProperty(), r[1], glide), new KeyValue(height, r[3], glide))).play();
						FadeTransition in = new FadeTransition(Duration.millis(200), words);
						in.setToValue(1);
						in.setDelay(Duration.millis(140));
						in.play();
					});
					out.play();
				}
				Glass.applyNow(card);
				next.requestFocus();
			}));
		};
		next.setOnAction(e -> {
			if (index[0] >= steps.size() - 1) {
				end.run();
			} else {
				index[0]++;
				show[0].run();
			}
		});
		back.setOnAction(e -> {
			if (index[0] > 0) {
				index[0]--;
				show[0].run();
			}
		});
		skip.setOnAction(e -> end.run());
		root.setFocusTraversable(true);
		root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
			if (e.getCode() == KeyCode.ESCAPE) {
				end.run();
				e.consume();
			} else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.ENTER) {
				next.fire();
				e.consume();
			} else if (e.getCode() == KeyCode.LEFT) {
				back.fire();
				e.consume();
			}
		});
		// clicks on the dark part do nothing (the tour stays until Next / Skip)
		root.setOnMouseClicked(e -> e.consume());
		root.setOpacity(0);
		FadeTransition in = new FadeTransition(Duration.millis(260), root);
		in.setToValue(1);
		in.play();
		redrawScrim[0].run();
		show[0].run();
	}
}
