package net.veloclient.launcher.instance;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small fixed set of profile icons drawn as pure JavaFX vector shapes -
 * this launcher has no Minecraft renderer/textures available to it (unlike
 * the in-game mod's {@code ModuleIcons}, which stamps vanilla item icons), so
 * icons are simple hand-drawn glyphs instead of requiring image assets.
 */
public final class BuiltinIcons {

	public static final String DEFAULT = "diamond";

	private static final Map<String, Drawer> ICONS = new LinkedHashMap<>();

	static {
		ICONS.put("diamond", BuiltinIcons::drawDiamond);
		ICONS.put("sword", BuiltinIcons::drawSword);
		ICONS.put("shield", BuiltinIcons::drawShield);
		ICONS.put("star", BuiltinIcons::drawStar);
		ICONS.put("potion", BuiltinIcons::drawPotion);
		ICONS.put("compass", BuiltinIcons::drawCompass);
		ICONS.put("book", BuiltinIcons::drawBook);
		ICONS.put("flame", BuiltinIcons::drawFlame);
		ICONS.put("anvil", BuiltinIcons::drawAnvil);
		ICONS.put("skull", BuiltinIcons::drawSkull);
	}

	private BuiltinIcons() {
	}

	public static Iterable<String> ids() {
		return ICONS.keySet();
	}

	/** Renders a builtin icon (or a "?" fallback for an unknown id) into a rounded tile sized {@code size}x{@code size}. */
	public static StackPane render(String id, double size, Color accentStart, Color accentEnd) {
		StackPane tile = new StackPane();
		tile.setPrefSize(size, size);
		tile.setMinSize(size, size);
		tile.setMaxSize(size, size);
		tile.setPadding(new Insets(size * 0.16));
		tile.setStyle("-fx-background-radius: " + (size * 0.22) + "; -fx-background-color: linear-gradient(to bottom right, "
				+ toRgba(accentStart) + ", " + toRgba(accentEnd) + ");");

		Canvas canvas = new Canvas(size, size);
		GraphicsContext gc = canvas.getGraphicsContext2D();
		Drawer drawer = ICONS.getOrDefault(id, BuiltinIcons::drawDiamond);
		drawer.draw(gc, size);
		StackPane.setAlignment(canvas, Pos.CENTER);
		tile.getChildren().add(canvas);
		return tile;
	}

	private static String toRgba(Color c) {
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
				(int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255), c.getOpacity());
	}

	@FunctionalInterface
	private interface Drawer {
		void draw(GraphicsContext gc, double size);
	}

	// ---- Glyphs (drawn in a 0..size coordinate space, white fill/stroke - tile background already carries color) ----

	private static void drawDiamond(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		double[] xs = {s * 0.5, s * 0.85, s * 0.5, s * 0.15};
		double[] ys = {s * 0.15, s * 0.45, s * 0.85, s * 0.45};
		gc.fillPolygon(xs, ys, 4);
	}

	private static void drawSword(GraphicsContext gc, double s) {
		gc.setStroke(Color.WHITE);
		gc.setLineWidth(s * 0.09);
		gc.setLineCap(StrokeLineCap.ROUND);
		gc.strokeLine(s * 0.28, s * 0.78, s * 0.78, s * 0.28);
		gc.setLineWidth(s * 0.14);
		gc.strokeLine(s * 0.2, s * 0.62, s * 0.38, s * 0.8);
		gc.setLineWidth(s * 0.07);
		gc.strokeLine(s * 0.62, s * 0.36, s * 0.78, s * 0.2);
	}

	private static void drawShield(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		double[] xs = {s * 0.5, s * 0.82, s * 0.82, s * 0.5, s * 0.18, s * 0.18};
		double[] ys = {s * 0.14, s * 0.28, s * 0.55, s * 0.86, s * 0.55, s * 0.28};
		gc.fillPolygon(xs, ys, 6);
	}

	private static void drawStar(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		double cx = s * 0.5, cy = s * 0.5, outer = s * 0.4, inner = s * 0.17;
		double[] xs = new double[10];
		double[] ys = new double[10];
		for (int i = 0; i < 10; i++) {
			double r = (i % 2 == 0) ? outer : inner;
			double angle = Math.toRadians(-90 + i * 36);
			xs[i] = cx + r * Math.cos(angle);
			ys[i] = cy + r * Math.sin(angle);
		}
		gc.fillPolygon(xs, ys, 10);
	}

	private static void drawPotion(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		gc.fillRoundRect(s * 0.36, s * 0.18, s * 0.28, s * 0.16, s * 0.06, s * 0.06);
		gc.fillOval(s * 0.24, s * 0.4, s * 0.52, s * 0.44);
		gc.setFill(Color.WHITE.deriveColor(0, 1, 1, 0.55));
		gc.fillOval(s * 0.3, s * 0.5, s * 0.14, s * 0.14);
	}

	private static void drawCompass(GraphicsContext gc, double s) {
		gc.setStroke(Color.WHITE);
		gc.setLineWidth(s * 0.06);
		gc.strokeOval(s * 0.18, s * 0.18, s * 0.64, s * 0.64);
		gc.setFill(Color.WHITE);
		double cx = s * 0.5, cy = s * 0.5;
		double[] xs = {cx, cx + s * 0.14, cx};
		double[] ys = {cy - s * 0.22, cy, cy + s * 0.03};
		gc.fillPolygon(xs, ys, 3);
		double[] xs2 = {cx, cx - s * 0.14, cx};
		double[] ys2 = {cy + s * 0.22, cy, cy - s * 0.03};
		gc.fillPolygon(xs2, ys2, 3);
	}

	private static void drawBook(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		gc.fillRoundRect(s * 0.2, s * 0.2, s * 0.6, s * 0.6, s * 0.06, s * 0.06);
		gc.setStroke(Color.WHITE.deriveColor(0, 1, 0.6, 1));
		gc.setLineWidth(s * 0.04);
		gc.strokeLine(s * 0.5, s * 0.24, s * 0.5, s * 0.76);
		gc.strokeLine(s * 0.28, s * 0.38, s * 0.44, s * 0.38);
		gc.strokeLine(s * 0.56, s * 0.38, s * 0.72, s * 0.38);
		gc.strokeLine(s * 0.28, s * 0.5, s * 0.44, s * 0.5);
		gc.strokeLine(s * 0.56, s * 0.5, s * 0.72, s * 0.5);
	}

	private static void drawFlame(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		gc.beginPath();
		gc.moveTo(s * 0.5, s * 0.12);
		gc.bezierCurveTo(s * 0.7, s * 0.35, s * 0.78, s * 0.5, s * 0.68, s * 0.66);
		gc.bezierCurveTo(s * 0.66, s * 0.5, s * 0.58, s * 0.46, s * 0.5, s * 0.5);
		gc.bezierCurveTo(s * 0.42, s * 0.46, s * 0.34, s * 0.5, s * 0.32, s * 0.66);
		gc.bezierCurveTo(s * 0.22, s * 0.5, s * 0.3, s * 0.35, s * 0.5, s * 0.12);
		gc.closePath();
		gc.fill();
		gc.setFill(Color.WHITE.deriveColor(0, 1, 1, 0.5));
		gc.fillOval(s * 0.42, s * 0.6, s * 0.16, s * 0.2);
	}

	private static void drawAnvil(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		gc.fillRoundRect(s * 0.3, s * 0.2, s * 0.4, s * 0.14, s * 0.03, s * 0.03);
		gc.fillRect(s * 0.42, s * 0.34, s * 0.16, s * 0.14);
		gc.fillRoundRect(s * 0.2, s * 0.48, s * 0.6, s * 0.16, s * 0.03, s * 0.03);
	}

	private static void drawSkull(GraphicsContext gc, double s) {
		gc.setFill(Color.WHITE);
		gc.fillArc(s * 0.24, s * 0.18, s * 0.52, s * 0.52, 0, 360, ArcType.ROUND);
		gc.fillRect(s * 0.3, s * 0.5, s * 0.4, s * 0.16);
		gc.setFill(Color.WHITE.deriveColor(0, 1, 0.3, 1));
		gc.fillOval(s * 0.33, s * 0.36, s * 0.14, s * 0.16);
		gc.fillOval(s * 0.53, s * 0.36, s * 0.14, s * 0.16);
	}
}
