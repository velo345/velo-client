package net.veloclient.launcher.ui;

import java.util.List;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/**
 * Launcher-wide motion: every button (and dropdown, and anything tagged {@code .motion-card})
 * eases up slightly on hover and dips on press - JavaFX CSS has no transitions, so this is done
 * once with scene-level event filters instead of per control. Page changes get a short fade +
 * rise via {@link #enter}.
 *
 * Opt out per node with the {@code no-motion} style class (e.g. nodes that run their own scale
 * animation).
 */
public final class UiMotion {

	public static final Interpolator EASE_OUT = Interpolator.SPLINE(0.2, 0.8, 0.25, 1);
	/**
	 * Ease-out that overshoots a little and settles - for things that pop in. Interpolator.SPLINE can't do this:
	 * JavaFX throws for control points outside [0,1] (that exception blanked whole views).
	 */
	public static final Interpolator BACK_OUT = back(1.70158);
	/** A softer overshoot, for things that slide. */
	public static final Interpolator BACK_OUT_SOFT = back(0.9);

	private static Interpolator back(double s) {
		return new Interpolator() {
			@Override
			protected double curve(double t) {
				double u = t - 1;
				return 1 + u * u * ((s + 1) * u + s);
			}
		};
	}
	private static final String KEY = "velo.motion";

	private UiMotion() {
	}

