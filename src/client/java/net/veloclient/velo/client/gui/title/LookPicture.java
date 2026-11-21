package net.veloclient.velo.client.gui.title;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A Customize background picture (PNG / JPG / BMP, or an animated GIF) as textures: decoded and shrunk on a background
 * thread, blurred there when asked, uploaded on the render thread. GIFs are scaled so all frames together stay under
 * ~160 MB of texture memory (a 1080p GIF with 100 frames would otherwise take about a gigabyte).
 */
public final class LookPicture {

	private static final long FRAME_BUDGET_BYTES = 160L * 1024 * 1024;
	private static final int MAX_FRAMES = 240;

	private final String slot;
	private String loadedKey;
	private volatile List<NativeImage> pending;
	private volatile int[] pendingDelays;
	private final List<Identifier> frames = new ArrayList<>();
	private int[] delays = new int[0];
	private int totalDelay;
	private int width;
	private int height;
	private volatile boolean loading;
	private long startedAt;

	public LookPicture(String slot) {
		this.slot = slot;
	}

	/**
	 * Draws the picture covering {@code w x h} (cropped evenly, never stretched); false while there is none / it loads.
	 *
	 * @param blur 0..100
	 */
	public boolean draw(DrawContext context, int w, int h, int blur, int alpha) {
		Path file = ScreenLook.picture(slot);
		if (file == null) {
			release();
			return false;
		}
		String key;
		try {
			key = file + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + blur;
		} catch (Exception e) {
			return false;
		}
		if (!key.equals(loadedKey) && !loading) {
			loadedKey = key;
			loading = true;
			Thread t = new Thread(() -> decode(file, blur), "velo-look-picture");
			t.setDaemon(true);
			t.start();
		}
		upload();
		if (frames.isEmpty()) {
			return false;
		}
		int frame = 0;
		if (frames.size() > 1 && totalDelay > 0) {
			if (startedAt == 0) {
				startedAt = System.currentTimeMillis();
			}
			long t = (System.currentTimeMillis() - startedAt) % totalDelay;
			for (int i = 0; i < delays.length; i++) {
				t -= delays[i];
				if (t < 0) {
					frame = i;
					break;
				}
			}
		}
		float scale = Math.max(w / (float) width, h / (float) height);
		int dw = (int) Math.ceil(width * scale);
		int dh = (int) Math.ceil(height * scale);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, frames.get(frame), (w - dw) / 2, (h - dh) / 2, 0f, 0f, dw, dh, width, height,
				width, height, (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF);
		return true;
	}

	private void upload() {
		List<NativeImage> ready = pending;
		if (ready == null) {
			return;
		}
		pending = null;
		release();
		var textures = MinecraftClient.getInstance().getTextureManager();
		for (int i = 0; i < ready.size(); i++) {
			NativeImage image = ready.get(i);
			Identifier id = Identifier.of("velo-client", "look/" + slot + "_" + i);
			String name = "velo look " + slot + " " + i;
			textures.registerTexture(id, new NativeImageBackedTexture(() -> name, image));
			frames.add(id);
			width = image.getWidth();
			height = image.getHeight();
		}
		delays = pendingDelays;
		totalDelay = 0;
		for (int d : delays) {
			totalDelay += d;
		}
		startedAt = 0;
	}

	/** Frees the textures (the picture was removed or replaced). */
	public void release() {
		if (frames.isEmpty()) {
			return;
		}
		var textures = MinecraftClient.getInstance().getTextureManager();
		for (Identifier id : frames) {
			textures.destroyTexture(id);
		}
		frames.clear();
		if (ScreenLook.picture(slot) == null) {
			loadedKey = null;
		}
	}

