package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.texture.NativeImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes an animated GIF into a flat list of fully-composited frames (each
 * one a complete, standalone {@link NativeImage} at the GIF's logical screen
 * size) plus each frame's display delay. Used for the store's animated capes
 * (design spec's cosmetics store, "Capes" category).
 *
 * <p>{@code javax.imageio}'s GIF reader hands back each frame as decoded from
 * its own local image descriptor - which can be a smaller sub-rectangle
 * placed at an offset (a common space-saving GIF encoding trick) rather than
 * a full frame - and does none of the disposal-method compositing itself.
 * This walks the standard GIF89a algorithm by hand: draw each frame onto a
 * persistent canvas at its declared offset, snapshot the canvas as that
 * frame's result, then apply that frame's own disposal method (leave as-is /
 * clear its region back to transparent / restore the pre-frame canvas)
 * before the next frame is drawn - so frames that only encode their changed
 * pixels still decode into correct, complete images.
 */
public final class GifDecoder {

	private GifDecoder() {
	}

	public record Frame(NativeImage image, int delayMillis) {
	}

	public record Result(List<Frame> frames, int width, int height) {
	}

	public static Result decode(InputStream in) throws IOException {
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
			reader.setInput(iis, false);

			int screenWidth = readScreenSize(reader, true);
			int screenHeight = readScreenSize(reader, false);

			BufferedImage canvas = null;
			BufferedImage preRestoreSnapshot = null;
			int[] pendingClearRect = null; // {x, y, w, h} of the previous frame's region, if it disposed to background
			boolean pendingRestorePrevious = false;

			int frameCount = reader.getNumImages(true);
			List<Frame> frames = new ArrayList<>(frameCount);
			for (int i = 0; i < frameCount; i++) {
				BufferedImage frameImage = reader.read(i);
				IIOMetadata metadata = reader.getImageMetadata(i);
				IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");

				int left = intAttr(findChild(root, "ImageDescriptor"), "imageLeftPosition", 0);
				int top = intAttr(findChild(root, "ImageDescriptor"), "imageTopPosition", 0);
				IIOMetadataNode gce = findChild(root, "GraphicControlExtension");
				int delayCentiseconds = intAttr(gce, "delayTime", 10);
				String disposal = gce != null && gce.getAttribute("disposalMethod") != null && !gce.getAttribute("disposalMethod").isEmpty()
						? gce.getAttribute("disposalMethod") : "none";

				if (canvas == null) {
					int w = screenWidth > 0 ? screenWidth : frameImage.getWidth();
					int h = screenHeight > 0 ? screenHeight : frameImage.getHeight();
					canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				}

				// Apply whatever the PREVIOUS frame asked for, now that we're
				// about to draw the next one on top of it.
				if (pendingRestorePrevious && preRestoreSnapshot != null) {
					canvas = deepCopy(preRestoreSnapshot);
					pendingRestorePrevious = false;
				} else if (pendingClearRect != null) {
					Graphics2D clearG = canvas.createGraphics();
					clearG.setComposite(java.awt.AlphaComposite.Clear);
					clearG.fillRect(pendingClearRect[0], pendingClearRect[1], pendingClearRect[2], pendingClearRect[3]);
					clearG.dispose();
				}
				pendingClearRect = null;

				if ("restoreToPrevious".equals(disposal)) {
					preRestoreSnapshot = deepCopy(canvas);
				}

				Graphics2D g = canvas.createGraphics();
				g.drawImage(frameImage, left, top, null);
				g.dispose();

				frames.add(new Frame(toNativeImage(canvas), normalizeDelay(delayCentiseconds)));

				if ("restoreToBackgroundColor".equals(disposal)) {
					pendingClearRect = new int[] {left, top, frameImage.getWidth(), frameImage.getHeight()};
				} else if ("restoreToPrevious".equals(disposal)) {
					pendingRestorePrevious = true;
				}
			}
			return new Result(frames, canvas != null ? canvas.getWidth() : 0, canvas != null ? canvas.getHeight() : 0);
		} finally {
			reader.dispose();
		}
	}

	/** GIF frame delays of 0 (or unreasonably short) commonly mean "use the viewer's default" by convention, not "instant" - treated the same way browsers/most GIF viewers do. */
	private static int normalizeDelay(int delayCentiseconds) {
		int millis = delayCentiseconds * 10;
		return millis < 20 ? 100 : millis;
	}

	private static int readScreenSize(ImageReader reader, boolean width) {
		try {
			IIOMetadata streamMetadata = reader.getStreamMetadata();
			if (streamMetadata == null) {
				return -1;
			}
			IIOMetadataNode root = (IIOMetadataNode) streamMetadata.getAsTree("javax_imageio_gif_stream_1.0");
			IIOMetadataNode descriptor = findChild(root, "LogicalScreenDescriptor");
			if (descriptor == null) {
				return -1;
			}
			return intAttr(descriptor, width ? "logicalScreenWidth" : "logicalScreenHeight", -1);
		} catch (IOException e) {
			return -1;
		}
	}

	private static IIOMetadataNode findChild(IIOMetadataNode parent, String name) {
		if (parent == null) {
			return null;
		}
		var children = parent.getElementsByTagName(name);
		return children.getLength() > 0 ? (IIOMetadataNode) children.item(0) : null;
	}

	private static int intAttr(IIOMetadataNode node, String attr, int fallback) {
		if (node == null) {
			return fallback;
		}
		String value = node.getAttribute(attr);
		if (value == null || value.isEmpty()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static BufferedImage deepCopy(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = copy.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return copy;
	}

	private static NativeImage toNativeImage(BufferedImage source) {
		int w = source.getWidth();
		int h = source.getHeight();
		NativeImage image = new NativeImage(w, h, false);
		int[] argbRow = new int[w];
		for (int y = 0; y < h; y++) {
			source.getRGB(0, y, w, 1, argbRow, 0, w);
			for (int x = 0; x < w; x++) {
				image.setColorArgb(x, y, argbRow[x]);
			}
		}
		return image;
	}
}
