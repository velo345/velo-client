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
		return decode(in, Integer.MAX_VALUE);
	}

	/**
	 * Same, but every returned frame is scaled down to at most {@code maxWidth} wide (the GIF itself is
	 * composited at full size first, so disposal methods still work). A 512 x 256 cape with 40 frames
	 * is 21 MB decoded and about 5 MB at 256 px - the difference between a launcher that opens
	 * Cosmetics instantly and one that runs out of memory.
	 */
	public static Result decode(InputStream in, int maxWidth) throws IOException {
		return decode(in, canvas -> scaled(canvas, maxWidth));
	}

	/** The part of the 64 x 32 cape layout a cape uses: outside, inside and edges ({@code 22 x 17} cape pixels from the top left). */
	public static final int CAPE_REGION_WIDTH = 22;
	public static final int CAPE_REGION_HEIGHT = 17;

	/**
	 * A cape GIF's frames cropped to {@link #CAPE_REGION_WIDTH} x {@link #CAPE_REGION_HEIGHT} cape pixels (the rest of the
	 * layout is never drawn - 82% of every frame), at most {@code maxScale} texture pixels per cape pixel. At its own
	 * resolution nothing is resampled at all, so the art stays exactly as sharp as the file. See {@link #isCapeRegion}.
	 */
	public static Result decodeCape(InputStream in, int maxScale) throws IOException {
		return decode(in, canvas -> {
			int scale = Math.max(1, canvas.getWidth() / 64);
			BufferedImage region = canvas.getSubimage(0, 0, Math.min(canvas.getWidth(), CAPE_REGION_WIDTH * scale),
					Math.min(canvas.getHeight(), CAPE_REGION_HEIGHT * scale));
			return scale <= maxScale ? deepCopy(region) : scaled(region, CAPE_REGION_WIDTH * maxScale);
		});
	}

	/** Whether {@code image} is a {@link #decodeCape} crop rather than a whole 64 x 32 layout. */
	public static boolean isCapeRegion(BufferedImage image) {
		return image.getWidth() * CAPE_REGION_HEIGHT == image.getHeight() * CAPE_REGION_WIDTH;
	}

	private static Result decode(InputStream in, java.util.function.UnaryOperator<BufferedImage> output) throws IOException {
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

				frames.add(new Frame(output.apply(canvas), normalizeDelay(delayCentiseconds)));

				if ("restoreToBackgroundColor".equals(disposal)) {
					pendingClearRect = new int[] {left, top, frameImage.getWidth(), frameImage.getHeight()};
				} else if ("restoreToPrevious".equals(disposal)) {
					pendingRestorePrevious = true;
				}
			}
			BufferedImage first = frames.isEmpty() ? null : frames.get(0).image();
			return new Result(frames, first != null ? first.getWidth() : 0, first != null ? first.getHeight() : 0);
		} finally {
			reader.dispose();
		}
	}

	/** A copy of {@code source}, scaled down (smoothly) to {@code maxWidth} wide if it's wider. */
	private static BufferedImage scaled(BufferedImage source, int maxWidth) {
		if (source.getWidth() <= maxWidth) {
			return deepCopy(source);
		}
		int w = maxWidth;
		int h = Math.max(1, Math.round(source.getHeight() * (maxWidth / (float) source.getWidth())));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
		// Step down in halves so large reductions stay smooth (a single bilinear step would alias).
		BufferedImage step = source;
		int sw = source.getWidth();
		int sh = source.getHeight();
		while (sw / 2 >= w) {
			sw /= 2;
			sh = Math.max(1, sh / 2);
			BufferedImage half = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
			Graphics2D hg = half.createGraphics();
			hg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			hg.drawImage(step, 0, 0, sw, sh, null);
			hg.dispose();
			step = half;
		}
		g.drawImage(step, 0, 0, w, h, null);
		g.dispose();
		return out;
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
