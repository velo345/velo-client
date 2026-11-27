package net.veloclient.launcher.ui.glass;

import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

import java.util.Set;

/**
 * Frosted glass for any {@link Pane} (2026-11-25): JavaFX has no backdrop-filter, so the panel shows the
 * {@link Backdrop}'s pre-blurred, tinted picture behind itself - an unmanaged {@link ImageView} under its children whose
 * viewport follows the panel's place in the window - plus a thin light edge on top. Every panel shares the one frosted
 * image; only the viewport differs, so it costs about as much as a plain background.
 */
public final class Glass {

	private static final String KEY = "velo.glass";

	/** Page pieces that become glass automatically when a page is shown. */
	private static final Set<String> AUTO = Set.of("card", "card-2", "server-card", "friend-group", "partner-card", "aside-card",
			"store-cat", "tile", "settings-card", "search-card", "news-card", "mod-table", "locker-slot",
			"locker-slot-active", "popover-card", "glass");

	private Glass() {
	}

	/** Makes {@code pane} glass with rounded corners of {@code radius} px (idempotent). */
	public static void install(Pane pane, double radius) {
		if (pane.getProperties().containsKey(KEY)) {
			return;
		}
		ImageView frost = new ImageView();
		frost.setManaged(false);
		frost.setMouseTransparent(true);
		frost.setSmooth(true);
		frost.setViewOrder(1000);
		frost.getStyleClass().add("glass-frost");
		Rectangle clip = new Rectangle();
		clip.setArcWidth(radius * 2);
		clip.setArcHeight(radius * 2);
		frost.setClip(clip);
		Region edge = new Region();
		edge.setManaged(false);
		edge.setMouseTransparent(true);
		edge.setViewOrder(-1000);
		edge.getStyleClass().add("glass-edge");
		edge.setStyle("-fx-border-radius: " + radius + "; -fx-background-radius: " + radius + ";");
		pane.getProperties().put(KEY, new Node[] {frost, edge});
		pane.getProperties().put(KEY + ".radius", radius);
		if (!pane.getStyleClass().contains("glass-on")) {
			pane.getStyleClass().add("glass-on");
		}

		Runnable update = () -> place(pane, frost, edge, clip);
		pane.getProperties().put(KEY + ".update", update);
		InvalidationListener relayout = o -> update.run();
		pane.layoutBoundsProperty().addListener(relayout);
		pane.localToSceneTransformProperty().addListener(relayout);
		pane.sceneProperty().addListener(relayout);
		pane.getProperties().put(KEY + ".listener", relayout);
		follow(pane, Backdrop.current());
		pane.getChildren().add(0, frost);
		pane.getChildren().add(edge);
		// a page that rebuilds its children (setAll) would drop the two layers: put them back
		pane.getChildren().addListener((ListChangeListener<Node>) change -> {
			if (!pane.getChildren().contains(frost) || !pane.getChildren().contains(edge)) {
				javafx.application.Platform.runLater(() -> {
					if (!pane.getChildren().contains(frost)) {
						pane.getChildren().add(0, frost);
					}
					if (!pane.getChildren().contains(edge)) {
						pane.getChildren().add(edge);
					}
				});
			}
		});
		update.run();
	}

	/**
	 * Re-places the panel whenever the backdrop's frosted picture or mapping changes (a new scene, glass darkness, theme
	 * tint). Hooked up lazily: the side rail is made before the backdrop exists, so it never followed it and kept the old
	 * frost until a restart (owner, 2026-11-25: "sidebar updates only after restart").
	 */
	private static void follow(Pane pane, Backdrop backdrop) {
		if (backdrop == null || pane.getProperties().get(KEY + ".backdrop") == backdrop) {
			return;
		}
		pane.getProperties().put(KEY + ".backdrop", backdrop);
		// weak: a page's panels must not stay alive through the backdrop's listeners
		InvalidationListener relayout = (InvalidationListener) pane.getProperties().get(KEY + ".listener");
		backdrop.geometryProperty().addListener(new javafx.beans.WeakInvalidationListener(relayout));
	}

