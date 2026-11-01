package net.veloclient.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;
import net.veloclient.launcher.theme.LauncherTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The startup splash: the window opens on it straight away while Home is still being built behind it. A short intro
 * (logo pops in, orbit rings draw, the wordmark slides in letter by letter) plays while the class preloader warms up;
 * then Home is built (the splash holds its final pose meanwhile) and {@link #reveal} zooms the logo through the screen
 * into the launcher. The splash ignores the mouse once the reveal starts, so the launcher is usable right away.
 *
 * <p>No effect on a container here or on the launcher below (see the JavaFX whole-node effect bug) - only the logo has
 * a glow.
 */
public final class LauncherSplash extends StackPane {

	private static final Interpolator EASE_IN = Interpolator.SPLINE(0.55, 0, 0.75, 0.2);
	/** Ease-out with a little overshoot (SPLINE only takes control points inside 0..1). */
	private static final Interpolator BACK_OUT = new Interpolator() {
		@Override
		protected double curve(double t) {
			double c = 1.7;
			double u = t - 1;
			return 1 + (c + 1) * u * u * u + c * u * u;
		}
	};
	private static final int LOGO = 120;

	private final Circle glow = new Circle(150);
	private final ImageView logo;
	private final Group rings = new Group();
	private final HBox word = new HBox(5);
	private final Text version;
	private final List<Animation> idle = new ArrayList<>();

	public LauncherSplash(LauncherTheme theme, Image logoImage, String versionText) {
		getStyleClass().add("no-motion");
		Color accent = color(theme.accentStart());
		Color accentEnd = color(theme.accentEnd());
		setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(theme.background() | 0xFF000000) + ";");

		glow.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
				new Stop(0, accent.deriveColor(0, 1, 1, 0.42)), new Stop(0.45, accent.deriveColor(0, 1, 1, 0.12)), new Stop(1, Color.TRANSPARENT)));
		glow.setMouseTransparent(true);

		// An invisible full circle keeps the group's bounds centred on the rings, so they spin in place.
		Circle bounds = new Circle(0, 0, 110, Color.TRANSPARENT);
		rings.getChildren().add(bounds);
		rings.getChildren().addAll(ring(92, 0, 250, accent, 2.4), ring(92, 200, 70, accentEnd.deriveColor(0, 1, 1, 0.55), 2.4),
				ring(108, 40, 110, accent.deriveColor(0, 1, 1, 0.35), 1.2), ring(108, 220, 60, accent.deriveColor(0, 1, 1, 0.22), 1.2));

		logo = new ImageView(logoImage);
		logo.setFitWidth(LOGO);
		logo.setFitHeight(LOGO);
		logo.setSmooth(true);
		DropShadow shine = new DropShadow(28, accent.deriveColor(0, 1, 1, 0.65));
		shine.setSpread(0.12);
		logo.setEffect(shine);

		StackPane mark = new StackPane(glow, rings, logo);
		mark.setMinSize(240, 240);
		mark.setMaxSize(240, 240);

		for (char c : "VELO".toCharArray()) {
			Text letter = new Text(String.valueOf(c));
			letter.setFont(Font.font("Audiowide", 34));
			letter.setFill(Color.WHITE);
			word.getChildren().add(letter);
		}
		Text client = new Text("CLIENT");
		client.setFont(Font.font("Sora", FontWeight.BOLD, 13));
		client.setFill(accent.deriveColor(0, 1, 1.15, 1));
		HBox.setMargin(client, new javafx.geometry.Insets(17, 0, 0, 8));
		word.getChildren().add(client);
		word.setAlignment(javafx.geometry.Pos.CENTER);

		version = new Text(versionText);
		version.setFont(Font.font("JetBrains Mono", 11));
		version.setFill(Color.rgb(255, 255, 255, 0.38));

		VBox column = new VBox(10, mark, word, version);
		column.setAlignment(javafx.geometry.Pos.CENTER);
		column.setMouseTransparent(true);
		getChildren().add(column);
	}

	private static Arc ring(double radius, double start, double length, Color color, double width) {
		Arc arc = new Arc(0, 0, radius, radius, start, length);
		arc.setType(ArcType.OPEN);
		arc.setFill(null);
		arc.setStroke(color);
		arc.setStrokeWidth(width);
		arc.setStrokeLineCap(StrokeLineCap.ROUND);
		return arc;
	}

	private static Color color(int argb) {
		return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
	}

	/**
	 * Plays the intro, then runs {@code build} once its last frame is on screen (the heavy part - the splash holds
	 * still meanwhile, so it must already look finished).
	 */
	public void intro(Runnable build) {
		logo.setScaleX(0.78);
		logo.setScaleY(0.78);
		logo.setOpacity(0);
		rings.setScaleX(0.6);
		rings.setScaleY(0.6);
		rings.setOpacity(0);
		glow.setOpacity(0);
		version.setOpacity(0);

		ScaleTransition pop = new ScaleTransition(Duration.millis(280), logo);
		pop.setToX(1);
		pop.setToY(1);
		pop.setInterpolator(BACK_OUT);
		FadeTransition logoIn = fade(logo, 1, 180, 0);
		ScaleTransition ringsOut = new ScaleTransition(Duration.millis(320), rings);
		ringsOut.setToX(1);
		ringsOut.setToY(1);
		ringsOut.setInterpolator(UiMotion.EASE_OUT);
		ParallelTransition all = new ParallelTransition(pop, logoIn, ringsOut, fade(rings, 1, 260, 0), fade(glow, 1, 300, 0),
				fade(version, 1, 220, 120));
		for (int i = 0; i < word.getChildren().size(); i++) {
			Node letter = word.getChildren().get(i);
			letter.setOpacity(0);
			letter.setTranslateY(10);
			TranslateTransition rise = new TranslateTransition(Duration.millis(200), letter);
			rise.setToY(0);
			rise.setDelay(Duration.millis(30 + i * 28));
			rise.setInterpolator(UiMotion.EASE_OUT);
			all.getChildren().addAll(rise, fade(letter, 1, 180, 30 + i * 28));
		}
		startIdle();
		// Two pulses after the intro so its final frame is really painted before the build blocks the FX thread.
		PauseTransition settle = new PauseTransition(Duration.millis(34));
		SequentialTransition sequence = new SequentialTransition(all, settle);
		sequence.setOnFinished(e -> build.run());
		sequence.play();
	}

	private void startIdle() {
		RotateTransition spin = new RotateTransition(Duration.seconds(2.6), rings);
		spin.setByAngle(360);
		spin.setCycleCount(Animation.INDEFINITE);
		spin.setInterpolator(Interpolator.LINEAR);
		ScaleTransition breathe = new ScaleTransition(Duration.millis(1400), glow);
		breathe.setFromX(0.92);
		breathe.setFromY(0.92);
		breathe.setToX(1.08);
		breathe.setToY(1.08);
		breathe.setAutoReverse(true);
		breathe.setCycleCount(Animation.INDEFINITE);
		breathe.setInterpolator(Interpolator.EASE_BOTH);
		idle.add(spin);
		idle.add(breathe);
		idle.forEach(Animation::play);
	}

	/** Zooms the logo through the screen and fades the launcher ({@code content}) in under it; {@code done} removes the splash. */
	public void reveal(Node content, Runnable done) {
		setMouseTransparent(true);
		ScaleTransition logoZoom = scale(logo, 1.5, 380, EASE_IN);
		ScaleTransition ringsBurst = scale(rings, 2.6, 460, UiMotion.EASE_OUT);
		ScaleTransition glowFlash = scale(glow, 3.2, 460, UiMotion.EASE_OUT);
		TranslateTransition wordDrop = new TranslateTransition(Duration.millis(260), word);
		wordDrop.setToY(14);
		wordDrop.setInterpolator(EASE_IN);
		ParallelTransition all = new ParallelTransition(logoZoom, fade(logo, 0, 300, 80), ringsBurst, fade(rings, 0, 380, 0), glowFlash,
				fade(glow, 0, 460, 0), wordDrop, fade(word, 0, 200, 0), fade(version, 0, 160, 0), fade(this, 0, 300, 200));
		// The launcher comes in piece by piece as the logo bursts: the top bar drops in, the launch bar rises, and the
		// page's blocks slide up one after another.
		List<Node> moved = new ArrayList<>();
		content.setOpacity(1);
		if (content instanceof javafx.scene.layout.BorderPane frame) {
			slideIn(all, moved, frame.getTop(), 0, -22, 140);
			slideIn(all, moved, frame.getBottom(), 0, 34, 220);
			int delay = 180;
			for (Node block : blocks(frame.getCenter())) {
				slideIn(all, moved, block, 0, 30, delay);
				delay += 70;
			}
		} else {
			content.setOpacity(0);
			content.setScaleX(0.965);
			content.setScaleY(0.965);
			ScaleTransition contentIn = scale(content, 1, 480, UiMotion.EASE_OUT);
			contentIn.setDelay(Duration.millis(120));
			all.getChildren().addAll(contentIn, fade(content, 1, 360, 120));
		}
		all.setOnFinished(e -> {
			idle.forEach(Animation::stop);
			for (Node node : moved) {
				node.setTranslateX(0);
				node.setTranslateY(0);
				node.setOpacity(1);
			}
			content.setScaleX(1);
			content.setScaleY(1);
			content.setOpacity(1);
			done.run();
		});
		all.play();
	}

	/** Starts {@code node} offset by ({@code dx}, {@code dy}) and see-through, and adds its glide home to {@code all}. */
	private static void slideIn(ParallelTransition all, List<Node> moved, Node node, double dx, double dy, int delay) {
		if (node == null) {
			return;
		}
		moved.add(node);
		node.setOpacity(0);
		node.setTranslateX(dx);
		node.setTranslateY(dy);
		TranslateTransition glide = new TranslateTransition(Duration.millis(560), node);
		glide.setToX(0);
		glide.setToY(0);
		glide.setInterpolator(BACK_OUT);
		glide.setDelay(Duration.millis(delay));
		all.getChildren().addAll(glide, fade(node, 1, 380, delay));
	}

	/**
	 * The visible blocks of a page, top to bottom, for the staggered slide-in: through a scroll pane and the page's
	 * column, one level into a column that groups several blocks (Home's first screen). At most six; anything further down is
	 * below the fold and simply there.
	 */
	private static List<Node> blocks(Node center) {
		List<Node> out = new ArrayList<>();
		Node page = center;
		if (page instanceof StackPane stack && stack.getChildren().size() == 1) {
			page = stack.getChildren().get(0);
		}
		if (page instanceof javafx.scene.control.ScrollPane scroll) {
			page = scroll.getContent();
		}
		if (!(page instanceof VBox column) || column.getChildren().isEmpty()) {
			if (center != null) {
				out.add(center);
			}
			return out;
		}
		for (Node child : column.getChildren()) {
			if (child instanceof VBox group && group.getChildren().size() >= 2) {
				out.addAll(group.getChildren());
			} else {
				out.add(child);
			}
		}
		return out.size() > 6 ? new ArrayList<>(out.subList(0, 6)) : out;
	}

	private static FadeTransition fade(Node node, double to, int millis, int delay) {
		FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
		fade.setToValue(to);
		fade.setDelay(Duration.millis(delay));
		return fade;
	}

	private static ScaleTransition scale(Node node, double to, int millis, Interpolator interpolator) {
		ScaleTransition scale = new ScaleTransition(Duration.millis(millis), node);
		scale.setToX(to);
		scale.setToY(to);
		scale.setInterpolator(interpolator);
		return scale;
	}
}
