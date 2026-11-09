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
 * (design spec's cosmetics store, "Cloaks" category).
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
		List<Frame> frames = new ArrayList<>();
		int[] size = new int[2];
		decodeCanvases(in, (canvas, delay) -> {
			size[0] = canvas.getWidth();
			size[1] = canvas.getHeight();
			frames.add(new Frame(toNativeImage(shrink(capeRegion(canvas), canvas.getWidth())), delay));
		});
		int factor = frames.isEmpty() ? 1 : shrinkFactor(size[0]);
		return new Result(frames, size[0] / factor, size[1] / factor);
	}

	/**
	 * Just the first frame, on the GIF's full canvas - a fraction of a full decode (one frame instead of 40 of a 2048 px
	 * Store cloak), for still thumbnails.
	 */
	public static BufferedImage firstFrame(InputStream in) throws IOException {
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
			reader.setInput(iis, false);
			BufferedImage frame = reader.read(0);
			int w = readScreenSize(reader, true);
			int h = readScreenSize(reader, false);
			IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
			int left = intAttr(findChild(root, "ImageDescriptor"), "imageLeftPosition", 0);
			int top = intAttr(findChild(root, "ImageDescriptor"), "imageTopPosition", 0);
			BufferedImage canvas = new BufferedImage(w > 0 ? w : frame.getWidth(), h > 0 ? h : frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = canvas.createGraphics();
			g.drawImage(frame, left, top, null);
			g.dispose();
			return canvas;
		} finally {
			reader.dispose();
		}
	}

	/**
	 * Every frame as the whole composited picture (disposal methods applied) with its delay in ms - for the cloak
	 * upload, which looks at the full picture. The canvas passed is reused: copy what you keep.
	 */
	public static void decodeCanvases(InputStream in, java.util.function.BiConsumer<BufferedImage, Integer> sink) throws IOException {
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

				sink.accept(canvas, normalizeDelay(delayCentiseconds));

				if ("restoreToBackgroundColor".equals(disposal)) {
					pendingClearRect = new int[] {left, top, frameImage.getWidth(), frameImage.getHeight()};
				} else if ("restoreToPrevious".equals(disposal)) {
					pendingRestorePrevious = true;
				}
			}
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

	/** The part of the 64 x 32 cape layout a cape actually uses (outside, inside, edges), in cape pixels from the top left. */
	public static final int CAPE_REGION_WIDTH = 22;
	public static final int CAPE_REGION_HEIGHT = 17;

	/**
	 * A cape layout (2:1) cropped to {@link #CAPE_REGION_WIDTH} x {@link #CAPE_REGION_HEIGHT} cape pixels - the other 82%
	 * is never drawn (elytras get their own {@link ElytraArt} texture), and a 1024 x 512 cape of 40 frames is 84 MB
	 * whole but 15 MB cropped. Anything else is kept as it is. {@link Result#width()} / {@link Result#height()} stay
	 * the whole layout's size.
	 */
	private static BufferedImage capeRegion(BufferedImage canvas) {
		if (canvas.getWidth() != canvas.getHeight() * 2 || canvas.getWidth() < 64) {
			return canvas;
		}
		int scale = canvas.getWidth() / 64;
		return canvas.getSubimage(0, 0, CAPE_REGION_WIDTH * scale, CAPE_REGION_HEIGHT * scale);
	}

	/**
	 * Texels per cape pixel kept in memory. The HD store cloaks are drawn at 32 (2048 x 1024): on a player that's more
	 * than any screen shows, and 40 frames of it are ~60 MB per cloak - a Locker full of them ran low-RAM PCs out of
	 * memory. 8 (eight times vanilla) is what most PCs get; with 6 GB or more given to the game, 16.
	 */
	private static final int RAM_TEXELS = Runtime.getRuntime().maxMemory() < 6_000L * 1024 * 1024 ? 8 : 16;

	/** Cosmetics Quality picks it too: Medium at most 8, Low half of what RAM allows. */
	private static int maxTexels() {
		return net.veloclient.velo.client.modules.performance.CosmeticsQualityModule.capeTexels(RAM_TEXELS);
	}

	/** By how much a cape layout of this width is shrunk (a power of two, 1 = kept as it is). */
	private static int shrinkFactor(int layoutWidth) {
		int factor = 1;
		int max = maxTexels();
		while (layoutWidth / factor / 64 > max && layoutWidth / factor >= 128) {
			factor *= 2;
		}
		return factor;
	}

	/** Halves {@code image} until the layout it came from is at most {@link #maxTexels()} per cape pixel (2:1 box filter per halving). */
	private static BufferedImage shrink(BufferedImage image, int layoutWidth) {
		int factor = shrinkFactor(layoutWidth);
		BufferedImage out = image;
		while (factor > 1) {
			int w = Math.max(1, out.getWidth() / 2), h = Math.max(1, out.getHeight() / 2);
			BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = half.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(out, 0, 0, w, h, null);
			g.dispose();
			out = half;
			factor /= 2;
		}
		return out;
	}

	/**
	 * Another cape-sized picture (the Dragon Eye's gaze atlas) shrunk the same way: {@code texelsPerCapePixel} is
	 * what it's drawn at now.
	 */
	static NativeImage shrunkForCape(BufferedImage image, int texelsPerCapePixel) {
		BufferedImage out = image;
		for (int t = texelsPerCapePixel; t > maxTexels() && out.getWidth() >= 128; t /= 2) {
			int w = out.getWidth() / 2, h = out.getHeight() / 2;
			BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = half.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(out, 0, 0, w, h, null);
			g.dispose();
			out = half;
		}
		return toNativeImage(out);
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