	private void decode(Path file, int blur) {
		try {
			List<BufferedImage> raw = new ArrayList<>();
			List<Integer> rawDelays = new ArrayList<>();
			boolean gif = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
			if (gif) {
				try (InputStream in = Files.newInputStream(file)) {
					net.veloclient.velo.client.cosmetics.GifDecoder.decodeCanvases(in, (canvas, delay) -> {
						// shrunk right away (854 px wide at most) and never more than MAX_FRAMES kept: long GIFs
						// drop every other frame on the way, merging its time into the one kept
						raw.add(copy(canvas, 854));
						rawDelays.add(Math.max(20, delay));
						if (raw.size() > MAX_FRAMES) {
							halve(raw, rawDelays);
						}
					});
				}
			} else {
				BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
				if (image != null) {
					raw.add(image);
					rawDelays.add(0);
				}
			}
			if (raw.isEmpty()) {
				return;
			}
			int sw = raw.get(0).getWidth();
			int sh = raw.get(0).getHeight();
			double fit = Math.min(1.0, Math.min(1920.0 / sw, 1080.0 / sh));
			double budget = Math.sqrt(FRAME_BUDGET_BYTES / (4.0 * raw.size() * sw * sh));
			double scale = Math.min(fit, budget);
			// blurred pictures don't need the detail: smaller is faster and blurs smoother
			if (blur > 0) {
				scale = Math.min(scale, 640.0 / sw);
			}
			int w = Math.max(16, (int) Math.round(sw * scale));
			int h = Math.max(9, (int) Math.round(sh * scale));
			List<NativeImage> out = new ArrayList<>();
			for (BufferedImage frame : raw) {
				BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				var g = scaled.createGraphics();
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(frame, 0, 0, w, h, null);
				g.dispose();
				if (blur > 0) {
					blur(scaled, Math.max(1, Math.round(blur / 100f * w / 40f)));
				}
				int[] px = scaled.getRGB(0, 0, w, h, null, 0, w);
				NativeImage image = new NativeImage(w, h, false);
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						image.setColorArgb(x, y, px[y * w + x] | 0xFF000000);
					}
				}
				out.add(image);
			}
			int[] d = new int[rawDelays.size()];
			for (int i = 0; i < d.length; i++) {
				d[i] = rawDelays.get(i);
			}
			pendingDelays = d;
			pending = out;
		} catch (Throwable t) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't load the {} background {}", slot, file, t);
		} finally {
			loading = false;
		}
	}

	/** A copy of {@code src} at most {@code maxW} wide (the decoder reuses its canvas). */
	private static BufferedImage copy(BufferedImage src, int maxW) {
		double k = Math.min(1.0, maxW / (double) src.getWidth());
		int w = Math.max(1, (int) Math.round(src.getWidth() * k));
		int h = Math.max(1, (int) Math.round(src.getHeight() * k));
		BufferedImage c = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		var g = c.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return c;
	}

	/** Keeps every other frame, adding the dropped frame's time to the one before it. */
	private static void halve(List<BufferedImage> frames, List<Integer> delays) {
		List<BufferedImage> fewer = new ArrayList<>();
		List<Integer> fewerDelays = new ArrayList<>();
		for (int i = 0; i < frames.size(); i += 2) {
			fewer.add(frames.get(i));
			fewerDelays.add(delays.get(i) + (i + 1 < frames.size() ? delays.get(i + 1) : 0));
		}
		frames.clear();
		frames.addAll(fewer);
		delays.clear();
		delays.addAll(fewerDelays);
	}

	/** Three box blurs (~ a gaussian) in place. */
	static void blur(BufferedImage image, int radius) {
		int w = image.getWidth();
		int h = image.getHeight();
		int[] px = image.getRGB(0, 0, w, h, null, 0, w);
		int[] tmp = new int[px.length];
		for (int pass = 0; pass < 3; pass++) {
			boxH(px, tmp, w, h, radius);
			boxV(tmp, px, w, h, radius);
		}
		image.setRGB(0, 0, w, h, px, 0, w);
	}

	private static void boxH(int[] src, int[] dst, int w, int h, int r) {
		int span = r * 2 + 1;
		for (int y = 0; y < h; y++) {
			int row = y * w;
			int sr = 0, sg = 0, sb = 0;
			for (int k = -r; k <= r; k++) {
				int p = src[row + Math.max(0, Math.min(w - 1, k))];
				sr += (p >> 16) & 0xFF;
				sg += (p >> 8) & 0xFF;
				sb += p & 0xFF;
			}
			for (int x = 0; x < w; x++) {
				dst[row + x] = 0xFF000000 | (sr / span << 16) | (sg / span << 8) | sb / span;
				int add = src[row + Math.min(w - 1, x + r + 1)];
				int sub = src[row + Math.max(0, x - r)];
				sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
				sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
				sb += (add & 0xFF) - (sub & 0xFF);
			}
		}
	}

	private static void boxV(int[] src, int[] dst, int w, int h, int r) {
		int span = r * 2 + 1;
		for (int x = 0; x < w; x++) {
			int sr = 0, sg = 0, sb = 0;
			for (int k = -r; k <= r; k++) {
				int p = src[Math.max(0, Math.min(h - 1, k)) * w + x];
				sr += (p >> 16) & 0xFF;
				sg += (p >> 8) & 0xFF;
				sb += p & 0xFF;
			}
			for (int y = 0; y < h; y++) {
				dst[y * w + x] = 0xFF000000 | (sr / span << 16) | (sg / span << 8) | sb / span;
				int add = src[Math.min(h - 1, y + r + 1) * w + x];
				int sub = src[Math.max(0, y - r) * w + x];
				sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
				sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
				sb += (add & 0xFF) - (sub & 0xFF);
			}
		}
	}
}
