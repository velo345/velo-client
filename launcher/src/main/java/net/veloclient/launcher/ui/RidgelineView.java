package net.veloclient.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Home's backdrop since the 2026-11 redesign (replaces the 3D island): a blocky horizon at dusk.
 * <ul>
 * <li>three far ridgelines drift slowly sideways (each one seamless period drawn twice, so it loops forever),</li>
 * <li>flat blocky clouds drift slower still, stars twinkle, a square moon wanders and glows,</li>
 * <li>a mist band breathes over the horizon and a meteor crosses every ~23 s,</li>
 * <li>the front ridge stays put so the player can stand on its plateau ({@link #setPlayer}).</li>
 * </ul>
 * All 2D shapes, so it also works where JavaFX can't do 3D. Colours are mixed from the theme's accent and background.
 * Everything stops while the view isn't in a showing window, and {@link #setAnimated(boolean)} turns motion off.
 */
public final class RidgelineView extends Region {

	private static boolean animatedSetting = true;

	private final Pane stage = new Pane();
	private final List<Animation> animations = new ArrayList<>();
	private final PauseTransition rebuildLater = new PauseTransition(Duration.millis(120));
	private Color accent = Color.web("#D7262D");
	private Color background = Color.web("#0C0A0A");
	private Node player;
	private double builtW;
	private double builtH;
	private boolean running;

	public RidgelineView() {
		getStyleClass().add("ridgeline");
		setMinSize(0, 0);
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		setClip(clip);
		getChildren().add(stage);
		setMouseTransparent(true);
		rebuildLater.setOnFinished(e -> rebuild());
		sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				stopAll();
			} else if (scene.getWindow() != null) {
				watchWindow(scene.getWindow());
			} else {
				scene.windowProperty().addListener((ow, aw, w) -> {
					if (w != null) {
						watchWindow(w);
					}
				});
			}
		});
	}

	/** Settings > Appearance "Animate the Home background" - applies to every view, now and later. */
	public static void setAnimatedDefault(boolean animated) {
		animatedSetting = animated;
	}

	public void setAnimated(boolean animated) {
		animatedSetting = animated;
		syncRunning();
	}

	/** Covered by something opaque (the Home arcade): stop animating until uncovered. */
	public void setSuspended(boolean suspended) {
		this.suspended = suspended;
		syncRunning();
	}

	private boolean suspended;

	public void setTheme(Color accent, Color background) {
		this.accent = accent;
		this.background = background;
		builtW = 0;
		requestLayout();
	}

	/** Who stands on the front ridge (the flat player); null for nobody. */
	public void setPlayer(Node player) {
		this.player = player;
		builtW = 0;
		requestLayout();
	}

	@Override
	protected void layoutChildren() {
		double w = getWidth();
		double h = getHeight();
		if (w < 10 || h < 10) {
			return;
		}
		if (builtW == 0) {
			rebuild();
		} else if (Math.abs(w - builtW) > 1 || Math.abs(h - builtH) > 1) {
			rebuildLater.playFromStart();
		}
	}

	private void watchWindow(javafx.stage.Window window) {
		window.showingProperty().addListener((o, a, showing) -> syncRunning());
		if (window instanceof javafx.stage.Stage s) {
			s.iconifiedProperty().addListener((o, a, iconified) -> syncRunning());
		}
		syncRunning();
	}

	private boolean shouldRun() {
		if (suspended || !animatedSetting || getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing()) {
			return false;
		}
		return !(getScene().getWindow() instanceof javafx.stage.Stage s && s.isIconified());
	}

	private void syncRunning() {
		boolean run = shouldRun();
		if (run == running) {
			return;
		}
		running = run;
		for (Animation animation : animations) {
			if (run) {
				animation.play();
			} else {
				animation.pause();
			}
		}
	}

	private void stopAll() {
		for (Animation animation : animations) {
			animation.stop();
		}
		running = false;
	}

	// ------------------------------------------------------------------ building

	private void rebuild() {
		double w = getWidth();
		double h = getHeight();
		if (w < 10 || h < 10) {
			return;
		}
		builtW = w;
		builtH = h;
		stopAll();
		animations.clear();
		stage.getChildren().clear();
		stage.resize(w, h);

		Rectangle sky = new Rectangle(w, h);
		sky.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, mix(background, Color.BLACK, 0.15)),
				new Stop(0.36, mix(background, accent, 0.03)),
				new Stop(0.58, mix(background, accent, 0.26)),
				new Stop(0.76, mix(background, accent, 0.07)),
				new Stop(1, mix(background, Color.BLACK, 0.1))));
		stage.getChildren().add(sky);

		SplittableRandom random = new SplittableRandom(3);
		for (int i = 0; i < 40; i++) {
			double size = random.nextDouble() < 0.25 ? 2 : 1;
			Rectangle star = new Rectangle(Math.round(random.nextDouble() * w), Math.round(random.nextDouble() * h * 0.46), size, size);
			star.setFill(Color.web("#F4E6E2"));
			double base = 0.2 + random.nextDouble() * 0.45;
			star.setOpacity(base);
			stage.getChildren().add(star);
			if (random.nextDouble() < 0.45) {
				FadeTransition twinkle = new FadeTransition(Duration.seconds(2.5 + random.nextDouble() * 4), star);
				twinkle.setFromValue(0.12);
				twinkle.setToValue(0.8);
				twinkle.setAutoReverse(true);
				twinkle.setCycleCount(Animation.INDEFINITE);
				twinkle.setDelay(Duration.seconds(random.nextDouble() * 6));
				animations.add(twinkle);
			}
		}

		stage.getChildren().add(moon(w, h));
		stage.getChildren().add(meteor(w, h));
		stage.getChildren().add(clouds(w, h));

		// Far ridges sit lighter (haze), each nearer one darker - enough steps apart to read against the glow.
		double[][] layers = {{0.527, 0.114, 4, 7, 0.36, 260}, {0.618, 0.10, 6, 19, 0.21, 180}, {0.723, 0.077, 9, 31, 0.11, 120}};
		for (double[] layer : layers) {
			Group drifting = new Group(ridge(w, h, layer[0] * h, layer[1] * h, layer[2], (long) layer[3], 2, null,
					mix(background, accent, layer[4])));
			drifting.setCache(true);
			drifting.setCacheHint(CacheHint.SPEED);
			stage.getChildren().add(drifting);
			animations.add(drift(drifting, w, layer[5]));
		}

		Region mist = new Region();
		mist.resizeRelocate(0, h * 0.57, w, h * 0.25);
		mist.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(new LinearGradient(0, 0, 0, 1, true,
				CycleMethod.NO_CYCLE, new Stop(0, Color.TRANSPARENT), new Stop(0.5, mix(background, accent, 0.4).deriveColor(0, 1, 1, 0.28)),
				new Stop(1, Color.TRANSPARENT)), null, null)));
		stage.getChildren().add(mist);
		FadeTransition breathe = new FadeTransition(Duration.seconds(16), mist);
		breathe.setFromValue(0.45);
		breathe.setToValue(0.9);
		breathe.setAutoReverse(true);
		breathe.setCycleCount(Animation.INDEFINITE);
		animations.add(breathe);

		double flatY = Math.round(h * 0.79 / 12) * 12;
		// The front ridge carries a thin warm top edge (lit by the horizon), so the plateau the player stands on reads.
		Polygon front = ridge(w, h, h * 0.864, h * 0.055, 12, 44, 1, new double[] {0.66, 0.80, flatY}, mix(background, Color.BLACK, 0.12));
		front.setStroke(mix(background, accent, 0.42).deriveColor(0, 1, 1, 0.75));
		front.setStrokeWidth(1);
		front.setStrokeType(javafx.scene.shape.StrokeType.INSIDE);
		stage.getChildren().add(front);

		if (player != null) {
			double pw = player.prefWidth(-1);
			double ph = player.prefHeight(-1);
			player.relocate(Math.round(w * 0.735 - pw / 2), flatY - ph);
			stage.getChildren().add(player);
			TranslateTransition bob = new TranslateTransition(Duration.seconds(1.7), player);
			bob.setFromY(0);
			bob.setToY(-1.5);
			bob.setAutoReverse(true);
			bob.setCycleCount(Animation.INDEFINITE);
			bob.setInterpolator(Interpolator.EASE_BOTH);
			animations.add(bob);
		}

		Rectangle grainRect = new Rectangle(w, h);
		grainRect.setFill(new ImagePattern(grainTile(), 0, 0, 128, 128, false));
		grainRect.setOpacity(0.05);
		stage.getChildren().add(grainRect);

		running = false;
		syncRunning();
	}

	/** A blocky ridge: one seamless period (integer wave counts over {@code w}), drawn {@code reps} times. */
	private static Polygon ridge(double w, double h, double base, double amp, double block, long seed, int reps, double[] flat, Color fill) {
		SplittableRandom r = new SplittableRandom(seed);
		double a = r.nextDouble() * 6.28, b = r.nextDouble() * 6.28, c = r.nextDouble() * 6.28, t2 = Math.PI * 2;
		List<double[]> steps = new ArrayList<>();
		double x = 0;
		while (x < w) {
			double step = Math.min(block * (1 + r.nextInt(3)), w - x);
			double t = x / w;
			double y = base - amp * (0.55 * Math.sin(t2 * t + a) + 0.3 * Math.sin(t2 * 3 * t + b) + 0.15 * Math.sin(t2 * 8 * t + c));
			if (flat != null && t >= flat[0] && t <= flat[1]) {
				y = flat[2];
			}
			steps.add(new double[] {x, step, Math.round(y / block) * block});
			x += step;
		}
		Polygon polygon = new Polygon();
		List<Double> points = polygon.getPoints();
		points.addAll(List.of(-2.0, h + 2));
		for (int k = 0; k < reps; k++) {
			for (double[] s : steps) {
				double sx = s[0] + k * w;
				points.addAll(List.of(sx, s[2], sx + s[1], s[2]));
			}
		}
		points.addAll(List.of(w * reps + 2, h + 2));
		polygon.setFill(fill);
		return polygon;
	}

	private static Animation drift(Node node, double w, double seconds) {
		TranslateTransition drift = new TranslateTransition(Duration.seconds(seconds * Math.max(0.6, w / 792)), node);
		drift.setFromX(0);
		drift.setToX(-w);
		drift.setInterpolator(Interpolator.LINEAR);
		drift.setCycleCount(Animation.INDEFINITE);
		return drift;
	}

	private Node moon(double w, double h) {
		double x = Math.round(w * 0.81);
		double y = Math.round(h * 0.12);
		Circle glow = new Circle(12, 12, 72);
		glow.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
				new Stop(0, mix(accent, Color.WHITE, 0.3).deriveColor(0, 1, 1, 0.16)), new Stop(1, Color.TRANSPARENT)));
		Rectangle disc = new Rectangle(24, 24, Color.web("#E8D9D2"));
		disc.setOpacity(0.88);
		Rectangle crater1 = new Rectangle(5, 6, 5, 5);
		crater1.setFill(Color.web("#CDB8AF"));
		Rectangle crater2 = new Rectangle(14, 13, 4, 4);
		crater2.setFill(Color.web("#CDB8AF"));
		Group moon = new Group(glow, disc, crater1, crater2);
		moon.setLayoutX(x);
		moon.setLayoutY(y);
		FadeTransition pulse = new FadeTransition(Duration.seconds(9), glow);
		pulse.setFromValue(0.55);
		pulse.setToValue(1);
		pulse.setAutoReverse(true);
		pulse.setCycleCount(Animation.INDEFINITE);
		animations.add(pulse);
		TranslateTransition wander = new TranslateTransition(Duration.seconds(120), moon);
		wander.setToX(-140);
		wander.setToY(22);
		wander.setAutoReverse(true);
		wander.setCycleCount(Animation.INDEFINITE);
		wander.setInterpolator(Interpolator.EASE_BOTH);
		animations.add(wander);
		return moon;
	}

	private Node meteor(double w, double h) {
		Line streak = new Line(0, 0, 70, 0);
		streak.setStroke(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.TRANSPARENT), new Stop(1, Color.web("#FFECE6", 0.85))));
		streak.setRotate(-24);
		streak.setLayoutX(w * 0.66);
		streak.setLayoutY(h * 0.09);
		streak.setOpacity(0);
		Timeline fly = new Timeline(
				new KeyFrame(Duration.ZERO, new KeyValue(streak.opacityProperty(), 0), new KeyValue(streak.translateXProperty(), 0),
						new KeyValue(streak.translateYProperty(), 0)),
				new KeyFrame(Duration.seconds(21.6), new KeyValue(streak.opacityProperty(), 0), new KeyValue(streak.translateXProperty(), 0),
						new KeyValue(streak.translateYProperty(), 0)),
				new KeyFrame(Duration.seconds(21.9), new KeyValue(streak.opacityProperty(), 1)),
				new KeyFrame(Duration.seconds(23), new KeyValue(streak.opacityProperty(), 0),
						new KeyValue(streak.translateXProperty(), -180, Interpolator.EASE_OUT),
						new KeyValue(streak.translateYProperty(), 80, Interpolator.EASE_OUT)));
		fly.setCycleCount(Animation.INDEFINITE);
		animations.add(fly);
		return streak;
	}

	private Node clouds(double w, double h) {
		Group group = new Group();
		SplittableRandom r = new SplittableRandom(11);
		for (int i = 0; i < Math.max(5, (int) (w / 115)); i++) {
			double cx = Math.round(r.nextDouble() * (w - 80) / 8) * 8;
			double cy = Math.round((h * 0.18 + r.nextDouble() * h * 0.16) / 8) * 8;
			int n = 2 + r.nextInt(3);
			Color fill = Color.web("#FFD6CE", 0.035 + r.nextDouble() * 0.03);
			for (int k = 0; k < 2; k++) {
				Rectangle body = new Rectangle(cx + k * w, cy, 48 + n * 16, 8);
				body.setFill(fill);
				Rectangle top = new Rectangle(cx + k * w + 16, cy - 8, 16 + n * 8, 8);
				top.setFill(fill);
				group.getChildren().addAll(body, top);
			}
		}
		group.setCache(true);
		group.setCacheHint(CacheHint.SPEED);
		animations.add(drift(group, w, 420));
		return group;
	}

	private static WritableImage grainTile;

	private static WritableImage grainTile() {
		if (grainTile == null) {
			int size = 128;
			int[] pixels = new int[size * size];
			SplittableRandom r = new SplittableRandom(5);
			for (int i = 0; i < pixels.length; i++) {
				int v = r.nextInt(256);
				pixels[i] = 0xFF000000 | v << 16 | v << 8 | v;
			}
			grainTile = new WritableImage(size, size);
			grainTile.getPixelWriter().setPixels(0, 0, size, size, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, size);
		}
		return grainTile;
	}

	private static Color mix(Color a, Color b, double t) {
		return new Color(a.getRed() + (b.getRed() - a.getRed()) * t, a.getGreen() + (b.getGreen() - a.getGreen()) * t,
				a.getBlue() + (b.getBlue() - a.getBlue()) * t, 1);
	}
}
