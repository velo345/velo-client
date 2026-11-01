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
 * Glows, glyphs and curtains are cached pictures. Live views draw with normal blending (a little brighter to make
 * up for it): a Canvas in ADD mode composites every single particle separately, which made dense, fast auras crawl -
 * and a blend mode on the canvas node would push the 3D viewer into offscreen rendering (see the JavaFX effect
 * trap). Only the one-off stills use ADD. Frames are capped at ~40 fps and particles at {@link #MAX_SPRITES}.
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
	private record Sprite(double x, double y, double depth, double radius, int rgb, double alpha, int shape, double x2, double y2, double[] corners) {
		Sprite(double x, double y, double depth, double radius, int rgb, double alpha, int shape, double x2, double y2) {
			this(x, y, depth, radius, rgb, alpha, shape, x2, y2, null);
		}
	}

	private static final int LINE = 99;
	private static final int PANEL = 98;
	/** A free quad (wings, ribbons): {@code corners} on screen, atlas cell {@code (int) y2}. */
	private static final int QUAD = 97;
	/** A newer atlas shape ({@code shape} - {@link #ATLAS_SHAPE}), turned by {@code x2}. */
	private static final int ATLAS_SHAPE = 1 << 12;
	/** Code-rain characters the default font surely has (the game's atlas has katakana; fonts here might not). */
	private static final String GLYPHS = "0123457ABCDEFHXZ";
	private static final javafx.scene.text.Font GLYPH_FONT = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 12);

	private static final int MAX_SPRITES = 700;
	private static final long FRAME_NANOS = 25_000_000L;

	private AuraFx() {
	}

	/** Shows {@code shown} (re-read every frame) around the player of a viewer made by PlayerSkin3DView. */
	public static void attach(Node viewer, Supplier<Shown> shown) {
		// Performance mode: no auras on previews.
		if (net.veloclient.launcher.data.LauncherSettings.get().performanceMode) {
			return;
		}
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
			private long lastDraw;

			@Override
			public void handle(long now) {
				if (now - lastDraw < FRAME_NANOS || !RenderGate.visible(wrapper)) {
					return;
				}
				lastDraw = now;
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
				draw(back.getGraphicsContext2D(), sprites, distance, false, false);
				draw(front.getGraphicsContext2D(), sprites, distance, true, false);
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
			draw(g, sprites, distance, false, true);
			silhouette(g, width / 2, height / 2 - 6, focal / distance);
			draw(g, sprites, distance, true, true);
			javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
			params.setFill(Color.TRANSPARENT);
			return canvas.snapshot(params, null);
		});
	}

	/**
	 * The frames of a looping card picture (the aura around the dim player shape, swaying gently so the turn loops)
	 * - exported as animated previews for the website and the owner panel by {@code AuraPreviewExport}. FX thread.
	 */
	public static List<javafx.scene.image.WritableImage> previewFrames(AuraEffects.Kind kind, int width, int height, int frames, int fps) {
		return previewFrames(kind, width, height, frames, fps, 0);
	}

	/**
	 * {@code layer}: 0 = the whole card (particles + the grey stand-in body), 1 = only the particles behind the body, 2 =
	 * only those in front - the website lays 1 under its real 3D player and 2 over it, so no stand-in body shows.
	 */
	public static List<javafx.scene.image.WritableImage> previewFrames(AuraEffects.Kind kind, int width, int height, int frames, int fps, int layer) {
		AuraEffects.Settings settings = AuraEffects.Settings.DEFAULT;
		AuraEffects.Emitter emitter = new AuraEffects.Emitter(kind);
		for (int i = 0; i < 120; i++) {
			emitter.update(1 / 30f, settings, 0, 0, 0);
		}
		double focal = (height / 2.0) / Math.tan(Math.toRadians(15));
		double distance = 95;
		Canvas canvas = new Canvas(width, height);
		GraphicsContext g = canvas.getGraphicsContext2D();
		javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
		params.setFill(Color.TRANSPARENT);
		List<javafx.scene.image.WritableImage> out = new ArrayList<>();
		for (int i = 0; i < frames; i++) {
			emitter.update(1f / fps, settings, 0, 0, 0);
			double angle = 0.5 + 0.35 * Math.sin(2 * Math.PI * i / frames);
			List<Sprite> sprites = project(emitter, settings, width / 2.0, height / 2.0 - 6, focal, distance, angle);
			clear(canvas);
			if (layer != 2) {
				draw(g, sprites, distance, false, true);
			}
			if (layer == 0) {
				silhouette(g, width / 2.0, height / 2.0 - 6, focal / distance);
			}
			if (layer != 1) {
				draw(g, sprites, distance, true, true);
			}
			out.add(canvas.snapshot(params, null));
		}
		return out;
	}

	/** A Store card's picture: the aura around a dim player shape, turning slowly. */
	public static Node card(AuraEffects.Kind kind, Supplier<AuraEffects.Settings> settings, double width, double height) {
		// Behind / player shape (drawn once) / in front.
		Canvas back = new Canvas(width, height);
		Canvas shape = new Canvas(width, height);
		Canvas front = new Canvas(width, height);
		double focal0 = (height / 2) / Math.tan(Math.toRadians(15));
		silhouette(shape.getGraphicsContext2D(), width / 2, height / 2 - 6, focal0 / 95);
		StackPane canvas = new StackPane(back, shape, front);
		canvas.setMouseTransparent(true);
		AuraEffects.Emitter emitter = new AuraEffects.Emitter(kind);
		AnimationTimer timer = new AnimationTimer() {
			private long lastDraw;

			@Override
			public void handle(long now) {
				// Cards animate at ~30 fps: a page of ten of them at the full refresh rate was most of the Store's cost.
				if (now - lastDraw < 33_000_000L || RenderGate.scrolling() || !RenderGate.visible(canvas)) {
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
				clear(back);
				clear(front);
				draw(back.getGraphicsContext2D(), sprites, distance, false, false);
				draw(front.getGraphicsContext2D(), sprites, distance, true, false);
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

	// ---- Kill effects (KillFxEffects): the same sprites, a wider shot ----

	/** How far back the camera stands for kill effects (model units; KillFxEffects.previewFraming scales it for tall ones). */
	private static final double KILL_DISTANCE = 190;

	/** The burst seen at {@code angle}, facing that camera. */
	private static List<Sprite> projectKill(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Burst burst, AuraEffects.Settings settings,
			double width, double height, double angle) {
		float[] framing = net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.previewFraming(burst.kind());
		double distance = KILL_DISTANCE * framing[1];
		double focal = (height / 2) / Math.tan(Math.toRadians(15));
		double k = focal / distance;
		return project(out -> burst.draw(settings, out), width / 2, height / 2 + (framing[0] * UNIT - FEET_Y) * k, focal, distance, angle);
	}

	private static net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Burst killBurst(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Kind kind,
			double angle) {
		return new net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Burst(kind, 7).facing(Math.sin(angle), -Math.cos(angle));
	}

	/** A Store card / page picture of a kill effect: it plays over and over, on a dim figure until the hit. */
	public static Node killFxCard(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Kind kind, Supplier<AuraEffects.Settings> settings,
			double width, double height, Runnable onStart) {
		Canvas back = new Canvas(width, height);
		Canvas shape = new Canvas(width, height);
		Canvas front = new Canvas(width, height);
		StackPane canvas = new StackPane(back, shape, front);
		canvas.setMouseTransparent(true);
		double angle = 0.45;
		float[] framing = net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.previewFraming(kind);
		double focal = (height / 2) / Math.tan(Math.toRadians(15));
		double k = focal / (KILL_DISTANCE * framing[1]);
		double cy = height / 2 + (framing[0] * UNIT - FEET_Y) * k;
		var burst = new Object() {
			net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Burst value;
			double idle = 0.4;
		};
		AnimationTimer timer = new AnimationTimer() {
			private long last;

			@Override
			public void handle(long now) {
				if (now - last < 33_000_000L || !RenderGate.visible(canvas)) {
					return;
				}
				float dt = last == 0 ? 0 : (float) Math.min(0.1, (now - last) / 1e9);
				last = now;
				if (burst.value == null || burst.value.done()) {
					burst.idle += dt;
					if (burst.idle < (burst.value == null ? 0.4 : 0.8)) {
						clear(back);
						clear(front);
						return;
					}
					burst.value = killBurst(kind, angle);
					burst.idle = 0;
					if (onStart != null) {
						onStart.run();
					}
				}
				burst.value.update(dt, settings.get());
				List<Sprite> sprites = projectKill(burst.value, settings.get(), width, height, angle);
				clear(back);
				clear(front);
				clear(shape);
				// The victim stands there until the hit.
				double fade = Math.max(0, 1 - burst.value.time() * 5);
				if (fade > 0) {
					GraphicsContext g = shape.getGraphicsContext2D();
					g.setGlobalAlpha(fade);
					silhouette(g, width / 2, cy, k);
					g.setGlobalAlpha(1);
				}
				draw(back.getGraphicsContext2D(), sprites, KILL_DISTANCE, false, false);
				draw(front.getGraphicsContext2D(), sprites, KILL_DISTANCE, true, false);
			}
		};
		canvas.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				timer.stop();
				// Left the page: its sound goes with it.
				KillFxSound.stop();
			} else {
				timer.start();
			}
		});
		// Starts the effect over right now (onStart runs with it - a sound starts in step with the picture).
		canvas.getProperties().put(KILL_RESTART, (Runnable) () -> {
			burst.value = null;
			burst.idle = 10;
		});
		return canvas;
	}

	/** The key of a kill effect card's "start over now" Runnable in its node properties. */
	public static final String KILL_RESTART = "velo.killfx.restart";

	/** Starts a card made by {@link #killFxCard} over now. */
	public static void restartKillFx(Node card) {
		if (card != null && card.getProperties().get(KILL_RESTART) instanceof Runnable restart) {
			restart.run();
		}
	}

	/** A still of a kill effect at its most striking moment (cards in long lists). FX thread; cached. */
	public static javafx.scene.image.Image killFxSnapshot(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Kind kind, AuraEffects.Settings settings,
			double width, double height) {
		return SNAPSHOTS.computeIfAbsent("killfx:" + kind + "@" + settings.encode() + "@" + (int) width + "x" + (int) height, key -> {
			var burst = killBurst(kind, 0.45);
			for (int i = 0; i < Math.round(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.highlight(kind) * 30); i++) {
				burst.update(1 / 30f, settings);
			}
			Canvas canvas = new Canvas(width, height);
			GraphicsContext g = canvas.getGraphicsContext2D();
			List<Sprite> sprites = projectKill(burst, settings, width, height, 0.45);
			draw(g, sprites, KILL_DISTANCE, false, true);
			draw(g, sprites, KILL_DISTANCE, true, true);
			javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
			params.setFill(Color.TRANSPARENT);
			return canvas.snapshot(params, null);
		});
	}

	/** Every frame of one play of a kill effect (exported as the website's previews by AuraPreviewExport). FX thread. */
	public static List<javafx.scene.image.WritableImage> killFxFrames(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.Kind kind, int width,
			int height, int fps) {
		AuraEffects.Settings settings = AuraEffects.Settings.DEFAULT;
		var burst = killBurst(kind, 0.45);
		float[] framing = net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.previewFraming(kind);
		double focal = (height / 2.0) / Math.tan(Math.toRadians(15));
		double k = focal / (KILL_DISTANCE * framing[1]);
		double cy = height / 2.0 + (framing[0] * UNIT - FEET_Y) * k;
		Canvas canvas = new Canvas(width, height);
		GraphicsContext g = canvas.getGraphicsContext2D();
		javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
		params.setFill(Color.TRANSPARENT);
		List<javafx.scene.image.WritableImage> out = new ArrayList<>();
		int frames = (int) Math.ceil((net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.duration(kind) + 0.9) * fps);
		for (int i = 0; i < frames; i++) {
			// A moment of the victim standing, then the effect.
			if (i >= fps / 3) {
				burst.update(1f / fps, settings);
			}
			clear(canvas);
			double fade = i < fps / 3 ? 1 : Math.max(0, 1 - burst.time() * 5);
			List<Sprite> sprites = projectKill(burst, settings, width, height, 0.45);
			draw(g, sprites, KILL_DISTANCE, false, true);
			if (fade > 0) {
				g.setGlobalAlpha(fade);
				silhouette(g, width / 2.0, cy, k);
				g.setGlobalAlpha(1);
			}
			draw(g, sprites, KILL_DISTANCE, true, true);
			out.add(canvas.snapshot(params, null));
		}
		return out;
	}

	private static void clear(Canvas canvas) {
		GraphicsContext g = canvas.getGraphicsContext2D();
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
		g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
	}

	/** The aura's sprites on screen (model space: x right, y down, feet at FEET_Y; turned by {@code angle}). */
	private static List<Sprite> project(AuraEffects.Emitter emitter, AuraEffects.Settings settings, double cx, double cy, double focal,
			double distance, double angle) {
		return project(out -> emitter.draw(settings, 0, 0, 0, out), cx, cy, focal, distance, angle);
	}

	/** Whatever {@code drawer} draws, on screen (the same projection as an aura's). */
	private static List<Sprite> project(java.util.function.Consumer<AuraEffects.Out> drawer, double cx, double cy, double focal, double distance,
			double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		List<Sprite> out = new ArrayList<>();
		drawer.accept(new AuraEffects.Out() {
			@Override
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
				if ((shape & 0xFF) >= AuraEffects.HALO) {
					sprite(x, y, z, size, rgb, alpha, shape, 0);
					return;
				}
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
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape, float rot) {
				if ((shape & 0xFF) < AuraEffects.HALO) {
					sprite(x, y, z, size, rgb, alpha, shape);
					return;
				}
				double[] c = alpha <= 0.02f ? null : point(x, y, z);
				if (c != null) {
					out.add(new Sprite(c[0], c[1], c[2], size * UNIT * focal / c[3], rgb, alpha, ATLAS_SHAPE + shape, rot, 0));
				}
			}

			@Override
			public void quad(float[] q, int cell, int rgb, float alpha) {
				if (alpha <= 0.02f) {
					return;
				}
				double[] screen = new double[8];
				double depth = 0;
				for (int i = 0; i < 4; i++) {
					double[] c = point(q[i * 3], q[i * 3 + 1], q[i * 3 + 2]);
					if (c == null) {
						return;
					}
					screen[i * 2] = c[0];
					screen[i * 2 + 1] = c[1];
					depth += c[2] / 4;
				}
				out.add(new Sprite(screen[0], screen[1], depth, 0, rgb, alpha, QUAD, 0, cell, screen));
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
		if (out.size() > MAX_SPRITES) {
			// Very dense / fast settings: keep the brightest ones, drop the faint.
			out.sort((a, b) -> Double.compare(b.alpha * b.radius, a.alpha * a.radius));
			List<Sprite> kept = new ArrayList<>(out.subList(0, MAX_SPRITES));
			kept.sort((a, b) -> Double.compare(b.depth, a.depth));
			return kept;
		}
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

	private static javafx.scene.image.Image atlas;
	private static final java.util.Map<Integer, javafx.scene.image.Image> CELLS = new java.util.HashMap<>();
	private static final int CELL_PX = 64;

	/** Atlas cell {@code cell} tinted {@code rgb} (colours snapped to 16 levels), {@link #CELL_PX} px, made once. */
	private static javafx.scene.image.Image cellImage(int cell, int rgb) {
		int key = cell << 24 | (rgb & 0xF0F0F0);
		javafx.scene.image.Image cached = CELLS.get(key);
		if (cached != null) {
			return cached;
		}
		if (CELLS.size() > 800) {
			CELLS.clear();
		}
		if (atlas == null) {
			try (var in = AuraFx.class.getResourceAsStream("/net/veloclient/launcher/aura/atlas.png")) {
				atlas = in == null ? new javafx.scene.image.WritableImage(CELL_PX * AuraEffects.ATLAS_COLUMNS, CELL_PX * AuraEffects.ATLAS_ROWS)
						: new javafx.scene.image.Image(in, CELL_PX * AuraEffects.ATLAS_COLUMNS, CELL_PX * AuraEffects.ATLAS_ROWS, false, true);
			} catch (java.io.IOException e) {
				atlas = new javafx.scene.image.WritableImage(CELL_PX * AuraEffects.ATLAS_COLUMNS, CELL_PX * AuraEffects.ATLAS_ROWS);
			}
		}
		javafx.scene.image.PixelReader reader = atlas.getPixelReader();
		javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(CELL_PX, CELL_PX);
		javafx.scene.image.PixelWriter writer = image.getPixelWriter();
		int ox = (cell % AuraEffects.ATLAS_COLUMNS) * CELL_PX;
		int oy = (cell / AuraEffects.ATLAS_COLUMNS) * CELL_PX;
		int tint = rgb & 0xF0F0F0 | 0x080808;
		for (int y = 0; y < CELL_PX; y++) {
			for (int x = 0; x < CELL_PX; x++) {
				writer.setArgb(x, y, (reader.getArgb(ox + x, oy + y) & 0xFF000000) | tint);
			}
		}
		CELLS.put(key, image);
		return image;
	}

	private static final java.util.Map<Integer, javafx.scene.image.Image> CURTAINS = new java.util.HashMap<>();
	private static final java.util.Map<Integer, javafx.scene.image.Image> GLYPH_IMAGES = new java.util.HashMap<>();

	/** A light curtain (bright foot, fading up) in {@code rgb}, made once per colour. */
	private static javafx.scene.image.Image curtainImage(int rgb) {
		int key = rgb & 0xF0F0F0;
		return CURTAINS.computeIfAbsent(key, k -> {
			int w = 4;
			int h = 64;
			javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(w, h);
			javafx.scene.image.PixelWriter writer = image.getPixelWriter();
			for (int y = 0; y < h; y++) {
				double t = 1 - (y + 0.5) / h; // 1 at the foot (bottom)
				double a = t > 0.88 ? 1 : t * 0.62;
				for (int x = 0; x < w; x++) {
					writer.setArgb(x, y, ((int) Math.round(a * 255) << 24) | (k & 0xFFFFFF));
				}
			}
			return image;
		});
	}

	/** One code glyph in {@code rgb}, drawn once per character and colour (mirrored like in the game). */
	private static javafx.scene.image.Image glyphImage(int index, int rgb) {
		int key = index << 24 | (rgb & 0xF0F0F0);
		return GLYPH_IMAGES.computeIfAbsent(key, k -> {
			Canvas canvas = new Canvas(10, 14);
			GraphicsContext g = canvas.getGraphicsContext2D();
			g.translate(5, 7);
			g.scale(-1, 1);
			g.setFill(Color.rgb((rgb >> 16) & 0xF0, (rgb >> 8) & 0xF0, rgb & 0xF0));
			g.setFont(GLYPH_FONT);
			g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
			g.setTextBaseline(javafx.geometry.VPos.CENTER);
			g.fillText(String.valueOf(GLYPHS.charAt(index % GLYPHS.length())), 0, 0);
			javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
			params.setFill(Color.TRANSPARENT);
			return canvas.snapshot(params, null);
		});
	}

	/**
	 * Draws the sprites behind ({@code front} false) or in front of the player. {@code additive}: blend each sprite
	 * additively here (one-off stills); live canvases leave that to the canvas node's own blend mode.
	 */
	private static void draw(GraphicsContext g, List<Sprite> sprites, double distance, boolean front, boolean additive) {
		g.setGlobalBlendMode(additive ? BlendMode.ADD : BlendMode.SRC_OVER);
		double boost = additive ? 1.0 : 1.3;
		for (Sprite s : sprites) {
			if (s.depth < 0 != front) {
				continue;
			}
			Color c = Color.rgb((s.rgb >> 16) & 255, (s.rgb >> 8) & 255, s.rgb & 255, Math.min(1, s.alpha * boost));
			if (s.shape == PANEL) {
				int cell = (int) s.y2;
				double w = Math.max(1, s.radius);
				double h = Math.max(1, s.x2);
				g.setGlobalAlpha(Math.min(1, s.alpha * boost));
				if (cell >= AuraEffects.CELL_GLYPH) {
					// A code glyph: the character itself (cached picture), mirrored like in the game.
					double gw = w / 9.0 * 10;
					double gh = h / 12.0 * 14;
					g.drawImage(glyphImage(cell - AuraEffects.CELL_GLYPH, s.rgb), s.x - gw / 2, s.y - gh / 2, gw, gh);
				} else {
					// A light curtain: bright at the foot, fading upwards (cached gradient picture).
					g.drawImage(curtainImage(s.rgb), s.x - w / 2, s.y - h / 2, w, h);
				}
				g.setGlobalAlpha(1);
				continue;
			}
			if (s.shape == QUAD) {
				// The cell stretched onto the quad (an affine fit through its first, second and last corner).
				double[] q = s.corners;
				g.save();
				g.setGlobalAlpha(Math.min(1, s.alpha * boost));
				g.transform(q[6] - q[0], q[7] - q[1], q[2] - q[0], q[3] - q[1], q[0], q[1]);
				g.drawImage(cellImage((int) s.y2, s.rgb), 0, 0, 1, 1);
				g.restore();
				continue;
			}
			if (s.shape >= ATLAS_SHAPE) {
				int shape = s.shape - ATLAS_SHAPE;
				double r = Math.max(1, s.radius);
				g.save();
				g.setGlobalAlpha(Math.min(1, s.alpha * boost));
				g.translate(s.x, s.y);
				if (!AuraEffects.upright(shape)) {
					// Lying flat: seen from about head height, squashed.
					g.scale(1, 0.28);
				}
				g.rotate(Math.toDegrees(-s.x2));
				g.drawImage(cellImage(AuraEffects.cell(shape), s.rgb), -r, -r, r * 2, r * 2);
				g.restore();
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
			g.setGlobalAlpha(Math.min(1, s.alpha * boost));
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
