package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import net.veloclient.launcher.data.EmoteCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A small shaded player figure doing an emote, drawn on a Canvas - cheap enough for a whole Store grid (a 3D SubScene
 * per card isn't). The emote's particles play along as glyphs (hearts, notes, sparkles). The same figure the game's
 * Store cards show. Animates only while on screen.
 */
public final class EmoteFigureFx {

	private static final double REST_SECONDS = 0.9;
	private static final Random RANDOM = new Random();
	private static final float[][] BOXES = {
			{-4, -8, -4, 8, 8, 8}, {-4, 0, -2, 8, 12, 4}, {-3, -2, -2, 4, 12, 4}, {-1, -2, -2, 4, 12, 4}, {-2, 0, -2, 4, 12, 4},
			{-2, 0, -2, 4, 12, 4}};
	private static final int[][] FACES = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
	static final float[] REST = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -5, 2, 0, 0, 0, 0, 5, 2, 0, 0, 0, 0, -1.9f, 12, 0, 0, 0, 0, 1.9f, 12, 0, 0, 0, 0};

	private record Sprite(String glyph, Color color, double x, double y, double vx, double vy, long born, double life) {
	}

	private EmoteFigureFx() {
	}

	/** A {@code width} x {@code height} canvas looping the emote (the figure stands on the bottom edge). */
	public static Canvas create(EmoteCatalog.EmoteType type, double width, double height) {
		Canvas canvas = new Canvas(width, height);
		Color accent = Color.web(type.accent());
		EmoteCatalog.Animation[] animation = {EmoteCatalog.ready(type)};
		if (animation[0] == null) {
			EmoteCatalog.load(type, a -> animation[0] = a);
		}
		List<Sprite> sprites = new ArrayList<>();
		long start = System.nanoTime();
		double[] fired = {-1};
		AnimationTimer timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				if (!RenderGate.active()) {
					return;
				}
				draw(canvas, animation[0], accent, (now - start) / 1e9, sprites, fired);
			}
		};
		canvas.sceneProperty().addListener((obs, o, n) -> {
			if (n == null) {
				timer.stop();
			} else {
				timer.start();
			}
		});
		draw(canvas, animation[0], accent, 0, sprites, fired);
		return canvas;
	}

	private static void draw(Canvas canvas, EmoteCatalog.Animation a, Color accent, double t, List<Sprite> sprites, double[] fired) {
		GraphicsContext g = canvas.getGraphicsContext2D();
		double w = canvas.getWidth();
		double h = canvas.getHeight();
		g.clearRect(0, 0, w, h);
		double scale = Math.min((h - 12) / 40.0, w / 30.0);
		double cx = w / 2;
		double footY = h - 6;
		float[] pose = REST.clone();
		if (a != null) {
			double cycle = a.duration() + (a.loop() ? 0 : REST_SECONDS);
			double local = t % cycle;
			if (local <= a.duration()) {
				a.pose(local * a.fps(), pose);
			}
			fire(a, t, cycle, pose, cx, footY, scale, sprites, fired);
		}
		drawPose(g, pose, cx, footY, scale, Math.toRadians(28 + Math.sin(t * 0.6) * 12), accent);
		long now = System.nanoTime();
		sprites.removeIf(s -> (now - s.born()) / 1e9 > s.life());
		g.setTextAlign(TextAlignment.CENTER);
		g.setFont(Font.font("System", FontWeight.BOLD, Math.max(9, scale * 3.2)));
		for (Sprite s : sprites) {
			double age = (now - s.born()) / 1e9;
			g.setGlobalAlpha(Math.max(0, 1 - age / s.life()));
			g.setFill(s.color());
			g.fillText(s.glyph(), s.x() + s.vx() * age, s.y() + s.vy() * age);
		}
		g.setGlobalAlpha(1);
		if (a == null) {
			g.setStroke(Color.rgb(255, 255, 255, 0.7));
			g.setLineWidth(1.5);
			g.strokeArc(cx - 5, footY - 38 * scale, 10, 10, -(t * 360) % 360, 260, javafx.scene.shape.ArcType.OPEN);
		}
	}

	/** The posed boxes, shaded, back to front (model pixels: y down, the player faces the viewer). */
	static void drawPose(GraphicsContext g, float[] pose, double cx, double footY, double scale, double yaw, Color accent) {
		double cos = Math.cos(yaw);
		double sin = Math.sin(yaw);
		Color skin = Color.web("#e3b08e");
		Color pants = Color.web("#3d3f86");
		Color[] colors = {skin, accent, skin, skin, pants, pants};
		record Face(double depth, double[] xs, double[] ys, Color color) {
		}
		List<Face> faces = new ArrayList<>();
		for (int part = 0; part < 6; part++) {
			double[][] c = corners(pose, part);
			for (double[] p : c) {
				double x = p[0];
				double z = p[2];
				p[0] = x * cos + z * sin;
				p[2] = -x * sin + z * cos;
			}
			double mx = 0, my = 0, mz = 0;
			for (double[] p : c) {
				mx += p[0] / 8;
				my += p[1] / 8;
				mz += p[2] / 8;
			}
			for (int[] f : FACES) {
				double[] a = c[f[0]], b = c[f[1]], d = c[f[3]], e = c[f[2]];
				double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
				double vx = d[0] - a[0], vy = d[1] - a[1], vz = d[2] - a[2];
				double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-6) {
					continue;
				}
				nx /= len;
				ny /= len;
				nz /= len;
				double fx = (a[0] + e[0]) / 2 - mx, fy = (a[1] + e[1]) / 2 - my, fz = (a[2] + e[2]) / 2 - mz;
				if (nx * fx + ny * fy + nz * fz < 0) {
					nx = -nx;
					ny = -ny;
					nz = -nz;
				}
				if (nz > 0.02) {
					continue;
				}
				double light = 0.55 + 0.45 * Math.max(0, -0.45 * nx - 0.6 * ny - 0.65 * nz);
				double[] xs = new double[4];
				double[] ys = new double[4];
				double depth = 0;
				for (int k = 0; k < 4; k++) {
					double[] p = c[f[k]];
					xs[k] = cx + p[0] * scale;
					ys[k] = footY + (p[1] - 24) * scale;
					depth += p[2];
				}
				Color base = colors[part];
				faces.add(new Face(depth / 4, xs, ys, base.deriveColor(0, 1, light, 1)));
			}
		}
		faces.sort(Comparator.comparingDouble(f -> -f.depth()));
		for (Face f : faces) {
			g.setFill(f.color());
			g.fillPolygon(f.xs(), f.ys(), 4);
			g.setStroke(f.color());
			g.setLineWidth(0.6);
			g.strokePolygon(f.xs(), f.ys(), 4);
		}
	}

	static double[][] corners(float[] pose, int part) {
		float[] b = BOXES[part];
		double[][] out = new double[8][];
		int k = 0;
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				for (int l = 0; l < 2; l++) {
					out[k++] = transform(pose, part, b[0] + b[3] * i, b[1] + b[4] * j, b[2] + b[5] * l);
				}
			}
		}
		return out;
	}

	/** A point of a part's own frame into model space (rotation Z * Y * X around the pivot, like ModelPart). */
	static double[] transform(float[] pose, int part, double lx, double ly, double lz) {
		int o = part * 6;
		double cx = Math.cos(pose[o + 3]), sx = Math.sin(pose[o + 3]);
		double y1 = ly * cx - lz * sx, z1 = ly * sx + lz * cx, x1 = lx;
		double cy = Math.cos(pose[o + 4]), sy = Math.sin(pose[o + 4]);
		double x2 = x1 * cy + z1 * sy, z2 = -x1 * sy + z1 * cy;
		double cz = Math.cos(pose[o + 5]), sz = Math.sin(pose[o + 5]);
		return new double[] {x2 * cz - y1 * sz + pose[o], x2 * sz + y1 * cz + pose[o + 1], z2 + pose[o + 2]};
	}

	/** Where an event's particles come from (model pixels). */
	static double[] anchor(float[] pose, String at) {
		return switch (at == null ? "chest" : at) {
			case "head" -> transform(pose, 0, 0, -10, 0);
			case "rhand" -> transform(pose, 2, -1, 10, 0);
			case "lhand" -> transform(pose, 3, 1, 10, 0);
			case "feet" -> new double[] {(pose[24] + pose[30]) / 2, 23, (pose[26] + pose[32]) / 2};
			case "ring" -> transform(pose, 1, 0, 10, 0);
			default -> transform(pose, 1, 0, 4, 0);
		};
	}

	/** A glyph and colour standing in for an emote effect sprite. */
	static Object[] glyph(EmoteCatalog.Event e) {
		String shape = e.particle() == null ? "glow" : e.particle();
		String g = switch (shape) {
			case "heart" -> "❤";
			case "note" -> "♪";
			case "notes" -> "♫";
			case "smoke", "glow" -> "●";
			case "snowflake" -> "❄";
			case "bat" -> "ᴥ";
			case "ghost" -> "☁";
			case "z" -> "Z";
			case "drop" -> "•";
			case "flame" -> "▲";
			case "square" -> "■";
			case "skull" -> "☠";
			case "bolt" -> "⚡";
			case "anger" -> "✖";
			case "ring", "bubble" -> "○";
			case "candy" -> "✚";
			case "sparkle" -> "✦";
			case "star" -> "✧";
			default -> shape.length() == 1 ? shape : e.text() != null && !e.text().isBlank() ? e.text().substring(0, 1) : "✧";
		};
		Color c;
		if (e.color() == null || "rainbow".equals(e.color())) {
			c = Color.hsb(RANDOM.nextDouble() * 360, 0.6, 1);
		} else {
			try {
				c = Color.web(e.color());
			} catch (IllegalArgumentException ex) {
				c = Color.WHITE;
			}
		}
		return new Object[] {g, c};
	}

	/** Anchors the flat figure knows (ground-level ones are its feet, fixed spots its chest). */
	static String anchorOf(String at) {
		if (at == null || at.startsWith("@")) {
			return "chest";
		}
		return switch (at) {
			case "ground", "front" -> "feet";
			case "above" -> "head";
			default -> at;
		};
	}

	private static void fire(EmoteCatalog.Animation a, double t, double cycle, float[] pose, double cx, double footY, double scale, List<Sprite> sprites,
			double[] fired) {
		double from = fired[0];
		fired[0] = t;
		int first = (int) Math.floor(Math.max(0, from) / cycle);
		int last = (int) Math.floor(t / cycle);
		for (int k = first; k <= last; k++) {
			for (EmoteCatalog.Event e : a.events()) {
				double at = k * cycle + e.t();
				if (e.particle() == null || at <= from || at > t) {
					continue;
				}
				double[] p = anchor(pose, anchorOf(e.at()));
				double ax = cx + p[0] * scale;
				double ay = footY + (p[1] - 24) * scale;
				int n = Math.min(6, Math.max(1, e.count() / 3));
				for (int i = 0; i < n; i++) {
					Object[] look = glyph(e);
					double spread = Math.max(2, e.spread() * 16 * scale);
					sprites.add(new Sprite((String) look[0], (Color) look[1], ax + (RANDOM.nextDouble() - 0.5) * spread,
							ay + (RANDOM.nextDouble() - 0.5) * spread, (RANDOM.nextDouble() - 0.5) * 10, -12 - e.up() * 60 - RANDOM.nextDouble() * 8,
							System.nanoTime(), 0.9 + RANDOM.nextDouble() * 0.5));
				}
			}
		}
		if (sprites.size() > 40) {
			sprites.subList(0, sprites.size() - 40).clear();
		}
	}
}