	private static void place(Pane pane, ImageView frost, Region edge, Rectangle clip) {
		Bounds local = pane.getLayoutBounds();
		double w = local.getWidth(), h = local.getHeight();
		edge.resizeRelocate(local.getMinX(), local.getMinY(), w, h);
		// the corners follow the panel's own (CSS) radius, so the frost and the edge always match its shape
		double radius = radiusOf(pane, (double) pane.getProperties().getOrDefault(KEY + ".radius", 14.0));
		if (clip.getArcWidth() != radius * 2) {
			clip.setArcWidth(radius * 2);
			clip.setArcHeight(radius * 2);
			edge.setStyle("-fx-border-radius: " + radius + "; -fx-background-radius: " + radius + ";");
		}
		Backdrop backdrop = Backdrop.current();
		follow(pane, backdrop);
		if (backdrop == null || backdrop.frostedProperty().get() == null || pane.getScene() == null || w <= 0 || h <= 0) {
			frost.setVisible(false);
			return;
		}
		if (frost.getImage() != backdrop.frostedProperty().get()) {
			// Home <-> grey page: the backdrop fades over 380 ms, so the panels already on screen (side rail, top bar,
			// a page that stays) fade their frost too - swapping it in one frame was the hard cut around the pages
			if (frost.getImage() != null && frost.isVisible() && backdrop.switchingMode()) {
				ghost(pane, frost);
			}
			frost.setImage(backdrop.frostedProperty().get());
		}
		Bounds scene;
		Bounds backdropScene;
		if (pane.getScene().getWindow() instanceof javafx.stage.PopupWindow) {
			// a dropdown is a window of its own: its place in the page's picture comes from the screen, not from its scene
			scene = pane.localToScreen(local);
			backdropScene = backdrop.localToScreen(backdrop.getLayoutBounds());
			if (scene == null || backdropScene == null) {
				frost.setVisible(false);
				return;
			}
		} else {
			scene = pane.localToScene(local);
			backdropScene = backdrop.localToScene(backdrop.getLayoutBounds());
		}
		double[] m = backdrop.mapping();
		double scale = m[0], offsetX = m[1], offsetY = m[2], frostPerSource = m[3];
		// window position -> source picture -> frosted picture (half size)
		double sx = (scene.getMinX() - backdropScene.getMinX() - offsetX) / scale * frostPerSource;
		double sy = (scene.getMinY() - backdropScene.getMinY() - offsetY) / scale * frostPerSource;
		double sw = scene.getWidth() / scale * frostPerSource;
		double sh = scene.getHeight() / scale * frostPerSource;
		frost.setViewport(new Rectangle2D(sx, sy, Math.max(1, sw), Math.max(1, sh)));
		frost.setFitWidth(w);
		frost.setFitHeight(h);
		frost.relocate(local.getMinX(), local.getMinY());
		clip.setWidth(w);
		clip.setHeight(h);
		frost.setVisible(true);
	}

	/** Re-places the frost of a glass pane now (a dropdown that has just been shown and positioned). */
	public static void refresh(Pane pane) {
		if (pane.getProperties().get(KEY + ".update") instanceof Runnable update) {
			update.run();
		}
	}

