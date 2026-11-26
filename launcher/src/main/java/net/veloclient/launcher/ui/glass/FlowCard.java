package net.veloclient.launcher.ui.glass;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.Locale;

/**
 * Home's play card in a flowing shape (2026-11-25, owner reference): a narrower tab on top (the profile row) that runs
 * into the wider card below through soft inner curves, instead of one plain rectangle. Frosted like every glass panel
 * (the blurred backdrop clipped to the outline) with a thin light edge drawn along the same outline.
 */
public final class FlowCard extends Pane {

	/** How far the tab sits in from the card's sides: exactly the bottom corner plus the inner curve, so they meet cleanly. */
	private static final double R_BOTTOM = 18;
	private static final double R_FLOW = 18;
	private static final double INSET = R_BOTTOM + R_FLOW;
	private static final double R_TAB = 22;
	private static final double R_BODY = 30;

	private final VBox body = new VBox(10);
	private final VBox tab = new VBox();
	private final VBox column = new VBox(0, body, tab);
	private final ImageView frost = new ImageView();
	private final SVGPath clip = new SVGPath();
	private final SVGPath edge = new SVGPath();

	/**
	 * {@code main}: the full-width part on top (profile row and Play - never narrower than anything under it);
	 * {@code tab}: the narrower part that flows out underneath (the "launch into" row), or null for a plain card.
	 */
	public FlowCard(Node tabContent, Node... main) {
		getStyleClass().add("flow-card");
		body.getChildren().addAll(main);
		body.setPadding(new Insets(14, 14, 8, 14));
		if (tabContent != null) {
			tab.getChildren().add(tabContent);
		}
		tab.setPadding(new Insets(6, INSET + 8, 12, INSET + 8));
		frost.setManaged(false);
		frost.setMouseTransparent(true);
		frost.setSmooth(true);
		frost.setClip(clip);
		edge.setManaged(false);
		edge.setMouseTransparent(true);
		edge.setFill(Color.TRANSPARENT);
		edge.setStroke(Color.rgb(255, 255, 255, 0.14));
		edge.setStrokeWidth(1);
		edge.getStyleClass().add("flow-card-edge");
		getChildren().addAll(frost, column, edge);
		Backdrop backdrop = Backdrop.current();
		javafx.beans.InvalidationListener relayout = o -> placeFrost();
		localToSceneTransformProperty().addListener(relayout);
		sceneProperty().addListener(relayout);
		if (backdrop != null) {
			backdrop.geometryProperty().addListener(new javafx.beans.WeakInvalidationListener(relayout));
			getProperties().put("velo.flow.listener", relayout);
		}
	}

	@Override
	protected double computePrefWidth(double height) {
		return column.prefWidth(height);
	}

	@Override
	protected double computePrefHeight(double width) {
		return column.prefHeight(width);
	}

	@Override
	protected double computeMinHeight(double width) {
		return column.minHeight(width);
	}

	@Override
	protected void layoutChildren() {
		double w = getWidth(), h = getHeight();
		column.resizeRelocate(0, 0, w, h);
		column.layout();
		double mainH = body.getHeight();
		String outline = tab.getChildren().isEmpty() ? outline(w, h, h) : outline(w, h, mainH);
		clip.setContent(outline);
		edge.setContent(outline);
		frost.relocate(0, 0);
		frost.setFitWidth(w);
		frost.setFitHeight(h);
		placeFrost();
	}

	/** Full-width body (0..mainH) with the narrower tab flowing out below it through concave curves. */
	private static String outline(double w, double h, double mainH) {
		double i = INSET;
		if (mainH >= h - 1) {
			return String.format(Locale.ROOT, "M %1$.1f 0 H %2$.1f A %1$.1f %1$.1f 0 0 1 %3$.1f %1$.1f V %4$.1f A %1$.1f %1$.1f 0 0 1 %2$.1f %3$.1f"
					+ " H %1$.1f A %1$.1f %1$.1f 0 0 1 0 %4$.1f V %1$.1f A %1$.1f %1$.1f 0 0 1 %1$.1f 0 Z", R_BODY, w - R_BODY, w, h - R_BODY)
					.replace(String.format(Locale.ROOT, "%.1f 0 %.1f", R_BODY, w), String.format(Locale.ROOT, "%.1f 0 %.1f", R_BODY, w));
		}
		StringBuilder d = new StringBuilder();
		d.append(String.format(Locale.ROOT, "M %.1f 0 H %.1f ", R_BODY, w - R_BODY));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 1 %.1f %.1f ", R_BODY, R_BODY, w, R_BODY));
		d.append(String.format(Locale.ROOT, "V %.1f ", mainH - R_BOTTOM));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 1 %.1f %.1f ", R_BOTTOM, R_BOTTOM, w - R_BOTTOM, mainH));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 0 %.1f %.1f ", R_FLOW, R_FLOW, w - i, mainH + R_FLOW));
		d.append(String.format(Locale.ROOT, "V %.1f ", h - R_TAB));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 1 %.1f %.1f ", R_TAB, R_TAB, w - i - R_TAB, h));
		d.append(String.format(Locale.ROOT, "H %.1f ", i + R_TAB));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 1 %.1f %.1f ", R_TAB, R_TAB, i, h - R_TAB));
		d.append(String.format(Locale.ROOT, "V %.1f ", mainH + R_FLOW));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 0 %.1f %.1f ", R_FLOW, R_FLOW, i - R_FLOW, mainH));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 1 0 %.1f ", R_BOTTOM, R_BOTTOM, mainH - R_BOTTOM));
		d.append(String.format(Locale.ROOT, "V %.1f ", R_BODY));
		d.append(String.format(Locale.ROOT, "A %.1f %.1f 0 0 1 %.1f 0 Z", R_BODY, R_BODY, R_BODY));
		return d.toString();
	}

	private void placeFrost() {
		Backdrop backdrop = Backdrop.current();
		if (backdrop == null || backdrop.frostedProperty().get() == null || getScene() == null || getWidth() <= 0) {
			frost.setVisible(false);
			return;
		}
		if (frost.getImage() != backdrop.frostedProperty().get()) {
			frost.setImage(backdrop.frostedProperty().get());
		}
		Bounds scene = localToScene(getLayoutBounds());
		Bounds backdropScene = backdrop.localToScene(backdrop.getLayoutBounds());
		double[] m = backdrop.mapping();
		double sx = (scene.getMinX() - backdropScene.getMinX() - m[1]) / m[0] * m[3];
		double sy = (scene.getMinY() - backdropScene.getMinY() - m[2]) / m[0] * m[3];
		frost.setViewport(new Rectangle2D(sx, sy, Math.max(1, scene.getWidth() / m[0] * m[3]), Math.max(1, scene.getHeight() / m[0] * m[3])));
		frost.setVisible(true);
	}

	/** The card's own width is set by the caller (Home keeps it at about 500 px). */
	public Region asRegion() {
		return this;
	}
}
