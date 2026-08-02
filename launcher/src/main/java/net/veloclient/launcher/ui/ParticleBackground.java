package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Layered, blocky, Minecraft-flavored animated title-screen background
 * (design spec section 4): a soft accent-colored glow behind the title, a
 * slowly scrolling faint grid (evoking chunk/block grids), drifting
 * pixel-square embers that twinkle, and a handful of small floating
 * isometric "cubes" that slowly tumble - a 2D nod to Minecraft's rotating
 * panorama without needing real 3D world rendering.
 */
public final class ParticleBackground extends Canvas {

	private final List<Ember> embers = new ArrayList<>();
	private final List<FloatingCube> cubes = new ArrayList<>();
	private Color accent = Color.web("#FF4444");
	private AnimationTimer timer;
	private double gridOffset;
	private long lastNanos = -1;

	private static final class Ember {
		double x, y, vy, size, phase, twinkleSpeed;
	}

	private static final class FloatingCube {
		double x, y, vx, vy, size, rotation, rotationSpeed;
	}

	public ParticleBackground(double width, double height, int emberCount) {
		super(width, height);
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < emberCount; i++) {
			Ember e = new Ember();
			e.x = random.nextDouble(width);
			e.y = random.nextDouble(height);
			e.vy = random.nextDouble(-14, -4);
			e.size = random.nextDouble(2, 5);
			e.phase = random.nextDouble(0, Math.PI * 2);
			e.twinkleSpeed = random.nextDouble(0.8, 2.0);
			embers.add(e);
		}
		for (int i = 0; i < 6; i++) {
			FloatingCube c = new FloatingCube();
			c.x = random.nextDouble(width);
			c.y = random.nextDouble(height);
			c.vx = random.nextDouble(-4, 4);
			c.vy = random.nextDouble(-3, 3);
			c.size = random.nextDouble(14, 30);
			c.rotation = random.nextDouble(0, 1);
			c.rotationSpeed = random.nextDouble(-0.15, 0.15);
			cubes.add(c);
		}
		widthProperty().addListener((obs, o, n) -> draw());
		heightProperty().addListener((obs, o, n) -> draw());
	}

	public void setDotColor(Color color) {
		this.accent = color;
	}

	public void start() {
		if (timer != null) {
			return;
		}
		lastNanos = -1;
		timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				double deltaSeconds = lastNanos < 0 ? 0 : Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
				lastNanos = now;
				step(deltaSeconds);
				draw();
			}
		};
		timer.start();
	}

	public void stop() {
		if (timer != null) {
			timer.stop();
			timer = null;
		}
	}

	private void step(double dt) {
		double width = getWidth();
		double height = getHeight();
		gridOffset = (gridOffset + dt * 6) % 48;

		for (Ember e : embers) {
			e.y += e.vy * dt;
			e.phase += e.twinkleSpeed * dt;
			if (e.y < -10) {
				e.y = height + 10;
				e.x = ThreadLocalRandom.current().nextDouble(width);
			}
		}
		for (FloatingCube c : cubes) {
			c.x += c.vx * dt;
			c.y += c.vy * dt;
			c.rotation += c.rotationSpeed * dt;
			if (c.x < -40) c.x = width + 40;
			if (c.x > width + 40) c.x = -40;
			if (c.y < -40) c.y = height + 40;
			if (c.y > height + 40) c.y = -40;
		}
	}

	private void draw() {
		double width = getWidth();
		double height = getHeight();
		GraphicsContext gc = getGraphicsContext2D();
		gc.clearRect(0, 0, width, height);

		// Layer 1: soft radial glow centered above middle, where the title sits.
		RadialGradient glow = new RadialGradient(0, 0, width / 2.0, height * 0.38, Math.max(width, height) * 0.55,
				false, CycleMethod.NO_CYCLE,
				new Stop(0, accent.deriveColor(0, 1, 1, 0.16)),
				new Stop(1, accent.deriveColor(0, 1, 1, 0)));
		gc.setFill(glow);
		gc.fillRect(0, 0, width, height);

		// Layer 2: faint scrolling grid, like chunk borders drifting past.
		gc.setStroke(Color.color(1, 1, 1, 0.035));
		gc.setLineWidth(1);
		for (double x = -48 + gridOffset; x < width; x += 48) {
			gc.strokeLine(x, 0, x, height);
		}
		for (double y = -48 + gridOffset; y < height; y += 48) {
			gc.strokeLine(0, y, width, y);
		}

		// Layer 3: floating isometric cubes (simplified 3-face blocks).
		for (FloatingCube c : cubes) {
			drawCube(gc, c);
		}

		// Layer 4: drifting twinkling pixel-square embers.
		for (Ember e : embers) {
			double twinkle = 0.35 + 0.4 * Math.abs(Math.sin(e.phase));
			gc.setFill(accent.deriveColor(0, 1, 1, twinkle));
			gc.fillRect(e.x - e.size / 2, e.y - e.size / 2, e.size, e.size);
		}
	}

	private void drawCube(GraphicsContext gc, FloatingCube c) {
		double s = c.size;
		double wobble = Math.sin(c.rotation * Math.PI * 2) * 0.25 + 0.75;
		double topAlpha = 0.10 * wobble;
		double leftAlpha = 0.07 * wobble;
		double rightAlpha = 0.045 * wobble;

		double halfW = s * 0.5;
		double halfH = s * 0.29;

		// Top face (diamond).
		gc.setFill(Color.color(1, 1, 1, topAlpha));
		gc.fillPolygon(
				new double[] {c.x, c.x + halfW, c.x, c.x - halfW},
				new double[] {c.y - halfH * 2, c.y - halfH, c.y, c.y - halfH},
				4);
		// Left face.
		gc.setFill(Color.color(1, 1, 1, leftAlpha));
		gc.fillPolygon(
				new double[] {c.x - halfW, c.x, c.x, c.x - halfW},
				new double[] {c.y - halfH, c.y, c.y + halfH * 2, c.y + halfH},
				4);
		// Right face.
		gc.setFill(Color.color(1, 1, 1, rightAlpha));
		gc.fillPolygon(
				new double[] {c.x + halfW, c.x, c.x, c.x + halfW},
				new double[] {c.y - halfH, c.y, c.y + halfH * 2, c.y + halfH},
				4);
	}

	@Override
	public boolean isResizable() {
		return true;
	}

	@Override
	public double prefWidth(double height) {
		return getWidth();
	}

	@Override
	public double prefHeight(double width) {
		return getHeight();
	}
}
