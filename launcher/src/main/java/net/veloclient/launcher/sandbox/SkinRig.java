package net.veloclient.launcher.sandbox;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;

/**
 * The player's Minecraft skin cut into flat side-view parts (head, body, arm, leg - with the overlay layer) for the
 * side-scroller, plus the front view for when the player turns to face you, and the face/shirt/leg tiles the Snake
 * game's skin-snake is made of.
 */
final class SkinRig {

	/** [0] = facing right, [1] = facing left. */
	final Image[] head = new Image[2];
	final Image[] body = new Image[2];
	final Image[] arm = new Image[2];
	final Image[] leg = new Image[2];
	final Image[] armFar = new Image[2];
	final Image[] legFar = new Image[2];
	final Image front;
	/** 8x8 face, 8x8 torso, 8x8 legs (Snake). */
	final Image face;
	final Image shirt;
	final Image pants;

	private SkinRig(PixelReader r, boolean legacy, boolean slim) {
		int aw = slim ? 3 : 4;
		head[0] = part(r, 0, 8, 8, 8, 32, 8, false);
		head[1] = part(r, 16, 8, 8, 8, 48, 8, false);
		body[0] = part(r, 16, 20, 4, 12, legacy ? -1 : 16, 36, false);
		body[1] = part(r, 28, 20, 4, 12, legacy ? -1 : 28, 36, false);
		arm[0] = part(r, 40, 20, 4, 12, legacy ? -1 : 40, 36, false);
		leg[0] = part(r, 0, 20, 4, 12, legacy ? -1 : 0, 36, false);
		if (legacy) {
			arm[1] = part(r, 40, 20, 4, 12, -1, 0, true);
			leg[1] = part(r, 0, 20, 4, 12, -1, 0, true);
		} else {
			arm[1] = part(r, 36 + aw, 52, 4, 12, 52 + aw, 52, false);
			leg[1] = part(r, 24, 52, 4, 12, 8, 52, false);
		}
		for (int f = 0; f < 2; f++) {
			armFar[f] = darker(arm[1 - f]);
			legFar[f] = darker(leg[1 - f]);
		}
		WritableImage composed = new WritableImage(16, 32);
		blit(composed, part(r, 8, 8, 8, 8, 40, 8, false), 4, 0);
		blit(composed, part(r, 20, 20, 8, 12, legacy ? -1 : 20, 36, false), 4, 8);
		blit(composed, part(r, 44, 20, aw, 12, legacy ? -1 : 44, 36, false), 4 - aw, 8);
		blit(composed, legacy ? part(r, 44, 20, aw, 12, -1, 0, true) : part(r, 36, 52, aw, 12, 52, 52, false), 12, 8);
		blit(composed, part(r, 4, 20, 4, 12, legacy ? -1 : 4, 36, false), 4, 20);
		blit(composed, legacy ? part(r, 4, 20, 4, 12, -1, 0, true) : part(r, 20, 52, 4, 12, 4, 52, false), 8, 20);
		front = composed;
		face = part(r, 8, 8, 8, 8, 40, 8, false);
		shirt = part(r, 20, 20, 8, 8, legacy ? -1 : 20, 36, false);
		WritableImage legs = new WritableImage(8, 8);
		blit(legs, part(r, 4, 24, 4, 8, legacy ? -1 : 4, 40, false), 0, 0);
		blit(legs, legacy ? part(r, 4, 24, 4, 8, -1, 0, true) : part(r, 20, 56, 4, 8, 4, 56, false), 4, 0);
		pants = legs;
	}

	static SkinRig from(byte[] png, boolean slim) {
		try {
			if (png != null) {
				Image image = new Image(new ByteArrayInputStream(png));
				if (!image.isError() && image.getWidth() == 64 && (image.getHeight() == 64 || image.getHeight() == 32)) {
					return new SkinRig(image.getPixelReader(), image.getHeight() == 32, slim);
				}
			}
		} catch (Exception ignored) {
			// fall through to the stand-in
		}
		return new SkinRig(fallback().getPixelReader(), false, false);
	}

	/** Not signed in / no skin: a dark hoodie with a red stripe. */
	private static WritableImage fallback() {
		WritableImage image = new WritableImage(64, 64);
		var w = image.getPixelWriter();
		fill(w, 0, 0, 64, 64, 0x00000000);
		fill(w, 0, 0, 32, 16, 0xFF2A2224);       // head (hood)
		fill(w, 8, 8, 8, 8, 0xFFC99A7C);          // face
		fill(w, 9, 11, 2, 1, 0xFF201818);
		fill(w, 13, 11, 2, 1, 0xFF201818);
		fill(w, 10, 14, 4, 1, 0xFF8A5A4A);
		fill(w, 16, 16, 24, 16, 0xFF2A2224);      // body
		fill(w, 23, 20, 2, 12, 0xFFD7262D);
		fill(w, 40, 16, 16, 16, 0xFF2A2224);      // right arm
		fill(w, 40, 30, 16, 2, 0xFFC99A7C);
		fill(w, 32, 48, 16, 16, 0xFF2A2224);      // left arm
		fill(w, 32, 62, 16, 2, 0xFFC99A7C);
		fill(w, 0, 16, 16, 16, 0xFF3A3540);       // legs
		fill(w, 0, 30, 16, 2, 0xFF17131A);
		fill(w, 16, 48, 16, 16, 0xFF3A3540);
		fill(w, 16, 62, 16, 2, 0xFF17131A);
		return image;
	}