	public static void install(Scene scene) {
		scene.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, e -> {
			Node target = motionTarget(e.getTarget());
			if (target != null && e.getTarget() == target) {
				scaleTo(target, hoverScale(target), 140);
			}
		});
		scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
			Node target = motionTarget(e.getTarget());
			if (target != null && e.getTarget() == target) {
				scaleTo(target, 1.0, 180);
			}
		});
		scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
			Node target = motionTarget(e.getTarget());
			if (target != null && !target.isDisabled()) {
				scaleTo(target, 0.955, 70);
			}
		});
		scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
			Node target = motionTarget(e.getTarget());
			if (target != null) {
				scaleTo(target, target.isHover() ? hoverScale(target) : 1.0, 160);
			}
		});
	}

	/** Small elements lift a bit more than wide ones, so the motion reads the same everywhere. */
	private static double hoverScale(Node node) {
		double width = node.getLayoutBounds().getWidth();
		if (node.getStyleClass().contains("motion-card")) {
			return 1.012;
		}
		return width > 260 ? 1.012 : width > 120 ? 1.025 : 1.05;
	}

	private static Node motionTarget(Object target) {
		if (!(target instanceof Node node)) {
			return null;
		}
		for (Node n = node; n != null; n = n.getParent()) {
			if (n instanceof ScrollBar) {
				return null;
			}
			if (n.getStyleClass().contains("no-motion")) {
				return null;
			}
			if (n instanceof ButtonBase || n instanceof ComboBoxBase<?> || n.getStyleClass().contains("motion-card")) {
				return n.isDisabled() ? null : n;
			}
		}
		return null;
	}

	private static void scaleTo(Node node, double scale, int millis) {
		Object running = node.getProperties().get(KEY);
		if (running instanceof ScaleTransition transition) {
			transition.stop();
		}
		ScaleTransition transition = new ScaleTransition(Duration.millis(millis), node);
		transition.setToX(scale);
		transition.setToY(scale);
		transition.setInterpolator(EASE_OUT);
		node.getProperties().put(KEY, transition);
		transition.play();
	}

	/** Panel-like pieces of a page that get the staggered entrance (cards, groups, rows of tiles...). */
	private static final java.util.Set<String> STAGGERED = java.util.Set.of("card", "card-2", "server-card", "friend-group", "partner-card",
			"aside-card", "store-cat", "tile", "locker-cell", "locker-cell-worn", "mod-table", "settings-card", "search-card", "news-card",
			"update-point", "skeleton-card", "locker-slot", "locker-slot-active", "profile-banner", "dashed-tile");

	/**
	 * 2026-11: after a page switch its panels slide up a little and fade in one after another (35 ms apart, at most 24),
	 * so the page builds itself instead of popping. Pieces holding a 3D view are skipped (opacity can blank a SubScene).
	 */
	public static void stagger(Node page) {
		javafx.application.Platform.runLater(() -> {
			List<Node> found = new java.util.ArrayList<>();
			collect(page, found);
			int i = 0;
			for (Node node : found) {
				if (i >= 24) {
					break;
				}
				node.setOpacity(0);
				node.setTranslateY(14);
				FadeTransition fade = new FadeTransition(Duration.millis(240), node);
				fade.setToValue(1);
				TranslateTransition rise = new TranslateTransition(Duration.millis(320), node);
				rise.setToY(0);
				rise.setInterpolator(EASE_OUT);
				ParallelTransition both = new ParallelTransition(fade, rise);
				both.setDelay(Duration.millis(40 + i * 35L));
				both.play();
				i++;
			}
		});
	}

	private static void collect(Node node, List<Node> out) {
		if (out.size() >= 24 || node instanceof javafx.scene.SubScene) {
			return;
		}
		boolean match = node.getStyleClass().stream().anyMatch(STAGGERED::contains);
		if (match && !holds3d(node, 0) && node.isVisible() && node.getOpacity() > 0.99) {
			out.add(node);
			return;
		}
		if (node instanceof javafx.scene.Parent parent) {
			for (Node child : parent.getChildrenUnmodifiable()) {
				collect(child, out);
			}
		}
	}

	private static boolean holds3d(Node node, int depth) {
		if (node instanceof javafx.scene.SubScene) {
			return true;
		}
		if (depth > 12 || !(node instanceof javafx.scene.Parent parent)) {
			return false;
		}
		for (Node child : parent.getChildrenUnmodifiable()) {
			if (holds3d(child, depth + 1)) {
				return true;
			}
		}
		return false;
	}

	/** Fades a freshly shown page in while it rises a few pixels - used for every page switch. */
	/**
	 * Page change (2026-11, sidebar): the new page comes from the direction of the row you picked - {@code direction}
	 * 1 = a row further down (rises from below), -1 = further up (drops from above) - with a short fade and a barely
	 * visible scale, so the switch feels physical without slowing you down.
	 */
	public static void enterFrom(Node node, int direction) {
		if (direction == 0) {
			enter(node);
			return;
		}
		node.setOpacity(0);
		node.setTranslateY(22 * direction);
		node.setScaleX(0.992);
		node.setScaleY(0.992);
		FadeTransition fade = new FadeTransition(Duration.millis(200), node);
		fade.setToValue(1);
		TranslateTransition slide = new TranslateTransition(Duration.millis(300), node);
		slide.setToY(0);
		slide.setInterpolator(EASE_OUT);
		ScaleTransition settle = new ScaleTransition(Duration.millis(300), node);
		settle.setToX(1);
		settle.setToY(1);
		settle.setInterpolator(EASE_OUT);
		new ParallelTransition(fade, slide, settle).play();
	}

	public static void enter(Node node) {
		node.setOpacity(0);
		node.setTranslateY(10);
		FadeTransition fade = new FadeTransition(Duration.millis(200), node);
		fade.setToValue(1);
		TranslateTransition rise = new TranslateTransition(Duration.millis(260), node);
		rise.setToY(0);
		rise.setInterpolator(EASE_OUT);
		new ParallelTransition(fade, rise).play();
	}

	/** Staggered entrance for the children of a list (cards, rows): each fades/rises slightly after the previous. */
	public static void stagger(Parent parent, int maxItems) {
		int index = 0;
		for (Node child : parent.getChildrenUnmodifiable()) {
			if (index >= maxItems) {
				break;
			}
			child.setOpacity(0);
			child.setTranslateY(8);
			FadeTransition fade = new FadeTransition(Duration.millis(180), child);
			fade.setToValue(1);
			fade.setDelay(Duration.millis(25L * index));
			TranslateTransition rise = new TranslateTransition(Duration.millis(240), child);
			rise.setToY(0);
			rise.setDelay(Duration.millis(25L * index));
			rise.setInterpolator(EASE_OUT);
			new ParallelTransition(fade, rise).play();
			index++;
		}
	}
}
