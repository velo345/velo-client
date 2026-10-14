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

	/** A glow, or (shape {@link #LINE}) a beam from (x, y) to (x2, y2) {@code radius} wide. */
	private record Sprite(double x, double y, double depth, double radius, int rgb, double alpha, int shape, double x2, double y2) {
	}

	private static final int LINE = 99;

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

	/** A Store card's picture: the aura around a dim player shape, turning slowly. */
	public static Node card(AuraEffects.Kind kind, Supplier<AuraEffects.Settings> settings, double width, double height) {
		Canvas canvas = new Canvas(width, height);
		AuraEffects.Emitter emitter = new AuraEffects.Emitter(kind);
		AnimationTimer timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
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

	/** Draws the sprites behind ({@code front} false) or in front of the player. */
	private static void draw(GraphicsContext g, List<Sprite> sprites, double distance, boolean front) {
		g.setGlobalBlendMode(BlendMode.ADD);
		for (Sprite s : sprites) {
			if (s.depth < 0 != front) {
				continue;
			}
			Color c = Color.rgb((s.rgb >> 16) & 255, (s.rgb >> 8) & 255, s.rgb & 255, Math.min(1, s.alpha));
			if (s.shape == LINE) {
				// A soft glow around a sharper line.
				g.setLineCap(StrokeLineCap.ROUND);
				g.setStroke(c.deriveColor(0, 1, 1, 0.35));
				g.setLineWidth(Math.max(1.5, s.radius * 2.2));
				g.strokeLine(s.x, s.y, s.x2, s.y2);
				g.setStroke(c);
				g.setLineWidth(Math.max(0.8, s.radius * 0.8));
				g.strokeLine(s.x, s.y, s.x2, s.y2);
				continue;
			}
			double r = Math.max(1, s.radius);
			double ry = s.shape == AuraEffects.DISC ? r * 0.22 : r;
			g.setFill(new RadialGradient(0, 0, s.x, s.y, r, false, CycleMethod.NO_CYCLE,
					new Stop(0, c), new Stop(0.35, c.deriveColor(0, 1, 1, 0.45)), new Stop(1, Color.TRANSPARENT)));
			if (s.shape == AuraEffects.DISC) {
				g.save();
				g.translate(s.x, s.y);
				g.scale(1, ry / r);
				g.setFill(new RadialGradient(0, 0, 0, 0, r, false, CycleMethod.NO_CYCLE,
						new Stop(0, c), new Stop(0.5, c.deriveColor(0, 1, 1, 0.35)), new Stop(1, Color.TRANSPARENT)));
				g.fillOval(-r, -r, r * 2, r * 2);
				g.restore();
				continue;
			}
			g.fillOval(s.x - r, s.y - r, r * 2, r * 2);
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
