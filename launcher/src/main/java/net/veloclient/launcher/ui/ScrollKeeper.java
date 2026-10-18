package net.veloclient.launcher.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps scroll positions when a page is rebuilt in place. Most pages refresh by building themselves again
 * (equip, select, edit, a background reload finishing ...), which used to throw every list back to the top.
 * The offsets are kept in pixels and matched to the new page's scroll panes in the same order; content that
 * grows after the rebuild (async loads) is followed until the player scrolls themselves.
 */
public final class ScrollKeeper {

	private record Offset(double y, double x) {
	}

	private ScrollKeeper() {
	}

	/** Replaces {@code parent}'s children with {@code content}, keeping the scroll positions. */
	public static void setAll(Pane parent, Node content) {
		List<Offset> saved = capture(parent);
		parent.getChildren().setAll(content);
		restore(content, saved);
	}

	/** True when {@code next} looks like the same page as {@code previous} (same kind of root, same heading). */
	public static boolean samePage(Node previous, Node next) {
		if (previous == null || next == null || previous.getClass() != next.getClass()) {
			return false;
		}
		String a = heading(previous);
		return a != null && a.equals(heading(next));
	}

	public static List<?> captureAll(Node root) {
		return capture(root);
	}

	@SuppressWarnings("unchecked")
	public static void restoreAll(Node root, List<?> saved) {
		restore(root, (List<Offset>) saved);
	}

	private static List<Offset> capture(Node root) {
		List<Offset> out = new ArrayList<>();
		for (ScrollPane pane : scrollPanes(root)) {
			out.add(new Offset(pixels(pane.getVvalue(), pane.getVmin(), pane.getVmax(), extra(pane, true)),
					pixels(pane.getHvalue(), pane.getHmin(), pane.getHmax(), extra(pane, false))));
		}
		return out;
	}

	private static void restore(Node root, List<Offset> saved) {
		if (saved.isEmpty() || saved.stream().allMatch(o -> o.y() < 1 && o.x() < 1)) {
			return;
		}
		// After the first layout pass, when the new content has a size.
		Platform.runLater(() -> {
			List<ScrollPane> panes = scrollPanes(root);
			for (int i = 0; i < Math.min(panes.size(), saved.size()); i++) {
				Offset offset = saved.get(i);
				if (offset.y() >= 1 || offset.x() >= 1) {
					follow(panes.get(i), offset);
				}
			}
		});
	}

	/** Applies the offset now and again while the content grows (lazy loads), until the player scrolls or 2.5s passed. */
	private static void follow(ScrollPane pane, Offset offset) {
		Runnable apply = () -> {
			double y = extra(pane, true);
			double x = extra(pane, false);
			if (y > 0) {
				pane.setVvalue(pane.getVmin() + Math.min(1, offset.y() / y) * (pane.getVmax() - pane.getVmin()));
			}
			if (x > 0) {
				pane.setHvalue(pane.getHmin() + Math.min(1, offset.x() / x) * (pane.getHmax() - pane.getHmin()));
			}
		};
		pane.applyCss();
		pane.layout();
		apply.run();
		Node content = pane.getContent();
		if (content == null) {
			return;
		}
		ChangeListener<Bounds> grow = (obs, was, now) -> apply.run();
		content.layoutBoundsProperty().addListener(grow);
		Runnable[] stop = new Runnable[1];
		javafx.event.EventHandler<ScrollEvent> onScroll = e -> stop[0].run();
		javafx.event.EventHandler<MouseEvent> onPress = e -> stop[0].run();
		stop[0] = () -> {
			content.layoutBoundsProperty().removeListener(grow);
			pane.removeEventFilter(ScrollEvent.SCROLL, onScroll);
			pane.removeEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
		};
		pane.addEventFilter(ScrollEvent.SCROLL, onScroll);
		pane.addEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
		PauseTransition timeout = new PauseTransition(Duration.millis(2500));
		timeout.setOnFinished(e -> stop[0].run());
		timeout.play();
	}

	/** How many pixels the content is larger than the viewport (the scrollable range). */
	private static double extra(ScrollPane pane, boolean vertical) {
		Node content = pane.getContent();
		if (content == null || pane.getViewportBounds() == null) {
			return 0;
		}
		Bounds bounds = content.getLayoutBounds();
		return vertical ? Math.max(0, bounds.getHeight() - pane.getViewportBounds().getHeight())
				: Math.max(0, bounds.getWidth() - pane.getViewportBounds().getWidth());
	}

	private static double pixels(double value, double min, double max, double range) {
		return max <= min ? 0 : (value - min) / (max - min) * range;
	}

	private static List<ScrollPane> scrollPanes(Node root) {
		List<ScrollPane> out = new ArrayList<>();
		collect(root, out);
		return out;
	}

	private static void collect(Node node, List<ScrollPane> out) {
		if (node instanceof ScrollPane pane) {
			out.add(pane);
			if (pane.getContent() != null) {
				collect(pane.getContent(), out);
			}
			return;
		}
		if (node instanceof Parent parent) {
			for (Node child : parent.getChildrenUnmodifiable()) {
				collect(child, out);
			}
		}
	}

	/** The first label of a page (its title) - breadth-first, so headings win over deeper labels. */
	private static String heading(Node root) {
		java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
		queue.add(root);
		int visited = 0;
		while (!queue.isEmpty() && visited++ < 400) {
			Node node = queue.poll();
			if (node instanceof Label label && label.getText() != null && !label.getText().isBlank()) {
				return label.getText();
			}
			if (node instanceof ScrollPane pane && pane.getContent() != null) {
				queue.add(pane.getContent());
			} else if (node instanceof Parent parent) {
				queue.addAll(parent.getChildrenUnmodifiable());
			}
		}
		return null;
	}
}
