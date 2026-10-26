package net.veloclient.velo.client.gui.packs;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Pixel operations of the texture editor on plain ARGB arrays ({@code w * h}, row by row): blending, brushes,
 * shapes, flood fill and the magic wand, selections and filters. No Minecraft types here.
 *
 * <p>A {@code mask} (selection) is a {@code boolean[w * h]}; null means "everything". Every write respects it.
 */
public final class PixelOps {

	private PixelOps() {
	}

	// ---- Colours ----

	public static int a(int c) {
		return c >>> 24;
	}

	public static int r(int c) {
		return (c >> 16) & 0xFF;
	}

	public static int g(int c) {
		return (c >> 8) & 0xFF;
	}

	public static int b(int c) {
		return c & 0xFF;
	}

	public static int argb(int a, int r, int g, int b) {
		return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
	}

	public static int clamp(int v) {
		return v < 0 ? 0 : Math.min(255, v);
	}

	/** {@code src} drawn over {@code dst} at {@code opacity} (0..1). */
	public static int over(int dst, int src, float opacity) {
		float sa = a(src) / 255f * opacity;
		if (sa <= 0f) {
			return dst;
		}
		float da = a(dst) / 255f;
		float outA = sa + da * (1 - sa);
		if (outA <= 0f) {
			return 0;
		}
		int r = Math.round((r(src) * sa + r(dst) * da * (1 - sa)) / outA);
		int g = Math.round((g(src) * sa + g(dst) * da * (1 - sa)) / outA);
		int b = Math.round((b(src) * sa + b(dst) * da * (1 - sa)) / outA);
		return argb(Math.round(outA * 255), r, g, b);
	}

	public static int lerp(int c1, int c2, float t) {
		return argb(Math.round(a(c1) + (a(c2) - a(c1)) * t), Math.round(r(c1) + (r(c2) - r(c1)) * t),
				Math.round(g(c1) + (g(c2) - g(c1)) * t), Math.round(b(c1) + (b(c2) - b(c1)) * t));
	}

	/** Colour distance 0..1 (RGBA, alpha weighted in). */
	public static float distance(int c1, int c2) {
		if (a(c1) == 0 && a(c2) == 0) {
			return 0f;
		}
		int dr = r(c1) - r(c2);
		int dg = g(c1) - g(c2);
		int db = b(c1) - b(c2);
		int da = a(c1) - a(c2);
		return (float) Math.sqrt((dr * dr + dg * dg + db * db + da * da) / (4.0 * 255 * 255));
	}

	/** {h 0..360, s 0..1, v 0..1}. */
	public static float[] hsv(int c) {
		float r = r(c) / 255f;
		float g = g(c) / 255f;
		float b = b(c) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h;
		if (d == 0) {
			h = 0;
		} else if (max == r) {
			h = 60 * (((g - b) / d) % 6);
		} else if (max == g) {
			h = 60 * ((b - r) / d + 2);
		} else {
			h = 60 * ((r - g) / d + 4);
		}
		if (h < 0) {
			h += 360;
		}
		return new float[] {h, max == 0 ? 0 : d / max, max};
	}

	public static int fromHsv(float h, float s, float v, int alpha) {
		h = ((h % 360) + 360) % 360;
		s = Math.max(0, Math.min(1, s));
		v = Math.max(0, Math.min(1, v));
		float c = v * s;
		float x = c * (1 - Math.abs((h / 60) % 2 - 1));
		float m = v - c;
		float r;
		float g;
		float b;
		if (h < 60) {
			r = c;
			g = x;
			b = 0;
		} else if (h < 120) {
			r = x;
			g = c;
			b = 0;
		} else if (h < 180) {
			r = 0;
			g = c;
			b = x;
		} else if (h < 240) {
			r = 0;
			g = x;
			b = c;
		} else if (h < 300) {
			r = x;
			g = 0;
			b = c;
		} else {
			r = c;
			g = 0;
			b = x;
		}
		return argb(alpha, Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
	}

	public static float luma(int c) {
		return (0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)) / 255f;
	}

	// ---- Painting ----

	private static boolean allowed(boolean[] mask, int i) {
		return mask == null || mask[i];
	}

	/** Paint modes of {@link #stamp}. */
	public enum Mode { PAINT, ERASE, LIGHTEN, DARKEN, DITHER }

