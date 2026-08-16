package net.veloclient.launcher.ui;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Picks a punchy accent color out of whatever's actually on screen for a
 * profile/server icon - the home screen's border glow (see {@code
 * LauncherApp#showHome}) needs one color regardless of whether that icon is
 * a custom-uploaded PNG, a built-in vector icon rendered as shapes, or a
 * server's favicon, so this works on a rasterized snapshot of whatever
 * {@link Node} is already rendering that icon rather than needing a
 * separate code path per icon representation.
 */
public final class IconColorExtractor {

	private IconColorExtractor() {
	}

	/** Snapshots {@code node} (works even off-scene, as long as it has a real size) and extracts its dominant color. */
	public static Color fromNode(Node node, Color fallback) {
		try {
			SnapshotParameters params = new SnapshotParameters();
			params.setFill(Color.TRANSPARENT);
			WritableImage image = node.snapshot(params, null);
			return fromImage(image, fallback);
		} catch (Exception e) {
			return fallback;
		}
	}

	public static Color fromImage(Image image, Color fallback) {
		if (image == null || image.isError()) {
			return fallback;
		}
		PixelReader reader = image.getPixelReader();
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();
		if (reader == null || w <= 0 || h <= 0) {
			return fallback;
		}
		int stepX = Math.max(1, w / 48);
		int stepY = Math.max(1, h / 48);
		double sumR = 0, sumG = 0, sumB = 0, weight = 0;
		for (int y = 0; y < h; y += stepY) {
			for (int x = 0; x < w; x += stepX) {
				Color c = reader.getColor(x, y);
				// Skip near-transparent (icon padding), near-black and
				// near-white pixels (backgrounds/outlines) - those would
				// otherwise dominate a flat-colored icon's average and wash
				// the result out toward gray.
				if (c.getOpacity() < 0.4) {
					continue;
				}
				double brightness = c.getBrightness();
				if (brightness < 0.08 || brightness > 0.97) {
					continue;
				}
				double sampleWeight = 0.2 + c.getSaturation();
				sumR += c.getRed() * sampleWeight;
				sumG += c.getGreen() * sampleWeight;
				sumB += c.getBlue() * sampleWeight;
				weight += sampleWeight;
			}
		}
		if (weight <= 0.0001) {
			return fallback;
		}
		Color average = Color.color(clamp(sumR / weight), clamp(sumG / weight), clamp(sumB / weight));
		// Boosted toward a punchier, more consistently-visible glow color
		// than a flat average alone would give (a mostly-desaturated icon's
		// raw average reads as dull gray, not as an accent).
		double saturation = Math.max(0.5, Math.min(1.0, average.getSaturation() * 1.35));
		double brightness = Math.max(0.55, Math.min(0.95, average.getBrightness() * 1.2));
		return Color.hsb(average.getHue(), saturation, brightness);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}
}
