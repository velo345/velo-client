package net.veloclient.legacy.cape;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A cloak picture (PNG, or an animated GIF) turned into what 1.8.9 draws: the 64 x 32 cape layout at up to
 * {@value #MAX_SCALE} texels per cape pixel, keeping only the part the cape model uses (22 x 17 cape pixels) per frame,
 * so a 40-frame HD cloak stays a few MB. A square picture is "cape on top, elytra below": only the top half is used.
 */
public final class CapeArt {

	static final int REGION_W = 22;
	static final int REGION_H = 17;
	private static final int MAX_SCALE = 8;

	/** {@code scale}: texels per cape pixel; each frame is REGION_W*scale x REGION_H*scale ARGB. */
	public record Decoded(int scale, List<int[]> frames, int[] delays) {
		public int width() {
			return 64 * scale;
		}

		public int height() {
			return 32 * scale;
		}

		public int regionWidth() {
			return REGION_W * scale;
		}

		public int regionHeight() {
			return REGION_H * scale;
		}
	}

	private CapeArt() {
	}

	public static Decoded decode(byte[] bytes) throws IOException {
		boolean gif = bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
		List<int[]> frames = new ArrayList<>();
		List<Integer> delays = new ArrayList<>();
		int[] scale = {0};
		if (gif) {
			canvases(bytes, (canvas, delay) -> {
				int s = scaleFor(canvas);
				scale[0] = s;
				frames.add(region(canvas, s));
				delays.add(delay);
			});
		} else {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null) {
				throw new IOException("Not a picture");
			}
			scale[0] = scaleFor(image);
			frames.add(region(image, scale[0]));
			delays.add(1000);
		}
		if (frames.isEmpty()) {
			throw new IOException("No frames");
		}
		int[] d = new int[delays.size()];
		for (int i = 0; i < d.length; i++) {
			d[i] = delays.get(i);
		}
		return new Decoded(scale[0], frames, d);
	}

	/** Texels per cape pixel this picture is kept at (a power of two up to MAX_SCALE). */
	private static int scaleFor(BufferedImage image) {
		int native_ = Math.max(1, image.getWidth() / 64);
		int s = 1;
		while (s * 2 <= Math.min(native_, MAX_SCALE)) {
			s *= 2;
		}
		return s;
	}

	/** The used cape area of a layout picture, resampled to {@code scale} texels per cape pixel. */
	private static int[] region(BufferedImage layout, int scale) {
		int layoutW = layout.getWidth();
		int layoutH = layout.getHeight() >= layoutW ? layoutW / 2 : layout.getHeight();
		double px = layoutW / 64.0;
		int srcW = (int) Math.round(REGION_W * px);
		int srcH = (int) Math.round(REGION_H * px * (layoutH * 2.0 / layoutW));
		int w = REGION_W * scale;
		int h = REGION_H * scale;
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				srcW > w ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(layout, 0, 0, w, h, 0, 0, Math.max(1, srcW), Math.max(1, srcH), null);
		g.dispose();
		return out.getRGB(0, 0, w, h, null, 0, w);
	}

	interface Sink {
		void accept(BufferedImage canvas, int delayMillis);
	}

	/** GIF89a frames composited onto one canvas (offsets and disposal), like the modern client's GifDecoder. */
	private static void canvases(byte[] bytes, Sink sink) throws IOException {
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(iis, false);
			int screenW = screenSize(reader, true);
			int screenH = screenSize(reader, false);
			BufferedImage canvas = null;
			BufferedImage saved = null;
			int[] clear = null;
			boolean restore = false;
			int count = reader.getNumImages(true);
			for (int i = 0; i < count; i++) {
				BufferedImage frame = reader.read(i);
				IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
				IIOMetadataNode desc = child(root, "ImageDescriptor");
				int left = attr(desc, "imageLeftPosition", 0);
				int top = attr(desc, "imageTopPosition", 0);
				IIOMetadataNode gce = child(root, "GraphicControlExtension");
				int delay = attr(gce, "delayTime", 10) * 10;
				String disposal = gce != null && !gce.getAttribute("disposalMethod").isEmpty() ? gce.getAttribute("disposalMethod") : "none";
				if (canvas == null) {
					canvas = new BufferedImage(screenW > 0 ? screenW : frame.getWidth(), screenH > 0 ? screenH : frame.getHeight(),
							BufferedImage.TYPE_INT_ARGB);
				}
				if (restore && saved != null) {
					canvas = copy(saved);
					restore = false;
				} else if (clear != null) {
					Graphics2D g = canvas.createGraphics();
					g.setComposite(AlphaComposite.Clear);
					g.fillRect(clear[0], clear[1], clear[2], clear[3]);
					g.dispose();
				}
				clear = null;
				if ("restoreToPrevious".equals(disposal)) {
					saved = copy(canvas);
				}
				Graphics2D g = canvas.createGraphics();
				g.drawImage(frame, left, top, null);
				g.dispose();
				sink.accept(canvas, delay < 20 ? 100 : delay);
				if ("restoreToBackgroundColor".equals(disposal)) {
					clear = new int[] {left, top, frame.getWidth(), frame.getHeight()};
				} else if ("restoreToPrevious".equals(disposal)) {
					restore = true;
				}
			}
		} finally {
			reader.dispose();
		}
	}

	private static int screenSize(ImageReader reader, boolean width) {
		try {
			IIOMetadata meta = reader.getStreamMetadata();
			IIOMetadataNode d = meta == null ? null : child((IIOMetadataNode) meta.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
			return attr(d, width ? "logicalScreenWidth" : "logicalScreenHeight", -1);
		} catch (IOException e) {
			return -1;
		}
	}

	private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
		if (parent == null) {
			return null;
		}
		var list = parent.getElementsByTagName(name);
		return list.getLength() > 0 ? (IIOMetadataNode) list.item(0) : null;
	}

	private static int attr(IIOMetadataNode node, String name, int fallback) {
		if (node == null || node.getAttribute(name).isEmpty()) {
			return fallback;
		}
		try {
			return Integer.parseInt(node.getAttribute(name));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static BufferedImage copy(BufferedImage source) {
		BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return out;
	}
}
