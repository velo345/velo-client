package net.veloclient.velo.client.cosmetics.capeart;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom cloak uploads, shared by the game and the launcher (plain Java, compiled into the launcher by
 * {@code bundleCapeArt}): {@link #detect} tells a real cloak texture from an ordinary picture, and {@link #convert}
 * turns any picture into a cloak texture - the art on the outside, matching edges and inside, and the elytra wings
 * painted too - so people can just drop in a logo, a photo or a GIF.
 *
 * <p>Layout: vanilla's 64 x 32 cape template, at {@code scale} texture pixels per template pixel. The outside of the
 * cloak (what others see on your back) is (1, 1) 10 x 16, the inside (12, 1), its edges around those, and the elytra
 * wing box at (22, 0) - (46, 22). Everything else is never drawn. Velo+ animated cloaks are GIFs of the same layout.
 */
public final class CapeArt {

	public static final int LAYOUT_WIDTH = 64;
	public static final int LAYOUT_HEIGHT = 32;
	/** Largest still cloak texture the game and the server take ("2K", 32 texture pixels per template pixel). */
	public static final int MAX_WIDTH = 2048;
	/** Largest animated cloak (Velo+): 1024 px, the most detail games show animated cloaks at (Store ones included). */
	public static final int MAX_ANIMATED_WIDTH = 1024;
	/** The server's limits for animated cloaks: frames, and decoded pixels over all frames of the 22 x 17 corner kept. */
	public static final int MAX_FRAMES = 128;
	public static final long MAX_ANIMATED_REGION_PIXELS = 24L * 1024 * 1024;
	/** The server's upload limit, with a little room. */
	public static final int MAX_UPLOAD_BYTES = 15 * 1024 * 1024 - 64 * 1024;

	/** How many frames of a {@code width}-wide animated cloak fit the server's budget. */
	public static int maxFrames(int width) {
		long region = (long) (width * 22 / LAYOUT_WIDTH) * (width / 2 * 17 / LAYOUT_HEIGHT);
		return (int) Math.max(1, Math.min(MAX_FRAMES, MAX_ANIMATED_REGION_PIXELS / Math.max(1, region)));
	}

	private CapeArt() {
	}

	/** One RGBA frame. */
	public record Picture(int[] argb, int width, int height) {
		public int at(int x, int y) {
			return argb[y * width + x];
		}

		public static Picture of(BufferedImage image) {
			int w = image.getWidth();
			int h = image.getHeight();
			return new Picture(image.getRGB(0, 0, w, h, null, 0, w), w, h);
		}

		public BufferedImage toImage() {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, width, height, argb, 0, width);
			return image;
		}
	}

	// ---- Detection ----

	/** What a file turned out to be. */
	public enum Kind {
		/** A cloak texture in the 64 x 32 layout (any 2:1 size). */
		TEMPLATE,
		/** A cloak texture on top and an elytra texture below (1:1). */
		COMBINED,
		/** OptiFine's 46 x 22 cloak layout (cloak + elytra, no padding). */
		OPTIFINE,
		/** Just the used 22 x 17 corner of the layout (how Velo stores animated cloaks). */
		REGION,
		/** An ordinary picture: needs converting. */
		IMAGE
	}

	/**
	 * {@code title}/{@code detail}: what to tell the player. {@code pixelArt}: few colours or tiny - scale it up with
	 * hard edges rather than smoothing it.
	 */
	public record Detection(Kind kind, String title, String detail, boolean pixelArt) {
		public boolean isCape() {
			return kind != Kind.IMAGE;
		}
	}

	/** Template pixels of the 64 x 32 layout nothing ever draws (to the right of the elytra, under the cloak and wings). */
	private static final int[][] UNUSED = {{46, 0, 18, 32}, {0, 17, 22, 15}, {22, 22, 24, 10}};
	/** The outside of the cloak. */
	private static final int[] OUTSIDE = {1, 1, 10, 16};

	/**
	 * Looks at the picture's shape and content. A cloak texture has the layout's proportions, art on the outside panel
	 * and nothing (transparent, or one flat fill) in the parts of the layout that are never drawn - an ordinary 2:1
	 * picture has something everywhere, so it isn't mistaken for one. A square picture only counts as cloak + elytra
	 * when its top half passes as a cloak; before, any square logo was cut in half into a cloak and an elytra.
	 */
	public static Detection detect(Picture p) {
		boolean pixelArt = isPixelArt(p);
		int w = p.width();
		int h = p.height();
		if (w < 22 || h < 17) {
			return new Detection(Kind.IMAGE, "Tiny picture", w + " x " + h + " is too small to be a cloak texture - it will be scaled up.", true);
		}
		if (w == h * 2 && looksLikeTemplate(p, 0, w / (double) LAYOUT_WIDTH)) {
			return new Detection(Kind.TEMPLATE, "Cloak texture", sizeName(w) + " cloak in the standard layout - ready to use, elytra included.", pixelArt);
		}
		if (w == h && looksLikeTemplate(p, 0, w / (double) LAYOUT_WIDTH)) {
			return new Detection(Kind.COMBINED, "Cloak + elytra texture", sizeName(w) + " cloak with its own elytra texture below - ready to use.", pixelArt);
		}
		if (Math.abs(w * 22 - h * 46) <= Math.max(2, w / 23) && outsideCovered(p, w / 46.0)) {
			return new Detection(Kind.OPTIFINE, "OptiFine cloak", "An OptiFine-style cloak (" + w + " x " + h + ") - it will be fitted into the standard layout.", pixelArt);
		}
		if (Math.abs(w * 17 - h * 22) <= Math.max(2, w / 22) && outsideCovered(p, w / 22.0)) {
			return new Detection(Kind.REGION, "Cloak texture", "A cropped cloak texture (" + w + " x " + h + ") - it will be fitted into the standard layout.", pixelArt);
		}
		String why = w == h * 2 ? "It has a cloak's 2:1 shape, but its layout isn't a cloak's" : "Not a cloak texture";
		return new Detection(Kind.IMAGE, "Picture", why + " - pick how it should sit on your cloak.", pixelArt);
	}

	private static String sizeName(int width) {
		int scale = Math.max(1, Math.round(width / (float) LAYOUT_WIDTH));
		if (scale > MAX_WIDTH / LAYOUT_WIDTH) {
			return "HD (" + width + " x " + width / 2 + ", used at " + MAX_WIDTH + " x " + MAX_WIDTH / 2 + ")";
		}
		return scale <= 1 ? "Classic (64 x 32)" : "HD " + scale + "x (" + width + " x " + width / 2 + ")";
	}

	private static boolean outsideCovered(Picture p, double unit) {
		return opaqueFraction(p, unit, OUTSIDE) > 0.6;
	}

	private static boolean looksLikeTemplate(Picture p, int originY, double unit) {
		if (opaqueFraction(p, unit, OUTSIDE) < 0.6) {
			return false;
		}
		List<Integer> unused = new ArrayList<>();
		for (int[] r : UNUSED) {
			sample(p, unit, r, unused);
		}
		int transparent = 0;
		Map<Integer, Integer> counts = new HashMap<>();
		for (int c : unused) {
			if ((c >>> 24) < 16) {
				transparent++;
			} else {
				counts.merge(quantize(c), 1, Integer::sum);
			}
		}
		if (unused.isEmpty()) {
			return false;
		}
		if (transparent >= unused.size() * 0.92) {
			return true;
		}
		// Filled with one flat colour (some editors do that): that colour must not also be all over the art.
		int dominant = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
		int flat = transparent;
		for (int c : unused) {
			if ((c >>> 24) >= 16 && near(quantize(c), dominant)) {
				flat++;
			}
		}
		if (flat < unused.size() * 0.92) {
			return false;
		}
		List<Integer> outside = new ArrayList<>();
		sample(p, unit, OUTSIDE, outside);
		int same = 0;
		for (int c : outside) {
			if ((c >>> 24) >= 16 && near(quantize(c), dominant)) {
				same++;
			}
		}
		return same < outside.size() * 0.7;
	}

	/** Up to ~32 x 32 evenly spread pixels of a layout rectangle {x, y, w, h} (template pixels, {@code unit} texture px each). */
	private static void sample(Picture p, double unit, int[] r, List<Integer> out) {
		int x0 = (int) Math.floor(r[0] * unit);
		int y0 = (int) Math.floor(r[1] * unit);
		int x1 = Math.min(p.width(), (int) Math.ceil((r[0] + r[2]) * unit));
		int y1 = Math.min(p.height(), (int) Math.ceil((r[1] + r[3]) * unit));
		int stepX = Math.max(1, (x1 - x0) / 32);
		int stepY = Math.max(1, (y1 - y0) / 32);
		for (int y = y0; y < y1; y += stepY) {
			for (int x = x0; x < x1; x += stepX) {
				out.add(p.at(x, y));
			}
		}
	}

	private static double opaqueFraction(Picture p, double unit, int[] r) {
		List<Integer> px = new ArrayList<>();
		sample(p, unit, r, px);
		if (px.isEmpty()) {
			return 0;
		}
		long opaque = px.stream().filter(c -> (c >>> 24) >= 128).count();
		return opaque / (double) px.size();
	}

	private static int quantize(int c) {
		return c & 0xF8F8F8;
	}

	private static boolean near(int a, int b) {
		int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
		int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
		int db = (a & 0xFF) - (b & 0xFF);
		return dr * dr + dg * dg + db * db <= 28 * 28;
	}

	/** Small, or only a handful of colours (sampled): scaled with hard edges so pixel art stays crisp. */
	public static boolean isPixelArt(Picture p) {
		if (p.width() <= 128 && p.height() <= 128) {
			return true;
		}
		java.util.Set<Integer> colors = new java.util.HashSet<>();
		int step = Math.max(1, (int) Math.sqrt((double) p.width() * p.height() / 4096));
		for (int y = 0; y < p.height(); y += step) {
			for (int x = 0; x < p.width(); x += step) {
				int c = p.at(x, y);
				if ((c >>> 24) >= 16) {
					colors.add(c & 0xFFFFFF);
					if (colors.size() > 48) {
						return false;
					}
				}
			}
		}
		return true;
	}

	// ---- Using a detected cloak texture as it is ----

	/**
	 * A detected cloak texture brought into the standard layout at a width the importers take (a multiple of 64, at
	 * most {@link #MAX_WIDTH}): OptiFine and cropped layouts are placed into the full 64 x 32, odd sizes resampled. A
	 * {@link Kind#COMBINED} texture keeps its elytra half (still square).
	 */
	public static Picture normalize(Picture p, Detection d) {
		return normalize(p, d, MAX_WIDTH);
	}

	/** {@link #normalize(Picture, Detection)} at most {@code maxWidth} wide (animated cloaks: {@link #MAX_ANIMATED_WIDTH}). */
	public static Picture normalize(Picture p, Detection d, int maxWidth) {
		int w = p.width();
		int h = p.height();
		double unit = switch (d.kind()) {
			case OPTIFINE -> w / 46.0;
			case REGION -> w / 22.0;
			default -> w / (double) LAYOUT_WIDTH;
		};
		int scale = Math.max(1, Math.min(maxWidth / LAYOUT_WIDTH, (int) Math.round(unit)));
		int outW = LAYOUT_WIDTH * scale;
		int outH = d.kind() == Kind.COMBINED ? outW : outW / 2;
		if (d.kind() != Kind.OPTIFINE && d.kind() != Kind.REGION && outW == w && outH == h) {
			return p;
		}
		int[] out = new int[outW * outH];
		// Template pixels the source covers (the whole layout, OptiFine's 46 x 22, or the 22 x 17 corner).
		double coverW = d.kind() == Kind.OPTIFINE ? 46 : d.kind() == Kind.REGION ? 22 : LAYOUT_WIDTH;
		double coverH = d.kind() == Kind.OPTIFINE ? 22 : d.kind() == Kind.REGION ? 17 : d.kind() == Kind.COMBINED ? LAYOUT_WIDTH : LAYOUT_HEIGHT;
		int destW = (int) Math.round(coverW * scale);
		int destH = (int) Math.round(coverH * scale);
		for (int y = 0; y < Math.min(destH, outH); y++) {
			int sy = Math.min(h - 1, (int) (y * (h / (double) destH)));
			for (int x = 0; x < Math.min(destW, outW); x++) {
				int sx = Math.min(w - 1, (int) (x * (w / (double) destW)));
				out[y * outW + x] = p.at(sx, sy);
			}
		}
		return new Picture(out, outW, outH);
	}

	// ---- Converting a picture ----

	/** How the picture fills the cloak: cropped to fill it, fitted whole (background around it), or stretched. */
	public enum Fit {
		FILL, FIT, STRETCH
	}

	/**
	 * {@code zoom} 1-4, {@code panX}/{@code panY} -1..1 (which part stays when cropping or zoomed), {@code background}
	 * ARGB or 0 for automatic, {@code scale} texture pixels per template pixel (1, 2, 4 or 8), {@code pixelArt} hard
	 * edges, {@code elytra} paint the wings too, {@code artInside} show the art (darkened) on the inside as well.
	 */
	public record Options(Fit fit, double zoom, double panX, double panY, int background, int scale, boolean pixelArt, boolean elytra,
			boolean artInside) {
		public static Options defaults(Detection detection, Picture picture) {
			// Pixel art at its own size looks best a few steps up; anything else at 1024 x 512, like the Store's HD cloaks.
			int scale = detection.pixelArt() && picture.width() <= 64 ? 4 : 16;
			// A logo (plain or see-through border) is shown whole; a photo fills the cloak.
			Fit fit = borderColor(picture) != null ? Fit.FIT : Fit.FILL;
			return new Options(fit, 1, 0, 0, 0, scale, detection.pixelArt(), true, false);
		}

		public Options withScale(int s) {
			return new Options(fit, zoom, panX, panY, background, s, pixelArt, elytra, artInside);
		}
	}

	/**
	 * A colour for the cloak around the art: the picture's own border colour when its edge is one flat colour (a logo on
	 * a background), otherwise a dark shade of its average colour.
	 */
	public static int autoBackground(Picture p) {
		Integer border = borderColor(p);
		if (border != null && border != TRANSPARENT_BORDER) {
			return 0xFF000000 | border;
		}
		int stepX = Math.max(1, p.width() / 64);
		int stepY = Math.max(1, p.height() / 64);
		long r = 0;
		long g = 0;
		long b = 0;
		long n = 0;
		for (int y = 0; y < p.height(); y += stepY) {
			for (int x = 0; x < p.width(); x += stepX) {
				int c = p.at(x, y);
				if ((c >>> 24) >= 128) {
					r += (c >> 16) & 0xFF;
					g += (c >> 8) & 0xFF;
					b += c & 0xFF;
					n++;
				}
			}
		}
		if (n == 0) {
			return 0xFF1B1B22;
		}
		return shade(0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n), 0.35);
	}

	/** {@link #borderColor} of a picture whose border is see-through. */
	private static final int TRANSPARENT_BORDER = -1;

	/**
	 * The picture's border colour when at least 60% of its edge is one flat colour ({@link #TRANSPARENT_BORDER} when
	 * it's see-through) - a logo on a background - or null for a photo.
	 */
	static Integer borderColor(Picture p) {
		Map<Integer, Integer> counts = new HashMap<>();
		int total = 0;
		int stepX = Math.max(1, p.width() / 64);
		int stepY = Math.max(1, p.height() / 64);
		for (int x = 0; x < p.width(); x += stepX) {
			total += countEdge(p, x, 0, counts) + countEdge(p, x, p.height() - 1, counts);
		}
		for (int y = 0; y < p.height(); y += stepY) {
			total += countEdge(p, 0, y, counts) + countEdge(p, p.width() - 1, y, counts);
		}
		var best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
		return best != null && best.getValue() >= total * 0.6 ? best.getKey() : null;
	}

	private static int countEdge(Picture p, int x, int y, Map<Integer, Integer> counts) {
		int c = p.at(x, y);
		if ((c >>> 24) < 128) {
			counts.merge(TRANSPARENT_BORDER, 1, Integer::sum);
			return 1;
		}
		counts.merge(quantize(c), 1, Integer::sum);
		return 1;
	}

	/** {@code argb} darkened ({@code factor} < 1) or lightened, alpha kept. */
	public static int shade(int argb, double factor) {
		int r = (int) Math.min(255, ((argb >> 16) & 0xFF) * factor);
		int g = (int) Math.min(255, ((argb >> 8) & 0xFF) * factor);
		int b = (int) Math.min(255, (argb & 0xFF) * factor);
		return (argb & 0xFF000000) | r << 16 | g << 8 | b;
	}

	/** The picture as a cloak texture ({@code 64 * scale} x {@code 32 * scale}). */
	public static Picture convert(Picture src, Options o) {
		int s = Math.max(1, Math.min(MAX_WIDTH / LAYOUT_WIDTH, o.scale()));
		int w = LAYOUT_WIDTH * s;
		int h = LAYOUT_HEIGHT * s;
		int[] out = new int[w * h];
		int bg = o.background() != 0 ? o.background() | 0xFF000000 : autoBackground(src);
		// The outside: the art itself.
		int[] art = render(src, 10 * s, 16 * s, o, bg);
		blit(out, w, art, 10 * s, 16 * s, s, s, false, 1);
		// Its edges (seen from the side and from above/below): the art's own border, a little darker.
		edges(out, w, art, 10 * s, 16 * s, 0, s, 11 * s, s, s, 0.8);
		// The inside, facing your back: a dark lining, or the art mirrored and darkened.
		if (o.artInside()) {
			blit(out, w, art, 10 * s, 16 * s, 12 * s, s, true, 0.45);
		} else {
			fill(out, w, 12 * s, s, 10 * s, 16 * s, shade(bg, 0.6));
		}
		if (o.elytra()) {
			// The wings (10 x 20 each face), so the art shows when you fly: outer face, inner face mirrored and darker.
			int[] wing = render(src, 10 * s, 20 * s, o, bg);
			blit(out, w, wing, 10 * s, 20 * s, 36 * s, 2 * s, false, 1);
			blit(out, w, wing, 10 * s, 20 * s, 24 * s, 2 * s, true, 0.7);
			edges(out, w, wing, 10 * s, 20 * s, 22 * s, 2 * s, 34 * s, 2 * s, 2 * s, 0.75);
		}
		return new Picture(out, w, h);
	}

	/** Strips beside / above / below a face, from its border pixels: left at (lx, ly) and right at (rx, ly), {@code thick} wide. */
	private static void edges(int[] out, int w, int[] face, int fw, int fh, int lx, int ly, int rx, int topY, int thick, double dark) {
		for (int y = 0; y < fh; y++) {
			int left = shade(face[y * fw], dark);
			int right = shade(face[y * fw + fw - 1], dark);
			for (int t = 0; t < thick; t++) {
				set(out, w, lx + t, ly + y, left);
				set(out, w, rx + t, ly + y, right);
			}
		}
		// Top edge over the face, bottom edge next to it (the template's order: top at x+thick, bottom at x+thick+fw).
		for (int x = 0; x < fw; x++) {
			int top = shade(face[x], dark);
			int bottom = shade(face[(fh - 1) * fw + x], dark);
			for (int t = 0; t < thick; t++) {
				set(out, w, lx + thick + x, topY - thick + t, top);
				set(out, w, lx + thick + fw + x, topY - thick + t, bottom);
			}
		}
	}

	private static void set(int[] out, int w, int x, int y, int c) {
		if (x >= 0 && y >= 0 && x < w && y * w + x < out.length) {
			out[y * w + x] = c;
		}
	}

	private static void fill(int[] out, int w, int x0, int y0, int fw, int fh, int c) {
		for (int y = 0; y < fh; y++) {
			for (int x = 0; x < fw; x++) {
				set(out, w, x0 + x, y0 + y, c);
			}
		}
	}

	private static void blit(int[] out, int w, int[] face, int fw, int fh, int x0, int y0, boolean mirror, double dark) {
		for (int y = 0; y < fh; y++) {
			for (int x = 0; x < fw; x++) {
				int c = face[y * fw + (mirror ? fw - 1 - x : x)];
				set(out, w, x0 + x, y0 + y, dark == 1 ? c : shade(c, dark));
			}
		}
	}

	/** The picture placed into a {@code tw} x {@code th} face per the options, composited on {@code bg} (opaque). */
	static int[] render(Picture src, int tw, int th, Options o, int bg) {
		int sw = src.width();
		int sh = src.height();
		double zoom = Math.max(1, o.zoom());
		// Source rectangle shown (may reach outside the picture when fitting: that's background).
		double rx;
		double ry;
		double rw;
		double rh;
		double aspect = tw / (double) th;
		switch (o.fit()) {
			case STRETCH -> {
				rw = sw / zoom;
				rh = sh / zoom;
			}
			case FIT -> {
				// The whole picture inside the face: the face's rectangle in source pixels is larger than the picture.
				// Empty margins (see-through or the flat border colour) are left out, so a logo isn't tiny.
				int[] b = contentBounds(src);
				double pad = 1.08;
				double k = Math.max(b[2] * pad / tw, b[3] * pad / th) / zoom;
				rw = tw * k;
				rh = th * k;
				// Panning slides the picture around the free space (slider right/down = picture right/down).
				double cx = b[0] + b[2] / 2.0 - o.panX() * Math.abs(rw - b[2]) / 2;
				double cy = b[1] + b[3] / 2.0 - o.panY() * Math.abs(rh - b[3]) / 2;
				return sampleFace(src, tw, th, o, bg, cx - rw / 2, cy - rh / 2, rw, rh);
			}
			default -> {
				double cw = Math.min(sw, sh * aspect);
				rw = cw / zoom;
				rh = rw / aspect;
			}
		}
		rx = (sw - rw) / 2 + o.panX() * Math.abs(sw - rw) / 2;
		ry = (sh - rh) / 2 + o.panY() * Math.abs(sh - rh) / 2;
		return sampleFace(src, tw, th, o, bg, rx, ry, rw, rh);
	}

	/** {x, y, w, h} of the picture's content: everything that isn't see-through or its flat border colour. */
	static int[] contentBounds(Picture p) {
		Integer border = borderColor(p);
		int minX = p.width();
		int minY = p.height();
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < p.height(); y++) {
			for (int x = 0; x < p.width(); x++) {
				int c = p.at(x, y);
				boolean empty = (c >>> 24) < 24 || border != null && border != TRANSPARENT_BORDER && near(quantize(c), border);
				if (!empty) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < minX || maxY < minY) {
			return new int[] {0, 0, p.width(), p.height()};
		}
		return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
	}

	/** Samples source rectangle (rx, ry, rw, rh) into a {@code tw} x {@code th} face over {@code bg}. */
	private static int[] sampleFace(Picture src, int tw, int th, Options o, int bg, double rx, double ry, double rw, double rh) {
		double stepX = rw / tw;
		double stepY = rh / th;
		// Shrinking: average a small grid of samples per pixel; growing: bilinear, or nearest for pixel art.
		int sub = o.pixelArt() ? 1 : (int) Math.max(1, Math.min(4, Math.ceil(Math.max(stepX, stepY))));
		int[] face = new int[tw * th];
		for (int y = 0; y < th; y++) {
			for (int x = 0; x < tw; x++) {
				long a = 0;
				long r = 0;
				long g = 0;
				long b = 0;
				for (int j = 0; j < sub; j++) {
					for (int i = 0; i < sub; i++) {
						double fx = rx + (x + (i + 0.5) / sub) * stepX;
						double fy = ry + (y + (j + 0.5) / sub) * stepY;
						int c = sample(src, fx, fy, o.pixelArt() || sub > 1);
						int ca = c >>> 24;
						a += ca;
						r += ((c >> 16) & 0xFF) * ca;
						g += ((c >> 8) & 0xFF) * ca;
						b += (c & 0xFF) * ca;
					}
				}
				int n = sub * sub;
				double alpha = a / (255.0 * n);
				int cr = a == 0 ? 0 : (int) (r / a);
				int cg = a == 0 ? 0 : (int) (g / a);
				int cb = a == 0 ? 0 : (int) (b / a);
				// Over the background (a cloak is drawn solid: transparent pixels would turn black).
				int or = (int) Math.round(cr * alpha + ((bg >> 16) & 0xFF) * (1 - alpha));
				int og = (int) Math.round(cg * alpha + ((bg >> 8) & 0xFF) * (1 - alpha));
				int ob = (int) Math.round(cb * alpha + (bg & 0xFF) * (1 - alpha));
				face[y * tw + x] = 0xFF000000 | or << 16 | og << 8 | ob;
			}
		}
		return face;
	}

	/** The colour at source position (fx, fy) - transparent outside the picture. */
	private static int sample(Picture p, double fx, double fy, boolean nearest) {
		if (fx < 0 || fy < 0 || fx >= p.width() || fy >= p.height()) {
			return 0;
		}
		if (nearest) {
			return p.at((int) fx, (int) fy);
		}
		double x = fx - 0.5;
		double y = fy - 0.5;
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		double tx = x - x0;
		double ty = y - y0;
		int c00 = p.at(clamp(x0, p.width()), clamp(y0, p.height()));
		int c10 = p.at(clamp(x0 + 1, p.width()), clamp(y0, p.height()));
		int c01 = p.at(clamp(x0, p.width()), clamp(y0 + 1, p.height()));
		int c11 = p.at(clamp(x0 + 1, p.width()), clamp(y0 + 1, p.height()));
		int result = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			double top = ((c00 >>> shift) & 0xFF) * (1 - tx) + ((c10 >>> shift) & 0xFF) * tx;
			double bottom = ((c01 >>> shift) & 0xFF) * (1 - tx) + ((c11 >>> shift) & 0xFF) * tx;
			result |= ((int) Math.round(top * (1 - ty) + bottom * ty) & 0xFF) << shift;
		}
		return result;
	}

	private static int clamp(int v, int size) {
		return Math.max(0, Math.min(size - 1, v));
	}

	// ---- Animated (GIF) ----

	/**
	 * Every frame converted, kept inside the server's limits for animated cloaks: at most {@link #MAX_FRAMES} frames and
	 * {@link #MAX_ANIMATED_PIXELS} pixels in all (frames are dropped evenly, their time added to the ones kept).
	 * Returns the frames and their delays (ms) in {@code delaysOut}.
	 */
	public static List<Picture> convertFrames(List<Picture> frames, List<Integer> delays, Options o, List<Integer> delaysOut) {
		int s = Math.max(1, Math.min(MAX_ANIMATED_WIDTH / LAYOUT_WIDTH, o.scale()));
		Options options = o.withScale(s);
		int[] picked = pickFrames(frames.size(), delays, maxFrames(LAYOUT_WIDTH * s), delaysOut);
		// Converted when the encoder asks for each frame: 2K frames are 8 MB each, never all held at once.
		return new java.util.AbstractList<>() {
			@Override
			public Picture get(int index) {
				return convert(frames.get(picked[index]), options);
			}

			@Override
			public int size() {
				return picked.length;
			}
		};
	}

	/**
	 * Which of {@code count} frames to keep for at most {@code max}: evenly spread, each kept frame showing for the time
	 * of the ones dropped after it (delays into {@code delaysOut}).
	 */
	public static int[] pickFrames(int count, List<Integer> delays, int max, List<Integer> delaysOut) {
		int keep = Math.max(1, Math.min(count, max));
		int[] picked = new int[keep];
		delaysOut.clear();
		for (int k = 0; k < keep; k++) {
			int from = (int) ((long) k * count / keep);
			int to = (int) ((long) (k + 1) * count / keep);
			int delay = 0;
			for (int i = from; i < to; i++) {
				delay += delays.get(i);
			}
			picked[k] = from;
			delaysOut.add(Math.max(20, delay));
		}
		return picked;
	}

	/**
	 * The animated cloak (Velo+) to upload for a GIF: a GIF that already is a valid cloak and fits the server's limits is
	 * kept byte for byte; otherwise every frame is normalized ({@code asIs}) or converted, frames are thinned to the
	 * budget, and the size steps down until the file fits the upload limit.
	 */
	public static byte[] animatedGif(byte[] original, List<Picture> frames, List<Integer> delays, Detection d, Options o, boolean asIs)
			throws IOException {
		Picture first = frames.get(0);
		int w = first.width();
		if (asIs && (d.kind() == Kind.TEMPLATE || d.kind() == Kind.COMBINED) && original != null && original.length <= MAX_UPLOAD_BYTES) {
			// The frames here may have been decoded smaller: judge the file by its own header.
			int[] header = gifHeader(original);
			int ow = header[0];
			int oh = header[1];
			if (ow % LAYOUT_WIDTH == 0 && ow <= MAX_ANIMATED_WIDTH && (oh * 2 == ow || oh == ow) && header[2] <= maxFrames(ow)) {
				return original;
			}
		}
		int scale = asIs ? Math.max(1, Math.min(MAX_ANIMATED_WIDTH / LAYOUT_WIDTH, Math.round(w / (float) (d.kind() == Kind.OPTIFINE ? 46
				: d.kind() == Kind.REGION ? 22 : LAYOUT_WIDTH)))) : Math.min(MAX_ANIMATED_WIDTH / LAYOUT_WIDTH, o.scale());
		for (; ; scale /= 2) {
			List<Integer> outDelays = new ArrayList<>();
			List<Picture> out;
			if (asIs) {
				int maxWidth = LAYOUT_WIDTH * scale;
				int[] picked = pickFrames(frames.size(), delays, maxFrames(maxWidth), outDelays);
				out = new java.util.AbstractList<>() {
					@Override
					public Picture get(int index) {
						return normalize(frames.get(picked[index]), d, maxWidth);
					}

					@Override
					public int size() {
						return picked.length;
					}
				};
			} else {
				out = convertFrames(frames, delays, o.withScale(scale), outDelays);
			}
			byte[] gif = encodeGif(out, outDelays);
			if (gif.length <= MAX_UPLOAD_BYTES) {
				return gif;
			}
			if (scale <= 1) {
				throw new IOException("This animation is too big for an animated cloak - try a shorter GIF");
			}
		}
	}

	/** {width, height, frames} of a GIF file ({0, 0, 0} when unreadable). */
	private static int[] gifHeader(byte[] gif) {
		try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(gif))) {
			javax.imageio.ImageReader reader = javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
			try {
				reader.setInput(in, false, false);
				return new int[] {reader.getWidth(0), reader.getHeight(0), reader.getNumImages(true)};
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			return new int[] {0, 0, 0};
		}
	}

	/** {@code p} halved (2 x 2 box filter) until at most {@code maxWidth} wide - for big GIF frames held in memory. */
	public static Picture shrink(Picture p, int maxWidth) {
		while (p.width() > maxWidth && p.width() >= 2 && p.height() >= 2) {
			int w = p.width() / 2;
			int h = p.height() / 2;
			int[] out = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int a = 0;
					int r = 0;
					int g = 0;
					int b = 0;
					for (int j = 0; j < 2; j++) {
						for (int i = 0; i < 2; i++) {
							int c = p.at(x * 2 + i, y * 2 + j);
							int ca = c >>> 24;
							a += ca;
							r += ((c >> 16) & 0xFF) * ca;
							g += ((c >> 8) & 0xFF) * ca;
							b += (c & 0xFF) * ca;
						}
					}
					out[y * w + x] = a == 0 ? 0 : (a / 4) << 24 | (r / a) << 16 | (g / a) << 8 | (b / a);
				}
			}
			p = new Picture(out, w, h);
		}
		return p;
	}

	/** A looping GIF of {@code frames} (delays in ms). */
	public static byte[] encodeGif(List<Picture> frames, List<Integer> delays) throws IOException {
		javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (javax.imageio.stream.ImageOutputStream out = javax.imageio.ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < frames.size(); i++) {
				BufferedImage image = frames.get(i).toImage();
				javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
				javax.imageio.metadata.IIOMetadata meta = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(image), param);
				String format = meta.getNativeMetadataFormatName();
				javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(format);
				javax.imageio.metadata.IIOMetadataNode control = child(root, "GraphicControlExtension");
				control.setAttribute("disposalMethod", "none");
				control.setAttribute("userInputFlag", "FALSE");
				control.setAttribute("transparentColorFlag", "FALSE");
				control.setAttribute("delayTime", Integer.toString(Math.max(2, Math.round(delays.get(i) / 10f))));
				control.setAttribute("transparentColorIndex", "0");
				if (i == 0) {
					javax.imageio.metadata.IIOMetadataNode apps = child(root, "ApplicationExtensions");
					javax.imageio.metadata.IIOMetadataNode app = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");
					app.setAttribute("applicationID", "NETSCAPE");
					app.setAttribute("authenticationCode", "2.0");
					app.setUserObject(new byte[] {1, 0, 0}); // loop forever
					apps.appendChild(app);
				}
				meta.setFromTree(format, root);
				writer.writeToSequence(new javax.imageio.IIOImage(opaque(image), null, meta), param);
			}
			writer.endWriteSequence();
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/** GIF frames as opaque RGB (the never-drawn parts of the layout become black instead of eating palette entries). */
	private static BufferedImage opaque(BufferedImage image) {
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		int[] px = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
		rgb.setRGB(0, 0, image.getWidth(), image.getHeight(), px, 0, image.getWidth());
		return rgb;
	}

	private static javax.imageio.metadata.IIOMetadataNode child(javax.imageio.metadata.IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
				return (javax.imageio.metadata.IIOMetadataNode) root.item(i);
			}
		}
		javax.imageio.metadata.IIOMetadataNode node = new javax.imageio.metadata.IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}

	/**
	 * A still cloak converted (or, {@code asIs}, a detected cloak texture normalized) and encoded as PNG within the
	 * server's upload limit - stepping the quality down if a busy 2K picture wouldn't fit.
	 */
	public static byte[] stillPng(Picture src, Detection d, Options o, boolean asIs) throws IOException {
		int maxWidth = MAX_WIDTH;
		while (true) {
			Picture texture = asIs ? normalize(src, d, maxWidth) : convert(src, o.withScale(Math.min(o.scale(), maxWidth / LAYOUT_WIDTH)));
			byte[] png = encodePng(texture);
			if (png.length <= MAX_UPLOAD_BYTES || texture.width() <= LAYOUT_WIDTH * 4) {
				return png;
			}
			maxWidth = texture.width() / 2;
		}
	}

	public static byte[] encodePng(Picture p) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(p.toImage(), "png", bytes);
		return bytes.toByteArray();
	}
}
