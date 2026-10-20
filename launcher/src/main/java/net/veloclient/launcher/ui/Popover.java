package net.veloclient.launcher.ui;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * A floating card anchored to a node (profile switcher, notifications, account menu). Closes on outside click or
 * Esc. The popup is its own scene, so it copies the window's stylesheets and theme colors to look the same.
 */
public final class Popover {

	public enum Side { BELOW_LEFT, BELOW_RIGHT, ABOVE_LEFT, ABOVE_RIGHT }

	private Popover() {
	}

	/** The one popover open right now (a new one replaces it) and what opened it. */
	private static Popup current;
	private static Node currentAnchor;
	private static long closedAt;
	private static Node closedAnchor;

	/**
	 * Shows {@code content} inside a popover card; returns the popup so callers can hide it after an action.
	 * Only one is ever open: clicking the button that opened it closes it again instead of stacking another.
	 */
	public static Popup show(Node anchor, Node content, Side side, double width) {
		// The press on the anchor already auto-hid the open popover - that click means "close", not "open again".
		boolean justClosedHere = anchor == closedAnchor && System.currentTimeMillis() - closedAt < 300;
		if (current != null && current.isShowing()) {
			boolean same = currentAnchor == anchor;
			current.hide();
			if (same) {
				return new Popup();
			}
		}
		if (justClosedHere) {
			closedAnchor = null;
			return new Popup();
		}
		VBox card = new VBox(content);
		card.getStyleClass().add("popover");
		if (width > 0) {
			card.setPrefWidth(width);
			card.setMaxWidth(width);
		}
		Popup popup = new Popup();
		current = popup;
		currentAnchor = anchor;
		popup.setOnHidden(e -> {
			closedAt = System.currentTimeMillis();
			closedAnchor = anchor;
			if (current == popup) {
				current = null;
				currentAnchor = null;
			}
		});
		popup.setAutoHide(true);
		popup.setHideOnEscape(true);
		popup.setAutoFix(true);
		popup.getContent().add(card);
		if (anchor.getScene() != null) {
			popup.getScene().getStylesheets().setAll(anchor.getScene().getStylesheets());
			Parent root = anchor.getScene().getRoot();
			card.setStyle(root.getStyle().replace("-fx-background-color: -velo-background;", ""));
		}
		card.getStyleClass().add("root");
		card.getStyleClass().add("popover");
		Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
		if (b == null) {
			return popup;
		}
		popup.show(anchor, b.getMinX(), b.getMaxY() + 8);
		card.applyCss();
		card.layout();
		double w = card.prefWidth(-1);
		double h = card.prefHeight(w);
		if (card instanceof Region region && region.getWidth() > 0) {
			w = region.getWidth();
			h = region.getHeight();
		}
		double x = switch (side) {
			case BELOW_LEFT, ABOVE_LEFT -> b.getMinX();
			case BELOW_RIGHT, ABOVE_RIGHT -> b.getMaxX() - w;
		};
		double y = switch (side) {
			case BELOW_LEFT, BELOW_RIGHT -> b.getMaxY() + 8;
			case ABOVE_LEFT, ABOVE_RIGHT -> b.getMinY() - h - 8;
		};
		popup.setX(x);
		popup.setY(y);
		UiMotion.enter(card);
		return popup;
	}
}
