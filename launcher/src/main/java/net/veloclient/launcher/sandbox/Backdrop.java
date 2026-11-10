package net.veloclient.launcher.sandbox;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.SplittableRandom;

/**
 * The dusk sky behind every mini-game - the Home Ridgeline redrawn on the canvas with real parallax: stars, the square
 * moon, blocky clouds and three ridge layers that slide at different speeds as the camera moves.
 */
final class Backdrop {

	private Color accent;
	private final Color background;
	private final Color baseAccent;
	private LinearGradient sky;
	private double skyH;
	private double[][] stars;
	private double starsW;
	private final Color[] ridge = new Color[3];
	private Color mist;
	/** Forest silhouettes on the nearest ridge (overworld only). */
	double trees = 1;

	Backdrop(Color accent, Color background) {
		this.accent = accent;
		this.baseAccent = accent;
		this.background = background;
		ridge[0] = mix(background, accent, 0.36);
		ridge[1] = mix(background, accent, 0.21);
		ridge[2] = mix(background, accent, 0.11);
		mist = mix(background, accent, 0.4).deriveColor(0, 1, 1, 0.22);
	}

	Color accent() {
		return baseAccent;
	}

	/** Shifts the sky's colour (biomes); small changes are ignored so the gradients aren't rebuilt every frame. */
	void tint(Color c) {
		if (Math.abs(c.getRed() - accent.getRed()) + Math.abs(c.getGreen() - accent.getGreen()) + Math.abs(c.getBlue() - accent.getBlue()) < 0.012) {
			return;
		}
		accent = c;
		ridge[0] = mix(background, accent, 0.36);
		ridge[1] = mix(background, accent, 0.21);
		ridge[2] = mix(background, accent, 0.11);
		mist = mix(background, accent, 0.4).deriveColor(0, 1, 1, 0.22);
		sky = null;
	}

	Color background() {
		return background;
	}

	/** @param scrollX camera position in screen pixels (world scroll), {@code scrollY} likewise. */
	void draw(GraphicsContext g, double w, double h, double scrollX, double scrollY, double t) {
		if (sky == null || skyH != h) {
			skyH = h;
			sky = new LinearGradient(0, 0, 0, h, false, CycleMethod.NO_CYCLE,
					new Stop(0, mix(background, Color.BLACK, 0.15)),
					new Stop(0.36, mix(background, accent, 0.03)),
					new Stop(0.58, mix(background, accent, 0.26)),
					new Stop(0.76, mix(background, accent, 0.07)),
					new Stop(1, mix(background, Color.BLACK, 0.1)));
		}
		double lift = Math.max(-h * 0.3, Math.min(h * 0.5, -scrollY * 0.04));
		g.setFill(sky);
		g.fillRect(0, 0, w, h);
		if (stars == null || starsW != w) {
			starsW = w;
			SplittableRandom random = new SplittableRandom(3);
			stars = new double[60][4];
			for (double[] star : stars) {
				star[0] = random.nextDouble() * w;
				star[1] = random.nextDouble() * h * 0.5;
				star[2] = random.nextDouble() < 0.25 ? 2 : 1;
				star[3] = random.nextDouble() * 10;
			}
		}
		for (double[] star : stars) {
			double x = Math.floorMod((long) (star[0] - scrollX * 0.01), (long) Math.max(1, w));
			double a = 0.25 + 0.35 * (0.5 + 0.5 * Math.sin(t * (0.6 + star[3] * 0.07) + star[3]));
			g.setFill(Color.web("#F4E6E2", a));
			g.fillRect(x, star[1] + lift * 0.2, star[2], star[2]);
		}
		// moon
		double mx = Math.round(w * 0.78 - scrollX * 0.015);
		double my = Math.round(h * 0.17 + lift * 0.3);
		double size = Math.max(14, Math.round(h * 0.055));
		g.setFill(new RadialGradient(0, 0, mx + size / 2, my + size / 2, size * 2.6, false, CycleMethod.NO_CYCLE,
				new Stop(0, Color.rgb(255, 225, 215, 0.22)), new Stop(1, Color.TRANSPARENT)));
		g.fillRect(mx - size * 2.2, my - size * 2.2, size * 5.4, size * 5.4);
		g.setFill(Color.web("#EFE2DC"));
		g.fillRect(mx, my, size, size);
		g.setFill(Color.web("#D8C8C2"));
		g.fillRect(mx + size * 0.25, my + size * 0.3, size * 0.2, size * 0.2);
		g.fillRect(mx + size * 0.6, my + size * 0.6, size * 0.15, size * 0.15);
		// blocky clouds
		g.setFill(mix(background, accent, 0.18).deriveColor(0, 1, 1, 0.5));
		for (int c = 0; c < 4; c++) {
			double span = w + 300;
			double cx = Math.floorMod((long) (c * 337 + t * (6 + c * 2) - scrollX * 0.04), (long) span) - 150;
			double cy = h * (0.12 + c * 0.07) + lift * 0.35;
			double unit = 10;
			g.fillRect(cx, cy, unit * 8, unit);
			g.fillRect(cx + unit * 2, cy - unit, unit * 4, unit);
			g.fillRect(cx + unit * 5, cy + unit, unit * 5, unit);
		}
		// ridges, far to near
		double[][] layers = {{0.527, 0.114, 4, 0.10}, {0.618, 0.10, 6, 0.22}, {0.723, 0.077, 9, 0.38}};
		for (int l = 0; l < 3; l++) {
			double[] layer = layers[l];
			double step = 12;
			double shift = scrollX * layer[3] + t * (2 + l);
			double offset = shift % step;
			g.setFill(ridge[l]);
			double baseY = layer[0] * h + lift * (0.5 + l * 0.3);
			for (double sx = -offset - step; sx < w + step; sx += step) {
				double world = (sx + shift) / (h * 0.32);
				double n = World.fractal(17 + l * 13L, world, 3);
				double top = Math.round((baseY - (n - 0.5) * 2 * layer[1] * h) / 12) * 12;
				g.fillRect(Math.floor(sx), top, step + 1, h - top);
				if (l >= 1 && trees > 0.05) {
					long cell = (long) Math.floor((sx + shift) / step);
					double hv = World.hash(31 + l, cell);
					if (hv < 0.22 * trees) {
						// a blocky pine on the ridge line
						double th = (l == 2 ? 30 : 18) * (0.7 + hv * 2);
						double tw = step * (l == 2 ? 1.6 : 1.1);
						for (int k = 0; k < 4; k++) {
							double ww = tw * (1 - k * 0.22);
							g.fillRect(Math.floor(sx + step / 2 - ww / 2), top - th * (k + 1) / 4, Math.ceil(ww), Math.ceil(th / 4 + 1));
						}
					}
				}
			}
			if (l == 1) {
				g.setFill(new LinearGradient(0, h * 0.57 + lift, 0, h * 0.82 + lift, false, CycleMethod.NO_CYCLE,
						new Stop(0, Color.TRANSPARENT), new Stop(0.5, mist), new Stop(1, Color.TRANSPARENT)));
				g.fillRect(0, h * 0.57 + lift, w, h * 0.25);
			}
		}
	}

	static Color mix(Color a, Color b, double t) {
		return new Color(a.getRed() + (b.getRed() - a.getRed()) * t, a.getGreen() + (b.getGreen() - a.getGreen()) * t,
				a.getBlue() + (b.getBlue() - a.getBlue()) * t, 1);
	}
}