	/**
	 * One brush dab centred on (cx, cy): {@code size} pixels across, round or square. {@code touched} (may be null)
	 * remembers pixels already hit in this stroke so dragging over them doesn't build up opacity / shading.
	 */
	public static void stamp(int[] px, int w, int h, boolean[] mask, int cx, int cy, int size, boolean round, int color,
			float opacity, Mode mode, boolean[] touched) {
		int r0 = (size - 1) / 2;
		int r1 = size / 2;
		float rad = size / 2f;
		for (int y = cy - r0; y <= cy + r1; y++) {
			for (int x = cx - r0; x <= cx + r1; x++) {
				if (x < 0 || y < 0 || x >= w || y >= h) {
					continue;
				}
				if (round && size > 2) {
					float dx = x - cx + (size % 2 == 0 ? -0.5f : 0f);
					float dy = y - cy + (size % 2 == 0 ? -0.5f : 0f);
					if (dx * dx + dy * dy > rad * rad + 0.3f) {
						continue;
					}
				}
				int i = y * w + x;
				if (!allowed(mask, i) || (touched != null && touched[i])) {
					continue;
				}
				if (touched != null) {
					touched[i] = true;
				}
				px[i] = paint(px[i], x, y, color, opacity, mode);
			}
		}
	}

	private static int paint(int dst, int x, int y, int color, float opacity, Mode mode) {
		return switch (mode) {
			case PAINT -> opacity >= 0.999f ? color : over(dst, color, opacity);
			case ERASE -> {
				int alpha = Math.round(a(dst) * (1 - opacity));
				yield alpha <= 0 ? 0 : (dst & 0xFFFFFF) | alpha << 24;
			}
			case LIGHTEN, DARKEN -> {
				if (a(dst) == 0) {
					yield dst;
				}
				float[] hsv = hsv(dst);
				float amount = 0.12f * Math.max(0.15f, opacity);
				if (mode == Mode.LIGHTEN) {
					hsv[2] = Math.min(1, hsv[2] + amount);
					hsv[1] = Math.max(0, hsv[1] - amount * 0.35f);
				} else {
					hsv[2] = Math.max(0, hsv[2] - amount);
					hsv[1] = Math.min(1, hsv[1] + amount * 0.25f);
				}
				yield fromHsv(hsv[0], hsv[1], hsv[2], a(dst));
			}
			case DITHER -> (x + y) % 2 == 0 ? (opacity >= 0.999f ? color : over(dst, color, opacity)) : dst;
		};
	}

	/** Calls {@code dab} on every pixel of the line (Bresenham), both ends included. */
	public static void line(int x0, int y0, int x1, int y1, java.util.function.BiConsumer<Integer, Integer> dab) {
		int dx = Math.abs(x1 - x0);
		int dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		while (true) {
			dab.accept(x0, y0);
			if (x0 == x1 && y0 == y1) {
				return;
			}
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x0 += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	/** Pixels of a rectangle (outline or filled) between two corners. */
	public static void rect(int x0, int y0, int x1, int y1, boolean filled, java.util.function.BiConsumer<Integer, Integer> dab) {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minY = Math.min(y0, y1);
		int maxY = Math.max(y0, y1);
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				if (filled || x == minX || x == maxX || y == minY || y == maxY) {
					dab.accept(x, y);
				}
			}
		}
	}