	/** A copy of the panel's old frost over the new one, fading out with the backdrop (removed when done). */
	private static void ghost(Pane pane, ImageView frost) {
		Object running = pane.getProperties().get(KEY + ".ghost");
		if (running instanceof javafx.animation.Animation animation) {
			animation.stop();
		}
		if (pane.getProperties().remove(KEY + ".ghostNode") instanceof Node previous) {
			pane.getChildren().remove(previous);
		}
		ImageView old = new ImageView(frost.getImage());
		old.setManaged(false);
		old.setMouseTransparent(true);
		old.setSmooth(true);
		old.setViewOrder(999);
		old.setViewport(frost.getViewport());
		old.setFitWidth(frost.getFitWidth());
		old.setFitHeight(frost.getFitHeight());
		old.relocate(frost.getLayoutX(), frost.getLayoutY());
		if (frost.getClip() instanceof Rectangle clip) {
			Rectangle copy = new Rectangle(clip.getWidth(), clip.getHeight());
			copy.setArcWidth(clip.getArcWidth());
			copy.setArcHeight(clip.getArcHeight());
			old.setClip(copy);
		}
		int at = pane.getChildren().indexOf(frost);
		pane.getChildren().add(Math.max(0, at + 1), old);
		javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(380), old);
		fade.setToValue(0);
		fade.setInterpolator(javafx.animation.Interpolator.EASE_BOTH);
		fade.setOnFinished(e -> {
			pane.getChildren().remove(old);
			pane.getProperties().remove(KEY + ".ghost");
			pane.getProperties().remove(KEY + ".ghostNode");
		});
		pane.getProperties().put(KEY + ".ghost", fade);
		pane.getProperties().put(KEY + ".ghostNode", old);
		net.veloclient.launcher.ui.UiMotion.afterFirstFrame(fade::play);
	}

	private static long lastWatchWalk;

	/**
	 * Panels that arrive later (lists loaded in the background, Discover, the Store...) become glass too: after layout
	 * pulses the page is walked again, at most every 120 ms (a cheap check of style classes).
	 */
	public static void watch(javafx.scene.Scene scene, java.util.function.Supplier<Node> page) {
		scene.addPostLayoutPulseListener(() -> {
			long now = System.currentTimeMillis();
			if (now - lastWatchWalk < 120) {
				return;
			}
			lastWatchWalk = now;
			Node root = page.get();
			if (root != null) {
				walk(root, 0);
			}
		});
	}

	private static boolean popupsWatched;

	/**
	 * Dropdowns, context menus and tooltips are little windows of their own, and their roots matched the page's ".root"
	 * rule: a dark square behind the rounded menu showed as black corners (2026-11-26). Their roots stay see-through.
	 */
	public static void clearPopupBackdrops() {
		if (popupsWatched) {
			return;
		}
		popupsWatched = true;
		javafx.stage.Window.getWindows().addListener((ListChangeListener<javafx.stage.Window>) change -> {
			while (change.next()) {
				for (javafx.stage.Window window : change.getAddedSubList()) {
					if (window instanceof javafx.stage.PopupWindow && window.getScene() != null) {
						window.getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
						var root = window.getScene().getRoot();
						if (!root.styleProperty().isBound()) {
							root.setStyle((root.getStyle() == null ? "" : root.getStyle()) + ";-fx-background-color: transparent;");
						}
					}
				}
			}
		});
	}

	/** Like {@link #applyTree}, but now - for a page that rebuilt its panels in place (no frame without glass). */
	public static void applyNow(Node node) {
		walk(node, 0);
	}

	/** Turns every panel of a freshly shown page into glass (cards, tiles, groups...), skipping 3D views. */
	public static void applyTree(Node node) {
		javafx.application.Platform.runLater(() -> walk(node, 0));
	}

	private static void walk(Node node, int depth) {
		if (depth > 40 || node instanceof javafx.scene.SubScene) {
			return;
		}
		if (node instanceof Pane pane && pane.getStyleClass().stream().anyMatch(AUTO::contains) && !pane.getProperties().containsKey(KEY)) {
			pane.applyCss();
			install(pane, radiusOf(pane, 18));
		}
		if (node instanceof Parent parent) {
			for (Node child : parent.getChildrenUnmodifiable()) {
				walk(child, depth + 1);
			}
		}
	}

	private static double radiusOf(Region region, double fallback) {
		if (region.getBackground() != null && !region.getBackground().getFills().isEmpty()) {
			double r = region.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius();
			if (r > 0) {
				return r;
			}
		}
		if (region.getBorder() != null && !region.getBorder().getStrokes().isEmpty()) {
			double r = region.getBorder().getStrokes().getFirst().getRadii().getTopLeftHorizontalRadius();
			if (r > 0) {
				return r;
			}
		}
		return fallback;
	}
}
