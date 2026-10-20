package net.veloclient.launcher.ui;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;

import java.util.List;

/**
 * The launcher's own window frame (no OS title bar): the top bar moves the window, a double click or dragging to
 * the top edge maximizes it, every edge and corner resizes it, and minimize / maximize / close sit at the right
 * of the top bar. Maximize fills the screen's usable area (not over the taskbar), on whichever screen the window is.
 */
public final class WindowChrome {

	private static final double EDGE = 6;

	private final Stage stage;
	private final ReadOnlyBooleanWrapper maximized = new ReadOnlyBooleanWrapper(false);
	private Rectangle2D restoreBounds;

	// Move / resize state.
	private double grabX;
	private double grabY;
	private boolean moving;
	private Cursor resizing;
	private double startX;
	private double startY;
	private double startW;
	private double startH;
	private double startScreenX;
	private double startScreenY;

	private WindowChrome(Stage stage) {
		this.stage = stage;
	}

	/** Call before the stage is first shown. */
	public static WindowChrome install(Stage stage) {
		stage.initStyle(StageStyle.UNDECORATED);
		return new WindowChrome(stage);
	}

	public ReadOnlyBooleanProperty maximizedProperty() {
		return maximized.getReadOnlyProperty();
	}

	public boolean isMaximized() {
		return maximized.get();
	}

	/** The windowed size and place (what to remember while maximized), or the current bounds. */
	public Rectangle2D windowedBounds() {
		return maximized.get() && restoreBounds != null ? restoreBounds
				: new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
	}

	/** The real pointer position: event screen coordinates are derived from the window's own (lagging) position while it moves. */
	private javafx.scene.robot.Robot pointer;

	private double pointerX(MouseEvent e) {
		try {
			if (pointer == null) {
				pointer = new javafx.scene.robot.Robot();
			}
			return pointer.getMouseX();
		} catch (RuntimeException ex) {
			return e.getScreenX();
		}
	}

	private double pointerY(MouseEvent e) {
		try {
			if (pointer == null) {
				pointer = new javafx.scene.robot.Robot();
			}
			return pointer.getMouseY();
		} catch (RuntimeException ex) {
			return e.getScreenY();
		}
	}

	// Bounds asked for by a drag are applied once per frame, all four together.
	private double[] pending;
	private final javafx.animation.AnimationTimer applier = new javafx.animation.AnimationTimer() {
		@Override
		public void handle(long now) {
			if (pending != null) {
				apply(pending[0], pending[1], pending[2], pending[3]);
				pending = null;
			}
		}
	};

	private void request(double x, double y, double w, double h) {
		pending = new double[] {x, y, w, h};
		applier.start();
	}

	/** Sets only what changed; growing upwards/leftwards moves first so the far edge stays put. */
	private void apply(double x, double y, double w, double h) {
		x = Math.round(x);
		y = Math.round(y);
		w = Math.round(w);
		h = Math.round(h);
		if (x != stage.getX()) {
			stage.setX(x);
		}
		if (y != stage.getY()) {
			stage.setY(y);
		}
		if (w != stage.getWidth()) {
			stage.setWidth(w);
		}
		if (h != stage.getHeight()) {
			stage.setHeight(h);
		}
	}

	private static final double DRAG_THRESHOLD = 5;
	private double pressX;
	private double pressY;
	private boolean armed;
	private Rectangle2D dragStartBounds;