	/** Pixels of an ellipse inscribed in the rectangle between two corners (outline or filled). */
	public static void ellipse(int x0, int y0, int x1, int y1, boolean filled, java.util.function.BiConsumer<Integer, Integer> dab) {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minY = Math.min(y0, y1);
		int maxY = Math.max(y0, y1);
		float cx = (minX + maxX) / 2f;
		float cy = (minY + maxY) / 2f;
		float rx = (maxX - minX) / 2f + 0.5f;
		float ry = (maxY - minY) / 2f + 0.5f;
		boolean[][] in = new boolean[maxY - minY + 3][maxX - minX + 3];
		for (int y = minY - 1; y <= maxY + 1; y++) {
			for (int x = minX - 1; x <= maxX + 1; x++) {
				float dx = (x - cx) / rx;
				float dy = (y - cy) / ry;
				in[y - minY + 1][x - minX + 1] = dx * dx + dy * dy <= 1.0f;
			}
		}
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				int iy = y - minY + 1;
				int ix = x - minX + 1;
				if (!in[iy][ix]) {
					continue;
				}
				boolean edge = !in[iy - 1][ix] || !in[iy + 1][ix] || !in[iy][ix - 1] || !in[iy][ix + 1];
				if (filled || edge) {
					dab.accept(x, y);
				}
			}
		}
	}

	// ---- Fill & wand ----

	/** Pixels similar to the one at (x, y): its connected region ({@code contiguous}) or every match in the image. */
	public static boolean[] similar(int[] px, int w, int h, int x, int y, float tolerance, boolean contiguous) {
		boolean[] out = new boolean[w * h];
		if (x < 0 || y < 0 || x >= w || y >= h) {
			return out;
		}
		int target = px[y * w + x];
		if (!contiguous) {
			for (int i = 0; i < px.length; i++) {
				out[i] = distance(px[i], target) <= tolerance;
			}
			return out;
		}
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(y * w + x);
		out[y * w + x] = true;
		while (!queue.isEmpty()) {
			int i = queue.poll();
			int cx = i % w;
			int cy = i / w;
			int[][] next = {{cx + 1, cy}, {cx - 1, cy}, {cx, cy + 1}, {cx, cy - 1}};
			for (int[] n : next) {
				if (n[0] < 0 || n[1] < 0 || n[0] >= w || n[1] >= h) {
					continue;
				}
				int j = n[1] * w + n[0];
				if (!out[j] && distance(px[j], target) <= tolerance) {
					out[j] = true;
					queue.add(j);
				}
			}
		}
		return out;
	}

	/** Bucket fill: every similar pixel (inside the selection) gets {@code color}. */
	public static void fill(int[] px, int w, int h, boolean[] mask, int x, int y, int color, float tolerance, boolean contiguous, float opacity) {
		boolean[] region = similar(px, w, h, x, y, tolerance, contiguous);
		for (int i = 0; i < px.length; i++) {
			if (region[i] && allowed(mask, i)) {
				px[i] = opacity >= 0.999f ? color : over(px[i], color, opacity);
			}
		}
	}

	/** Linear gradient from (x0, y0) colour {@code c0} to (x1, y1) colour {@code c1} over the selection. */
	public static void gradient(int[] px, int w, int h, boolean[] mask, int x0, int y0, int x1, int y1, int c0, int c1, int steps) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len2 = Math.max(1e-4f, dx * dx + dy * dy);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (!allowed(mask, i)) {
					continue;
				}
				float t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / len2));
				if (steps > 1) {
					t = Math.round(t * (steps - 1)) / (float) (steps - 1);
				}
				px[i] = over(px[i], lerp(c0, c1, t), 1f);
			}
		}
	}

	// ---- Selections ----

	public static boolean[] rectMask(int w, int h, int x0, int y0, int x1, int y1) {
		boolean[] out = new boolean[w * h];
		rect(x0, y0, x1, y1, true, (x, y) -> {
			if (x >= 0 && y >= 0 && x < w && y < h) {
				out[y * w + x] = true;
			}
		});
		return out;
	}

	public static boolean[] ellipseMask(int w, int h, int x0, int y0, int x1, int y1) {
		boolean[] out = new boolean[w * h];
		ellipse(x0, y0, x1, y1, true, (x, y) -> {
			if (x >= 0 && y >= 0 && x < w && y < h) {
				out[y * w + x] = true;
			}
		});
		return out;
	}

	/** Pixels whose centre lies inside the polygon {x0, y0, x1, y1, ...} (pixel coordinates, + 0.5 = centre). */
	public static boolean[] polygonMask(int w, int h, float[] points) {
		boolean[] out = new boolean[w * h];
		int n = points.length / 2;
		if (n < 3) {
			return out;
		}
		for (int y = 0; y < h; y++) {
			float py = y + 0.5f;
			for (int x = 0; x < w; x++) {
				float px = x + 0.5f;
				boolean inside = false;
				for (int i = 0, j = n - 1; i < n; j = i++) {
					float xi = points[i * 2];
					float yi = points[i * 2 + 1];
					float xj = points[j * 2];
					float yj = points[j * 2 + 1];
					if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
						inside = !inside;
					}
				}
				out[y * w + x] = inside;
			}
		}
		return out;
	}

	/** Combines a new selection with the current one: 0 replace, 1 add, 2 subtract, 3 intersect. */
	public static boolean[] combine(boolean[] current, boolean[] added, int op) {
		if (op == 0 || current == null && op != 2) {
			return op == 2 ? null : added;
		}
		boolean[] out = new boolean[added.length];
		for (int i = 0; i < out.length; i++) {
			boolean c = current == null || current[i];
			out[i] = switch (op) {
				case 1 -> c || added[i];
				case 2 -> c && !added[i];
				default -> c && added[i];
			};
		}
		return out;
	}

	public static boolean any(boolean[] mask) {
		if (mask == null) {
			return false;
		}
		for (boolean b : mask) {
			if (b) {
				return true;
			}
		}
		return false;
	}

	public static boolean[] invert(boolean[] mask, int size) {
		boolean[] out = new boolean[size];
		for (int i = 0; i < size; i++) {
			out[i] = mask == null ? false : !mask[i];
		}
		return out;
	}

	// ---- Filters (all respect the mask) ----

	public interface PixelFilter {
		int apply(int c, int x, int y);
	}

	public static void filter(int[] px, int w, int h, boolean[] mask, PixelFilter f) {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (allowed(mask, i) && a(px[i]) > 0) {
					px[i] = f.apply(px[i], x, y);
				}
			}
		}
	}

	/** Hue shift (degrees), saturation and brightness (-1..1 each). */
	public static PixelFilter hsb(float hue, float saturation, float brightness) {
		return (c, x, y) -> {
			float[] hsv = hsv(c);
			float s = saturation >= 0 ? hsv[1] + (1 - hsv[1]) * saturation : hsv[1] * (1 + saturation);
			float v = brightness >= 0 ? hsv[2] + (1 - hsv[2]) * brightness : hsv[2] * (1 + brightness);
			return fromHsv(hsv[0] + hue, s, v, a(c));
		};
	}

	/**
	 * GIMP's "Colorize": every pixel takes its luminance, the lightness offset is applied, and it's rebuilt in HSL
	 * with the given hue and saturation - so the shading survives and the whole area turns one colour family.
	 * {@code hue} 0..360, {@code saturation} 0..1, {@code lightness} -1..1.
	 */
	public static PixelFilter colorize(float hue, float saturation, float lightness) {
		return (c, x, y) -> {
			float lum = luma(c);
			if (lightness > 0) {
				lum = lum * (1 - lightness) + lightness;
			} else if (lightness < 0) {
				lum = lum * (1 + lightness);
			}
			return fromHsl(hue, saturation, lum, a(c));
		};
	}

	/** HSL (h 0..360, s and l 0..1) to ARGB. */
	public static int fromHsl(float h, float s, float l, int alpha) {
		float c = (1 - Math.abs(2 * l - 1)) * s;
		float v = l + c / 2;
		float sv = v <= 0 ? 0 : 2 * (1 - l / v);
		return fromHsv(h, sv, v, alpha);
	}

	/** {h 0..360, s 0..1, l 0..1} of a colour. */
	public static float[] hsl(int c) {
		float[] hsv = hsv(c);
		float l = hsv[2] * (1 - hsv[1] / 2);
		float s = l <= 0 || l >= 1 ? 0 : (hsv[2] - l) / Math.min(l, 1 - l);
		return new float[] {hsv[0], s, l};
	}

	/** Replaces colours close to {@code from} with {@code to}, moving each pixel by the same offset (keeps shading). */
	public static PixelFilter replace(int from, int to, float tolerance) {
		float[] f = hsv(from);
		float[] t = hsv(to);
		return (c, x, y) -> {
			if (distance(c, from) > tolerance) {
				return c;
			}
			float[] hsv = hsv(c);
			return fromHsv(hsv[0] + (t[0] - f[0]), hsv[1] + (t[1] - f[1]), hsv[2] + (t[2] - f[2]), a(c));
		};
	}

	public static PixelFilter invertColors() {
		return (c, x, y) -> (c & 0xFF000000) | (~c & 0xFFFFFF);
	}

	public static PixelFilter grayscale() {
		return (c, x, y) -> {
			int l = Math.round(luma(c) * 255);
			return argb(a(c), l, l, l);
		};
	}

	public static PixelFilter sepia() {
		return (c, x, y) -> {
			int r = r(c);
			int g = g(c);
			int b = b(c);
			return argb(a(c), (int) (r * 0.393 + g * 0.769 + b * 0.189), (int) (r * 0.349 + g * 0.686 + b * 0.168),
					(int) (r * 0.272 + g * 0.534 + b * 0.131));
		};
	}

	/** Contrast (-1..1) around mid grey. */
	public static PixelFilter contrast(float amount) {
		float k = amount >= 0 ? 1 + amount * 2 : 1 + amount;
		return (c, x, y) -> argb(a(c), Math.round((r(c) - 128) * k + 128), Math.round((g(c) - 128) * k + 128), Math.round((b(c) - 128) * k + 128));
	}

	public static PixelFilter posterize(int levels) {
		int n = Math.max(2, levels);
		return (c, x, y) -> {
			int r = Math.round(Math.round(r(c) / 255f * (n - 1)) * 255f / (n - 1));
			int g = Math.round(Math.round(g(c) / 255f * (n - 1)) * 255f / (n - 1));
			int b = Math.round(Math.round(b(c) / 255f * (n - 1)) * 255f / (n - 1));
			return argb(a(c), r, g, b);
		};
	}

	/** Per-pixel brightness noise (amount 0..1), the same for the same seed. */
	public static PixelFilter noise(float amount, long seed) {
		return (c, x, y) -> {
			Random random = new Random(seed * 31 + x * 7919L + y * 104729L);
			int d = Math.round((random.nextFloat() * 2 - 1) * amount * 64);
			return argb(a(c), r(c) + d, g(c) + d, b(c) + d);
		};
	}

	/** Adds a 1 px outline of {@code color} around opaque pixels (on transparent neighbours inside the selection). */
	public static void outline(int[] px, int w, int h, boolean[] mask, int color, boolean diagonal) {
		int[] src = px.clone();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (a(src[i]) > 0 || !allowed(mask, i)) {
					continue;
				}
				boolean near = false;
				for (int dy = -1; dy <= 1 && !near; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						if ((dx == 0 && dy == 0) || (!diagonal && dx != 0 && dy != 0)) {
							continue;
						}
						int nx = x + dx;
						int ny = y + dy;
						if (nx >= 0 && ny >= 0 && nx < w && ny < h && a(src[ny * w + nx]) > 0) {
							near = true;
							break;
						}
					}
				}
				if (near) {
					px[i] = color;
				}
			}
		}
	}

	/** 3x3 box blur of the selected pixels (alpha weighted). */
	public static void blur(int[] px, int w, int h, boolean[] mask) {
		int[] src = px.clone();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (!allowed(mask, i)) {
					continue;
				}
				float sa = 0;
				float sr = 0;
				float sg = 0;
				float sb = 0;
				int n = 0;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = Math.max(0, Math.min(w - 1, x + dx));
						int ny = Math.max(0, Math.min(h - 1, y + dy));
						int c = src[ny * w + nx];
						float al = a(c) / 255f;
						sa += al;
						sr += r(c) * al;
						sg += g(c) * al;
						sb += b(c) * al;
						n++;
					}
				}
				px[i] = sa <= 0 ? 0 : argb(Math.round(sa / n * 255), Math.round(sr / sa), Math.round(sg / sa), Math.round(sb / sa));
			}
		}
	}

	/** Flips the selected area (or everything) horizontally / vertically within its bounds. */
	public static void flip(int[] px, int w, int h, boolean[] mask, boolean horizontal) {
		int[] bounds = bounds(mask, w, h);
		int[] src = px.clone();
		for (int y = bounds[1]; y <= bounds[3]; y++) {
			for (int x = bounds[0]; x <= bounds[2]; x++) {
				int i = y * w + x;
				int sx = horizontal ? bounds[0] + bounds[2] - x : x;
				int sy = horizontal ? y : bounds[1] + bounds[3] - y;
				int j = sy * w + sx;
				if (allowed(mask, i) && allowed(mask, j)) {
					px[i] = src[j];
				}
			}
		}
	}

	/** Rotates a square image 90 degrees clockwise. */
	public static int[] rotate(int[] px, int size) {
		int[] out = new int[px.length];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				out[x * size + (size - 1 - y)] = px[y * size + x];
			}
		}
		return out;
	}

	/** Shifts the image by (dx, dy), wrapping around - for checking tiling seams. */
	public static int[] offset(int[] px, int w, int h, int dx, int dy) {
		int[] out = new int[px.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out[Math.floorMod(y + dy, h) * w + Math.floorMod(x + dx, w)] = px[y * w + x];
			}
		}
		return out;
	}

	/** {minX, minY, maxX, maxY} of the selection (the whole image without one). */
	public static int[] bounds(boolean[] mask, int w, int h) {
		if (mask == null) {
			return new int[] {0, 0, w - 1, h - 1};
		}
		int minX = w;
		int minY = h;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (mask[y * w + x]) {
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return maxX < 0 ? new int[] {0, 0, w - 1, h - 1} : new int[] {minX, minY, maxX, maxY};
	}

	/** Nearest-neighbour resample (pixel art stays crisp). */
	public static int[] resample(int[] px, int w, int h, int nw, int nh) {
		int[] out = new int[nw * nh];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				out[y * nw + x] = px[Math.min(h - 1, y * h / nh) * w + Math.min(w - 1, x * w / nw)];
			}
		}
		return out;
	}

	/** The distinct colours of the image, most used first (up to {@code max}). */
	public static int[] palette(int[] px, int max) {
		java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
		for (int c : px) {
			if (a(c) > 0) {
				counts.merge(c, 1, Integer::sum);
			}
		}
		return counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(max)
				.sorted((x, y) -> Float.compare(hsv(x.getKey())[0] + luma(x.getKey()) * 0.01f, hsv(y.getKey())[0] + luma(y.getKey()) * 0.01f))
				.mapToInt(java.util.Map.Entry::getKey).toArray();
	}
}
