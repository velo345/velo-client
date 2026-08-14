package net.veloclient.launcher.data;

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
 * Decodes an animated GIF into fully-composited frames (each a complete,
 * standalone image at the GIF's logical screen size) plus each frame's
 * display delay - the launcher's counterpart to the mod's {@code
 * GifDecoder}, same disposal-method-aware algorithm, just producing
 * {@link BufferedImage}s instead of Minecraft's {@code NativeImage} (this
 * module has no dependency on the game at all). Used for the Store's
 * animated cape cards and the 3D preview's cycling cape texture.
 */
public final class GifFrames {

	private GifFrames() {
	}

	public record Frame(BufferedImage image, int delayMillis) {
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
			int[] pendingClearRect = null;
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

				frames.add(new Frame(deepCopy(canvas), normalizeDelay(delayCentiseconds)));

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
}
