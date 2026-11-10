package net.veloclient.launcher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;

/**
 * A skin drawn flat, straight from the front or back (2026-11 Home): head + hat, body + jacket, arms + sleeves, legs +
 * pants, composited into a 16x32 picture and scaled up with hard pixel edges. Slim skins get 3-pixel arms; old 64x32
 * skins mirror their right arm and leg. Built from int arrays in one setPixels call (never per-pixel setColor).
 */
public final class FlatSkin {

	private FlatSkin() {
	}

	/** {@code scale} screen pixels per skin pixel; null when the skin can't be read. */
	public static Image render(byte[] png, boolean slim, boolean back, int scale) {
		Image skin = new Image(new ByteArrayInputStream(png));
		if (skin.isError() || skin.getWidth() < 64 || skin.getHeight() < 32) {
			return null;
		}
		int sw = (int) skin.getWidth();
		int sh = (int) skin.getHeight();
		int unit = sw / 64;
		boolean legacy = sh * 2 == sw;
		PixelReader reader = skin.getPixelReader();
		int[] src = new int[sw * sh];
		reader.getPixels(0, 0, sw, sh, PixelFormat.getIntArgbInstance(), src, 0, sw);
		int[] out = new int[16 * 32];
		int arm = slim ? 3 : 4;
		if (!back) {
			part(src, sw, unit, 8, 8, 40, 8, 8, 8, out, 4, 0, false);
			part(src, sw, unit, 20, 20, 20, 36, 8, 12, out, 4, 8, false);
			part(src, sw, unit, 44, 20, 44, 36, arm, 12, out, 4 - arm, 8, false);
			if (legacy) {
				part(src, sw, unit, 44, 20, -1, -1, arm, 12, out, 12, 8, true);
				part(src, sw, unit, 4, 20, -1, -1, 4, 12, out, 8, 20, true);
			} else {
				part(src, sw, unit, 36, 52, 52, 52, arm, 12, out, 12, 8, false);
				part(src, sw, unit, 20, 52, 4, 52, 4, 12, out, 8, 20, false);
			}
			part(src, sw, unit, 4, 20, 4, 36, 4, 12, out, 4, 20, false);
		} else {
			part(src, sw, unit, 24, 8, 56, 8, 8, 8, out, 4, 0, false);
			part(src, sw, unit, 32, 20, 32, 36, 8, 12, out, 4, 8, false);
			int armBack = slim ? 51 : 52;
			part(src, sw, unit, armBack, 20, armBack, 36, arm, 12, out, 12, 8, false);
			if (legacy) {
				part(src, sw, unit, armBack, 20, -1, -1, arm, 12, out, 4 - arm, 8, true);
				part(src, sw, unit, 12, 20, -1, -1, 4, 12, out, 4, 20, true);
			} else {
				int leftArmBack = slim ? 43 : 44;
				part(src, sw, unit, leftArmBack, 52, leftArmBack + 16, 52, arm, 12, out, 4 - arm, 8, false);
				part(src, sw, unit, 28, 52, 12, 52, 4, 12, out, 4, 20, false);
			}
			part(src, sw, unit, 12, 20, 12, 36, 4, 12, out, 8, 20, false);
		}
		int[] scaled = new int[16 * scale * 32 * scale];
		int width = 16 * scale;
		for (int y = 0; y < 32 * scale; y++) {
			int row = (y / scale) * 16;
			for (int x = 0; x < width; x++) {
				scaled[y * width + x] = out[row + x / scale];
			}
		}
		WritableImage image = new WritableImage(width, 32 * scale);
		image.getPixelWriter().setPixels(0, 0, width, 32 * scale, PixelFormat.getIntArgbPreInstance(), premultiply(scaled), 0, width);
		return image;
	}

	/** Copies a w x h skin region (and its overlay, if any) into the 16x32 output at (dx, dy). */
	private static void part(int[] src, int sw, int unit, int bx, int by, int ox, int oy, int w, int h, int[] out, int dx, int dy, boolean mirror) {
		for (int pass = 0; pass < 2; pass++) {
			int sx0 = pass == 0 ? bx : ox;
			int sy0 = pass == 0 ? by : oy;
			if (sx0 < 0) {
				continue;
			}
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int sx = (sx0 + (mirror ? w - 1 - x : x)) * unit;
					int sy = (sy0 + y) * unit;
					int index = sy * sw + sx;
					if (index < 0 || index >= src.length) {
						continue;
					}
					int argb = src[index];
					int alpha = argb >>> 24;
					if (pass == 0) {
						out[(dy + y) * 16 + dx + x] = argb | 0xFF000000;
					} else if (alpha > 0) {
						out[(dy + y) * 16 + dx + x] = blend(out[(dy + y) * 16 + dx + x], argb);
					}
				}
			}
		}
	}

	private static int blend(int under, int over) {
		int a = over >>> 24;
		if (a >= 255) {
			return over;
		}
		int r = ((over >> 16 & 255) * a + (under >> 16 & 255) * (255 - a)) / 255;
		int g = ((over >> 8 & 255) * a + (under >> 8 & 255) * (255 - a)) / 255;
		int b = ((over & 255) * a + (under & 255) * (255 - a)) / 255;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int[] premultiply(int[] argb) {
		int[] out = new int[argb.length];
		for (int i = 0; i < argb.length; i++) {
			int a = argb[i] >>> 24;
			if (a == 255) {
				out[i] = argb[i];
			} else if (a != 0) {
				int r = (argb[i] >> 16 & 255) * a / 255, g = (argb[i] >> 8 & 255) * a / 255, b = (argb[i] & 255) * a / 255;
				out[i] = a << 24 | r << 16 | g << 8 | b;
			}
		}
		return out;
	}
}
