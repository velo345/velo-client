package net.veloclient.launcher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.io.ByteArrayInputStream;

/**
 * Renders a player head from a full skin PNG by stacking two nearest-neighbor
 * upscaled crops (base 8x8 head at (8,8), hat-layer overlay 8x8 at (40,8)).
 *
 * <p>An earlier version used an {@code ImageView} viewport + {@code fitWidth}
 * with {@code setSmooth(false)} to do this crop-and-scale, but JavaFX's
 * software/Prism scaling still visibly filtered/blurred an 8px source
 * stretched ~4-12x despite that flag - the reliable fix is scaling the
 * pixels ourselves (nearest-neighbor, one square per source pixel) into a
 * plain {@code WritableImage}, which every render path draws as-is with no
 * further interpolation.
 */
public final class PlayerHeadView {

	private PlayerHeadView() {
	}

	/** @return the rendered head, or null if {@code skinPngBytes} is null/unreadable (caller should fall back to a placeholder). */
	public static StackPane build(byte[] skinPngBytes, double size) {
		if (skinPngBytes == null) {
			return null;
		}
		try {
			Image skin = new Image(new ByteArrayInputStream(skinPngBytes));
			if (skin.isError() || skin.getWidth() < 64) {
				return null;
			}
			int pixels = (int) Math.round(size);
			WritableImage composed = new WritableImage(pixels, pixels);
			PixelReader reader = skin.getPixelReader();
			blitLayer(reader, composed, 8, 8, pixels, false);
			blitLayer(reader, composed, 40, 8, pixels, true);

			ImageView view = new ImageView(composed);
			view.setSmooth(false);
			StackPane pane = new StackPane(view);
			pane.setPrefSize(size, size);
			pane.setMinSize(size, size);
			pane.setMaxSize(size, size);
			return pane;
		} catch (Exception e) {
			return null;
		}
	}

	/** Nearest-neighbor upscales the skin's 8x8 region at ({@code u},{@code v}) into {@code dest}, skipping fully-transparent source pixels when {@code skipTransparent} (so the hat layer doesn't paint over the base layer with blank pixels where a skin has no overlay art). */
	private static void blitLayer(PixelReader reader, WritableImage dest, int u, int v, int destSize, boolean skipTransparent) {
		PixelWriter writer = dest.getPixelWriter();
		double scale = destSize / 8.0;
		for (int dy = 0; dy < destSize; dy++) {
			int sy = v + Math.min(7, (int) (dy / scale));
			for (int dx = 0; dx < destSize; dx++) {
				int sx = u + Math.min(7, (int) (dx / scale));
				Color color = reader.getColor(sx, sy);
				if (skipTransparent && color.getOpacity() < 0.05) {
					continue;
				}
				writer.setColor(dx, dy, color);
			}
		}
	}
}
