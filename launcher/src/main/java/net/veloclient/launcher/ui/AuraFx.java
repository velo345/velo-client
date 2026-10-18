package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.transform.Rotate;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Auras in the launcher, from the same {@link AuraEffects} code the game runs:
 * <ul>
 * <li>{@link #attach} puts one around the 3D player of a {@link PlayerSkin3DView} viewer - two
 * transparent canvases, one behind and one in front of the 3D scene, projected with the viewer's own
 * camera and turn, so the aura wraps around the player as it spins;</li>
 * <li>{@link #card} is a light 2D version around a dim player shape, for Store cards.</li>
 * </ul>
 * Glows are radial gradients drawn with additive blending, like the game's.
 */
public final class AuraFx {

	/** What to show: an aura id (null = nothing) and how it's worn. */
	public record Shown(String id, AuraEffects.Settings settings) {
	}

	/** Model units per block (the viewer's model is in Minecraft pixels), and where its feet are. */
	private static final double UNIT = 16;
	private static final double FEET_Y = 10;

	/**
	 * A glow, (shape {@link #LINE}) a beam from (x, y) to (x2, y2) {@code radius} wide, or (shape {@link #PANEL}) an
	 * upright card {@code radius} wide and {@code x2} tall showing atlas cell {@code (int) y2}.
	 */
	private record Sprite(double x, double y, double depth, double radius, int rgb, double alpha, int shape, double x2, double y2) {
	}

	private static final int LINE = 99;
	private static final int PANEL = 98;
	/** Code-rain characters the default font surely has (the game's atlas has katakana; fonts here might not). */
	private static final String GLYPHS = "0123457ABCDEFHXZ";
	private static final javafx.scene.text.Font GLYPH_FONT = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 12);

	private AuraFx() {
	}

	/** Shows {@code shown} (re-read every frame) around the player of a viewer made by PlayerSkin3DView. */
	public static void attach(Node viewer, Supplier<Shown> shown) {
		if (!(viewer instanceof StackPane wrapper) || !(wrapper.getProperties().get("velo.yaw") instanceof Rotate yaw)
				|| !(wrapper.getProperties().get("velo.camera") instanceof PerspectiveCamera camera)
				|| !(wrapper.getProperties().get("velo.subScene") instanceof SubScene)) {
			return;
		}
		Canvas back = new Canvas();
		Canvas front = new Canvas();
		for (Canvas c : new Canvas[] {back, front}) {
			c.setMouseTransparent(true);
			c.widthProperty().bind(wrapper.widthProperty());
			c.heightProperty().bind(wrapper.heightProperty());
			c.setManaged(false);
		}
		wrapper.getChildren().add(0, back);
		wrapper.getChildren().add(front);
		AuraEffects.Emitter[] emitter = new AuraEffects.Emitter[1];
		AnimationTimer timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				Shown s = shown.get();
				AuraEffects.Kind kind = s == null || s.id() == null ? null : AuraEffects.kind(s.id());
				clear(back);
				clear(front);
				if (kind == null) {
					emitter[0] = null;
					return;
				}
				if (emitter[0] == null || emitter[0].kind() != kind) {
					emitter[0] = new AuraEffects.Emitter(kind);
				}
				emitter[0].update(emitter[0].tick(now), s.settings(), 0, 0, 0);
				double w = wrapper.getWidth();
				double h = wrapper.getHeight();
				double focal = (h / 2) / Math.tan(Math.toRadians(camera.getFieldOfView() / 2));
				double distance = -camera.getTranslateZ();
				double angle = Math.toRadians(yaw.getAngle());
				List<Sprite> sprites = project(emitter[0], s.settings(), w / 2, h / 2, focal, distance, angle);
				draw(back.getGraphicsContext2D(), sprites, distance, false);
				draw(front.getGraphicsContext2D(), sprites, distance, true);
			}
		};
		wrapper.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				timer.stop();
			} else {
				timer.start();
			}
		});
		if (wrapper.getScene() != null) {
			timer.start();
		}
	}

	private static final java.util.Map<String, javafx.scene.image.Image> SNAPSHOTS = new java.util.HashMap<>();

	/**
	 * One still picture of an aura (a couple of seconds into its animation, around a dim player shape):
	 * for lists with many entries, where a live animation per row would cost more than it's worth.
	 * FX thread; cached.
	 */
	public static javafx.scene.image.Image snapshot(AuraEffects.Kind kind, double width, double height) {
		return snapshot(kind, AuraEffects.Settings.DEFAULT, width, height);
	}

	/** One cached still picture using the supplied per-aura settings. */
	public static javafx.scene.image.Image snapshot(AuraEffects.Kind kind, AuraEffects.Settings settings, double width, double height) {
		return SNAPSHOTS.computeIfAbsent(kind + "@" + settings.encode() + "@" + (int) width + "x" + (int) height, k -> {
			AuraEffects.Emitter emitter = new AuraEffects.Emitter(kind);
			for (int i = 0; i < 90; i++) {
				emitter.update(1 / 30f, settings, 0, 0, 0);
			}
			double focal = (height / 2) / Math.tan(Math.toRadians(15));
			double distance = 95;
			Canvas canvas = new Canvas(width, height);
			List<Sprite> sprites = project(emitter, settings, width / 2, height / 2 - 6, focal, distance, 0.5);
			GraphicsContext g = canvas.getGraphicsContext2D();
			draw(g, sprites, distance, false);
			silhouette(g, width / 2, height / 2 - 6, focal / distance);
			draw(g, sprites, distance, true);
			javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
			params.setFill(Color.TRANSPARENT);
			return canvas.snapshot(params, null);
		});
	}

	/** A Store card's picture: the aura around a dim player shape, turning slowly. */
	public static Node card(AuraEffects.Kind kind, Supplier<AuraEffects.Settings> settings, double width, double height) {
		Canvas canvas = new Canvas(width, height);
		AuraEffects.Emitter emitter = new AuraEffects.Emitter(kind);
		AnimationTimer timer = new AnimationTimer() {
			private long lastDraw;

			@Override
			public void handle(long now) {
				// Cards animate at ~30 fps: a page of ten of them at the full refresh rate was most of the Store's cost.
				if (now - lastDraw < 30_000_000L) {
					return;
				}
				lastDraw = now;
				AuraEffects.Settings s = settings.get();
				emitter.update(emitter.tick(now), s, 0, 0, 0);
				// A camera like the viewer's, zoomed so the whole aura fits.
				double focal = (height / 2) / Math.tan(Math.toRadians(15));
				double distance = 95;
				double angle = now / 4e9;
				List<Sprite> sprites = project(emitter, s, width / 2, height / 2 - 6, focal, distance, angle);
				GraphicsContext g = canvas.getGraphicsContext2D();
				clear(canvas);
				draw(g, sprites, distance, false);
				silhouette(g, width / 2, height / 2 - 6, focal / distance);
				draw(g, sprites, distance, true);
			}
		};
		canvas.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				timer.stop();
			} else {
				timer.start();
			}
		});
		return canvas;
	}

	private static void clear(Canvas canvas) {
		GraphicsContext g = canvas.getGraphicsContext2D();
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
		g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
	}

	/** The aura's sprites on screen (model space: x right, y down, feet at FEET_Y; turned by {@code angle}). */
	private static List<Sprite> project(AuraEffects.Emitter emitter, AuraEffects.Settings settings, double cx, double cy, double focal,
			double distance, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		List<Sprite> out = new ArrayList<>();
		emitter.draw(settings, 0, 0, 0, new AuraEffects.Out() {
			@Override
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
				if (alpha <= 0.02f) {
					return;
				}
				double mx = x * UNIT;
				double my = FEET_Y - y * UNIT;
				double mz = z * UNIT;
				double rx = mx * cos + mz * sin;
				double rz = -mx * sin + mz * cos;
				double d = rz + distance;
				if (d < 5) {
					return;
				}
				double k = focal / d;
				out.add(new Sprite(cx + rx * k, cy + my * k, rz, size * UNIT * k, rgb, alpha, shape, 0, 0));
			}

			@Override
			public void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha) {
				if (alpha <= 0.02f) {
					return;
				}
				double[] a = point(x1, y1, z1);
				double[] b = point(x2, y2, z2);
				if (a == null || b == null) {
					return;
				}
				double k = focal / ((a[3] + b[3]) / 2);
				out.add(new Sprite(a[0], a[1], (a[2] + b[2]) / 2, width * UNIT * k, rgb, alpha, LINE, b[0], b[1]));
			}

			@Override
			public void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha) {
				if (alpha <= 0.02f) {
					return;
				}
				double[] c = point(x, y, z);
				if (c == null) {
					return;
				}
				double k = focal / c[3];
				// Turned away from the camera it gets narrow (its normal, turned like the model).
				out.add(new Sprite(c[0], c[1], c[2], w * UNIT * k * Math.max(0.15, facingFor(nx, nz, cos, sin)), rgb, alpha, PANEL, h * UNIT * k, cell));
			}

			private double[] point(float x, float y, float z) {
				double mx = x * UNIT;
				double my = FEET_Y - y * UNIT;
				double mz = z * UNIT;
				double rx = mx * cos + mz * sin;
				double rz = -mx * sin + mz * cos;
				double d = rz + distance;
				if (d < 5) {
					return null;
				}
				return new double[] {cx + rx * focal / d, cy + my * focal / d, rz, d};
			}
		});
		out.sort((a, b) -> Double.compare(b.depth, a.depth));
		return out;
	}

	/** How squarely a card with normal (nx, nz) faces the camera after the model's turn (0 edge-on, 1 head-on). */
	private static double facingFor(float nx, float nz, double cos, double sin) {
		// The model turns by its yaw; the camera looks along +z.
		double rz = -nx * sin + nz * cos;
		return Math.abs(rz);
	}

	private static final java.util.Map<Integer, javafx.scene.image.Image> GLOWS = new java.util.HashMap<>();

	/** A soft round glow (bright core, long falloff) in {@code rgb}, 48 px, made once per colour (colours snapped to 16 levels). */
	private static javafx.scene.image.Image glowImage(int rgb) {
		int key = ((rgb >> 20) & 15) << 8 | ((rgb >> 12) & 15) << 4 | ((rgb >> 4) & 15);
		javafx.scene.image.Image cached = GLOWS.get(key);
		if (cached != null) {
			return cached;
		}
		if (GLOWS.size() > 600) {
			GLOWS.clear();
		}
		int size = 48;
		javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(size, size);
		javafx.scene.image.PixelWriter writer = image.getPixelWriter();
		int r = ((key >> 8) & 15) * 17;
		int gr = ((key >> 4) & 15) * 17;
		int b = (key & 15) * 17;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double dx = (x + 0.5) / size * 2 - 1;
				double dy = (y + 0.5) / size * 2 - 1;
				double d = Math.hypot(dx, dy);
				double a = d >= 1 ? 0 : (Math.exp(-d * d * 9) * 0.85 + Math.exp(-d * d * 2.5) * 0.35) * Math.sqrt(1 - d);
				writer.setArgb(x, y, ((int) Math.round(Math.min(1, a) * 255) << 24) | (r << 16) | (gr << 8) | b);
			}
		}
		GLOWS.put(key, image);
		return image;
	}

	/** Draws the sprites behind ({@code front} false) or in front of the player. */
	private static void draw(GraphicsContext g, List<Sprite> sprites, double distance, boolean front) {
		g.setGlobalBlendMode(BlendMode.ADD);
		for (Sprite s : sprites) {
			if (s.depth < 0 != front) {
				continue;
			}
			Color c = Color.rgb((s.rgb >> 16) & 255, (s.rgb >> 8) & 255, s.rgb & 255, Math.min(1, s.alpha));
			if (s.shape == PANEL) {
				int cell = (int) s.y2;
				double w = Math.max(1, s.radius);
				double h = Math.max(1, s.x2);
				if (cell >= AuraEffects.CELL_GLYPH) {
					// A code glyph: the character itself, mirrored like in the game.
					g.save();
					g.translate(s.x, s.y);
					g.scale(-w / 9.0, h / 12.0);
					g.setFill(c);
					g.setFont(GLYPH_FONT);
					g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
					g.setTextBaseline(javafx.geometry.VPos.CENTER);
					g.fillText(String.valueOf(GLYPHS.charAt((cell - AuraEffects.CELL_GLYPH) % GLYPHS.length())), 0, 0);
					g.restore();
				} else {
					// A light curtain: bright at the foot, fading upwards.
					g.setFill(new javafx.scene.paint.LinearGradient(0, s.y + h / 2, 0, s.y - h / 2, false, CycleMethod.NO_CYCLE,
							new Stop(0, c), new Stop(0.12, c.deriveColor(0, 1, 1, 0.55)), new Stop(1, Color.TRANSPARENT)));
					g.fillRect(s.x - w / 2, s.y - h / 2, w, h);
				}
				continue;
			}
			if (s.shape == AuraEffects.HEART) {
				double r = Math.max(1.5, s.radius);
				g.setFill(c);
				g.beginPath();
				g.moveTo(s.x, s.y + r * 0.9);
				g.bezierCurveTo(s.x - r * 1.4, s.y - r * 0.1, s.x - r * 0.6, s.y - r * 1.1, s.x, s.y - r * 0.35);
				g.bezierCurveTo(s.x + r * 0.6, s.y - r * 1.1, s.x + r * 1.4, s.y - r * 0.1, s.x, s.y + r * 0.9);
				g.closePath();
				g.fill();
				continue;
			}
			if (s.shape == AuraEffects.LEAF) {
				double r = Math.max(1.5, s.radius);
				g.save();
				g.translate(s.x, s.y);
				g.rotate((s.rgb * 37 + s.depth * 40) % 360);
				g.setFill(c);
				g.fillOval(-r * 0.4, -r, r * 0.8, r * 2);
				g.restore();
				continue;
			}
			if (s.shape == LINE) {
				// A soft glow around a sharper line.
				g.setLineCap(StrokeLineCap.ROUND);
				g.setStroke(c.deriveColor(0, 1, 1, 0.35));
				g.setLineWidth(Math.max(1.2, s.radius * 1.1));
				g.strokeLine(s.x, s.y, s.x2, s.y2);
				g.setStroke(c);
				g.setLineWidth(Math.max(0.6, s.radius * 0.4));
				g.strokeLine(s.x, s.y, s.x2, s.y2);
				continue;
			}
			double r = Math.max(1, s.radius);
			// Glows are a cached soft sprite per colour, stamped with the sprite's alpha: building a gradient
			// object per particle per frame (the old way) made a page of aura cards crawl.
			javafx.scene.image.Image glow = glowImage(s.rgb);
			g.setGlobalAlpha(Math.min(1, s.alpha));
			if (s.shape == AuraEffects.DISC) {
				g.drawImage(glow, s.x - r, s.y - r * 0.22, r * 2, r * 0.44);
				g.setGlobalAlpha(1);
				continue;
			}
			g.drawImage(glow, s.x - r, s.y - r, r * 2, r * 2);
			g.setGlobalAlpha(1);
			if (s.shape == AuraEffects.STAR) {
				g.setStroke(c);
				g.setLineCap(StrokeLineCap.ROUND);
				g.setLineWidth(Math.max(0.6, r * 0.12));
				g.strokeLine(s.x - r * 1.1, s.y, s.x + r * 1.1, s.y);
				g.strokeLine(s.x, s.y - r * 1.1, s.x, s.y + r * 1.1);
			}
		}
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
	}

	/** A dim player shape (cards), {@code k} pixels per model unit. */
	private static void silhouette(GraphicsContext g, double cx, double cy, double k) {
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
		g.setFill(Color.rgb(42, 42, 52));
		g.fillRoundRect(cx - 4 * k, cy - 20 * k, 8 * k, 8 * k, k, k);
		g.fillRoundRect(cx - 4 * k, cy - 12 * k, 8 * k, 12 * k, k, k);
		g.setFill(Color.rgb(36, 36, 45));
		g.fillRoundRect(cx - 8 * k, cy - 12 * k, 4 * k, 12 * k, k, k);
		g.fillRoundRect(cx + 4 * k, cy - 12 * k, 4 * k, 12 * k, k, k);
		g.setFill(Color.rgb(38, 38, 47));
		g.fillRoundRect(cx - 4 * k, cy, 8 * k, 10 * k, k, k);
	}
}