	/** Lets {@code area} (the top bar) move the window; clicks on its buttons and fields still work. */
	public void dragWith(Node area) {
		area.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
			if (e.getButton() != MouseButton.PRIMARY || resizing != null || isControl(e) || animating) {
				return;
			}
			armed = true;
			moving = false;
			pressX = pointerX(e);
			pressY = pointerY(e);
			grabX = pressX - stage.getX();
			grabY = pressY - stage.getY();
			dragStartBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
		});
		area.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
			if (!armed) {
				return;
			}
			double mx = pointerX(e);
			double my = pointerY(e);
			if (!moving) {
				// A double click or a shaky click mustn't move (or un-maximize) the window.
				if (Math.hypot(mx - pressX, my - pressY) < DRAG_THRESHOLD) {
					return;
				}
				moving = true;
				if (maximized.get()) {
					// Pulling a maximized window down restores its size under the cursor, at the same relative spot.
					double ratio = grabX / stage.getWidth();
					Rectangle2D back = restoreBounds != null ? restoreBounds : new Rectangle2D(stage.getX(), stage.getY(), 1200, 760);
					maximized.set(false);
					grabX = back.getWidth() * ratio;
					grabY = Math.min(grabY, 40);
					dragStartBounds = back;
					request(mx - grabX, my - grabY, back.getWidth(), back.getHeight());
					return;
				}
			}
			double minY = screenFor(mx, my).getVisualBounds().getMinY();
			request(mx - grabX, Math.max(minY, my - grabY), stage.getWidth(), stage.getHeight());
		});
		area.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
			if (moving && !maximized.get()) {
				double my = pointerY(e);
				Rectangle2D visual = screenFor(pointerX(e), my).getVisualBounds();
				if (my <= visual.getMinY() + 2) {
					// Snapped to the top: un-maximizing goes back to where the window was before this drag.
					maximize(dragStartBounds);
				}
			}
			armed = false;
			moving = false;
		});
		area.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
			if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && e.isStillSincePress() && !isControl(e)) {
				toggleMaximize();
			}
		});
	}

	/** Resizing from every edge and corner of the window. */
	public void resizeWith(Scene scene) {
		scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
			if (maximized.get() || animating) {
				return;
			}
			Cursor c = edgeCursor(e.getSceneX(), e.getSceneY(), scene);
			if (c != null) {
				scene.setCursor(c);
			} else if (isEdgeCursor(scene.getCursor())) {
				scene.setCursor(Cursor.DEFAULT);
			}
		});
		scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
			if (resizing == null && isEdgeCursor(scene.getCursor())) {
				scene.setCursor(Cursor.DEFAULT);
			}
		});
		scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
			if (maximized.get() || animating || e.getButton() != MouseButton.PRIMARY) {
				return;
			}
			Cursor c = edgeCursor(e.getSceneX(), e.getSceneY(), scene);
			if (c == null) {
				return;
			}
			resizing = c;
			startX = stage.getX();
			startY = stage.getY();
			startW = stage.getWidth();
			startH = stage.getHeight();
			startScreenX = pointerX(e);
			startScreenY = pointerY(e);
			e.consume();
		});
		scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
			if (resizing == null) {
				return;
			}
			double dx = pointerX(e) - startScreenX;
			double dy = pointerY(e) - startScreenY;
			double minW = Math.max(stage.getMinWidth(), 400);
			double minH = Math.max(stage.getMinHeight(), 300);
			boolean west = resizing == Cursor.W_RESIZE || resizing == Cursor.NW_RESIZE || resizing == Cursor.SW_RESIZE;
			boolean east = resizing == Cursor.E_RESIZE || resizing == Cursor.NE_RESIZE || resizing == Cursor.SE_RESIZE;
			boolean north = resizing == Cursor.N_RESIZE || resizing == Cursor.NW_RESIZE || resizing == Cursor.NE_RESIZE;
			boolean south = resizing == Cursor.S_RESIZE || resizing == Cursor.SW_RESIZE || resizing == Cursor.SE_RESIZE;
			double x = startX;
			double y = startY;
			double w = startW;
			double h = startH;
			if (east) {
				w = Math.max(minW, startW + dx);
			}
			if (south) {
				h = Math.max(minH, startH + dy);
			}
			if (west) {
				w = Math.max(minW, startW - dx);
				x = startX + startW - w;
			}
			if (north) {
				h = Math.max(minH, startH - dy);
				y = startY + startH - h;
			}
			request(x, y, w, h);
			e.consume();
		});
		scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
			if (resizing != null) {
				resizing = null;
				e.consume();
			}
		});
	}

	private static final List<Cursor> EDGE_CURSORS = List.of(Cursor.N_RESIZE, Cursor.S_RESIZE, Cursor.E_RESIZE, Cursor.W_RESIZE,
			Cursor.NE_RESIZE, Cursor.NW_RESIZE, Cursor.SE_RESIZE, Cursor.SW_RESIZE);

	private static boolean isEdgeCursor(Cursor cursor) {
		return cursor != null && EDGE_CURSORS.contains(cursor);
	}

	private static Cursor edgeCursor(double x, double y, Scene scene) {
		double w = scene.getWidth();
		double h = scene.getHeight();
		boolean left = x < EDGE;
		boolean right = x > w - EDGE;
		boolean top = y < EDGE;
		boolean bottom = y > h - EDGE;
		if (top && left) {
			return Cursor.NW_RESIZE;
		}
		if (top && right) {
			return Cursor.NE_RESIZE;
		}
		if (bottom && left) {
			return Cursor.SW_RESIZE;
		}
		if (bottom && right) {
			return Cursor.SE_RESIZE;
		}
		if (left) {
			return Cursor.W_RESIZE;
		}
		if (right) {
			return Cursor.E_RESIZE;
		}
		if (top) {
			return Cursor.N_RESIZE;
		}
		return bottom ? Cursor.S_RESIZE : null;
	}

	public void toggleMaximize() {
		if (animating) {
			return;
		}
		if (maximized.get()) {
			restore();
		} else {
			maximize();
		}
	}

	public void maximize() {
		maximize(new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
	}

	/** Maximizes; {@code windowed} is what restoring goes back to. */
	private void maximize(Rectangle2D windowed) {
		if (maximized.get()) {
			return;
		}
		restoreBounds = windowed;
		Rectangle2D visual = screenFor(stage.getX() + stage.getWidth() / 2, stage.getY() + stage.getHeight() / 2).getVisualBounds();
		maximized.set(true);
		animateTo(visual);
	}

	public void restore() {
		if (!maximized.get()) {
			return;
		}
		maximized.set(false);
		if (restoreBounds != null) {
			animateTo(restoreBounds);
		}
	}

	private boolean animating;

	/** Glides the window to {@code target} (180 ms, ease-out), applying all four bounds each frame. */
	private void animateTo(Rectangle2D target) {
		double x0 = stage.getX();
		double y0 = stage.getY();
		double w0 = stage.getWidth();
		double h0 = stage.getHeight();
		pending = null;
		animating = true;
		long start = System.nanoTime();
		double duration = 180e6;
		new javafx.animation.AnimationTimer() {
			@Override
			public void handle(long now) {
				double t = Math.min(1, (now - start) / duration);
				double k = UiMotion.EASE_OUT.interpolate(0.0, 1.0, t);
				apply(x0 + (target.getMinX() - x0) * k, y0 + (target.getMinY() - y0) * k,
						w0 + (target.getWidth() - w0) * k, h0 + (target.getHeight() - h0) * k);
				if (t >= 1) {
					stop();
					animating = false;
				}
			}
		}.start();
	}

	/** Starts maximized with {@code windowed} as the size to restore to (window placement from last time). */
	public void startMaximized(Rectangle2D windowed) {
		restoreBounds = windowed;
		Rectangle2D visual = screenFor(windowed.getMinX() + windowed.getWidth() / 2, windowed.getMinY() + windowed.getHeight() / 2).getVisualBounds();
		apply(visual.getMinX(), visual.getMinY(), visual.getWidth(), visual.getHeight());
		maximized.set(true);
	}

	/** Minimize, maximize/restore and close, for the right end of the top bar. */
	public Node controls() {
		Button minimize = windowButton(minimizeGlyph(), "Minimize", false);
		minimize.setOnAction(e -> stage.setIconified(true));
		StackPane maxGlyph = new StackPane();
		Runnable paintMax = () -> maxGlyph.getChildren().setAll(maximized.get() ? restoreGlyph() : maximizeGlyph());
		paintMax.run();
		maximized.addListener((o, a, b) -> paintMax.run());
		Button max = windowButton(maxGlyph, "Maximize", false);
		maximized.addListener((o, a, b) -> max.getTooltip().setText(b ? "Restore" : "Maximize"));
		max.setOnAction(e -> toggleMaximize());
		Button close = windowButton(closeGlyph(), "Close", true);
		close.setOnAction(e -> {
			// The same path as the OS close button: the launcher's own close handling (save placement, ask about running games) runs first.
			WindowEvent request = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
			Event.fireEvent(stage, request);
			if (!request.isConsumed()) {
				stage.close();
			}
		});
		HBox box = new HBox(2, minimize, max, close);
		box.setAlignment(Pos.CENTER);
		box.setMinWidth(Region.USE_PREF_SIZE);
		return box;
	}

	private static Button windowButton(Node glyph, String tip, boolean danger) {
		Button b = new Button(null, glyph);
		b.getStyleClass().setAll("button", "window-button", "no-motion");
		if (danger) {
			b.getStyleClass().add("window-button-close");
		}
		b.setTooltip(new Tooltip(tip));
		b.setFocusTraversable(false);
		return b;
	}

	private static Node minimizeGlyph() {
		Line line = new Line(0, 0, 10, 0);
		line.getStyleClass().add("window-glyph");
		return line;
	}

	private static Node maximizeGlyph() {
		Rectangle r = new Rectangle(10, 10);
		r.setArcWidth(3);
		r.setArcHeight(3);
		r.getStyleClass().add("window-glyph");
		return r;
	}

	private static Node restoreGlyph() {
		Rectangle back = new Rectangle(8, 8);
		back.setTranslateX(2);
		back.setTranslateY(-2);
		Rectangle front = new Rectangle(8, 8);
		front.setTranslateX(-1);
		front.setTranslateY(1);
		for (Rectangle r : new Rectangle[] {back, front}) {
			r.setArcWidth(3);
			r.setArcHeight(3);
			r.getStyleClass().add("window-glyph");
		}
		front.getStyleClass().add("window-glyph-front");
		return new StackPane(back, front);
	}

	private static Node closeGlyph() {
		Line a = new Line(0, 0, 10, 10);
		Line b = new Line(0, 10, 10, 0);
		a.getStyleClass().add("window-glyph");
		b.getStyleClass().add("window-glyph");
		return new StackPane(a, b);
	}

	private static boolean isControl(MouseEvent e) {
		Node node = e.getPickResult().getIntersectedNode();
		while (node != null) {
			if (node instanceof ButtonBase || node instanceof TextInputControl || node.getStyleClass().contains("no-drag")) {
				return true;
			}
			Parent parent = node.getParent();
			node = parent;
		}
		return false;
	}

	private static Screen screenFor(double x, double y) {
		var screens = Screen.getScreensForRectangle(x, y, 1, 1);
		return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
	}
}
