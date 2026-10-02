package net.veloclient.launcher.ui;

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

	/** Fades a freshly shown page in while it rises a few pixels - used for every page switch. */
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
