package net.veloclient.velo.dev.notes;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Turns a picked image into a small PNG for a note (at most 256 px, under the server's size limit). */
final class NotePictures {

	private static final int MAX_BYTES = 250 * 1024;

	private NotePictures() {
	}

	static byte[] shrink(Path file) throws IOException {
		BufferedImage source = ImageIO.read(file.toFile());
		if (source == null) {
			throw new IOException("not a picture");
		}
		for (int side : new int[] {256, 192, 128, 96}) {
			byte[] png = encode(source, side);
			if (png.length <= MAX_BYTES) {
				return png;
			}
		}
		throw new IOException("too detailed even when small");
	}

	private static byte[] encode(BufferedImage source, int max) throws IOException {
		double scale = Math.min(1.0, max / (double) Math.max(source.getWidth(), source.getHeight()));
		int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		var g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(source, 0, 0, w, h, null);
		g.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		return bytes.toByteArray();
	}
}