	private static void fill(javafx.scene.image.PixelWriter w, int x, int y, int width, int height, int argb) {
		for (int j = y; j < y + height; j++) {
			for (int i = x; i < x + width; i++) {
				w.setArgb(i, j, argb);
			}
		}
	}

	private static Image part(PixelReader r, int x, int y, int w, int h, int ox, int oy, boolean flip) {
		WritableImage out = new WritableImage(w, h);
		var writer = out.getPixelWriter();
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int sx = flip ? x + w - 1 - i : x + i;
				int base = r.getArgb(sx, y + j) | 0xFF000000;
				if (ox >= 0) {
					int over = r.getArgb(flip ? ox + w - 1 - i : ox + i, oy + j);
					int a = over >>> 24;
					if (a > 0) {
						base = blend(base, over, a / 255.0);
					}
				}
				writer.setArgb(i, j, base);
			}
		}
		return out;
	}

	private static int blend(int under, int over, double a) {
		int r = (int) (((under >> 16) & 0xFF) * (1 - a) + ((over >> 16) & 0xFF) * a);
		int g = (int) (((under >> 8) & 0xFF) * (1 - a) + ((over >> 8) & 0xFF) * a);
		int b = (int) ((under & 0xFF) * (1 - a) + (over & 0xFF) * a);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static Image darker(Image source) {
		int w = (int) source.getWidth();
		int h = (int) source.getHeight();
		WritableImage out = new WritableImage(w, h);
		PixelReader r = source.getPixelReader();
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				out.getPixelWriter().setArgb(i, j, Items.shade(r.getArgb(i, j), 0.62));
			}
		}
		return out;
	}

	private static void blit(WritableImage target, Image source, int x, int y) {
		PixelReader r = source.getPixelReader();
		for (int j = 0; j < source.getHeight(); j++) {
			for (int i = 0; i < source.getWidth(); i++) {
				target.getPixelWriter().setArgb(x + i, y + j, r.getArgb(i, j));
			}
		}
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * Draws the side view with its feet at (x, y). {@code s} = screen pixels per skin pixel; angles in degrees.
	 * {@code turn} 0..1 squashes the sprite horizontally (the quick flip when turning to face you).
	 */
	void drawSide(GraphicsContext g, double x, double y, double s, int facing, double legSwing, double armSwing,
			double frontArm, double headTilt, double turn, Image held, double heldAngle) {
		int f = facing > 0 ? 0 : 1;
		g.save();
		g.translate(x, y);
		g.scale(facing * Math.max(0.05, turn), 1);
		// in a mirrored frame (facing left) the "left" textures are already oriented - undo the mirror for them
		double mirror = facing > 0 ? 1 : -1;
		limb(g, armFar[f], 0, -22 * s, s, -armSwing, mirror);
		limb(g, legFar[f], 0, -12 * s, s, -legSwing, mirror);
		limb(g, leg[f], 0, -12 * s, s, legSwing, mirror);
		drawPart(g, body[f], -2 * s, -24 * s, 4 * s, 12 * s, mirror);
		g.save();
		g.translate(0, -24 * s);
		g.rotate(headTilt);
		drawPart(g, head[f], -4 * s, -8 * s, 8 * s, 8 * s, mirror);
		g.restore();
		g.save();
		g.translate(0, -22 * s);
		g.rotate(frontArm != 0 ? frontArm : armSwing);
		if (held != null) {
			g.save();
			g.translate(1 * s, 9 * s);
			g.rotate(heldAngle);
			g.drawImage(held, -2 * s, -13 * s, 14 * s, 14 * s);
			g.restore();
		}
		drawPart(g, arm[f], -2 * s, -2 * s, 4 * s, 12 * s, mirror);
		g.restore();
		g.restore();
	}

	private static void limb(GraphicsContext g, Image image, double px, double py, double s, double angle, double mirror) {
		g.save();
		g.translate(px, py);
		g.rotate(angle);
		drawPart(g, image, -2 * s, -2 * s, 4 * s, 12 * s, mirror);
		g.restore();
	}

	private static void drawPart(GraphicsContext g, Image image, double x, double y, double w, double h, double mirror) {
		if (mirror > 0) {
			g.drawImage(image, x, y, w, h);
		} else {
			// draw unmirrored inside the mirrored frame
			g.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), x + w, y, -w, h);
		}
	}

	void drawFront(GraphicsContext g, double x, double y, double s, double turn) {
		double w = 16 * s * Math.max(0.05, turn);
		g.drawImage(front, x - w / 2, y - 32 * s, w, 32 * s);
	}
}
