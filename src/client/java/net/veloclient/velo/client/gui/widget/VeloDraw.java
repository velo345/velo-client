package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.util.ClientCompat;

/**
 * Shared low-level drawing helpers for the custom widget toolkit.
 *
 * Rounded shapes are real geometry, not stair-stepped pixel rows: the outline is sampled as a
 * polygon, filled as a fan, and wrapped in a fringe one <i>screen</i> pixel wide whose outer
 * vertices fade to alpha 0. Vertex-color interpolation turns that fringe into an anti-aliased
 * edge, so corners stay smooth at every GUI scale (the old per-row version could only ever be as
 * fine as one GUI unit - 2-4 screen pixels - which is what made every corner look pixelated).
 * The fringe sits centered on the shape's edge, so straight edges on the pixel grid stay exactly
 * as crisp as before.
 *
 * Every shape is submitted as ONE gui element ({@link VeloShapeElement}) - vanilla's per-element
 * layering test is quadratic, so splitting a panel into many fills is expensive.
 * Render thread only (the builder below is shared, non-reentrant scratch space).
 */
public final class VeloDraw {

	private static float[] xy = new float[2048];
	private static int[] colors = new int[1024];
	private static int quadCount;
	private static float minX;
	private static float minY;
	private static float maxX;
	private static float maxY;

	// Ring scratch: up to 4 corners x (MAX_SEGMENTS + 1) points.
	private static final int MAX_SEGMENTS = 16;
	private static final float[][] RINGS = new float[4][4 * (MAX_SEGMENTS + 1) * 2];

	private VeloDraw() {
	}

	// ---------------------------------------------------------------- builder

	private static void begin() {
		quadCount = 0;
		minX = minY = Float.MAX_VALUE;
		maxX = maxY = -Float.MAX_VALUE;
	}

	private static void vertex(int index, float x, float y, int color) {
		xy[index * 2] = x;
		xy[index * 2 + 1] = y;
		colors[index] = color;
		if (x < minX) {
			minX = x;
		}
		if (x > maxX) {
			maxX = x;
		}
		if (y < minY) {
			minY = y;
		}
		if (y > maxY) {
			maxY = y;
		}
	}

	private static void quad(float ax, float ay, int ca, float bx, float by, int cb,
			float cx, float cy, int cc, float dx, float dy, int cd) {
		int base = quadCount * 4;
		if ((base + 4) * 2 > xy.length) {
			xy = java.util.Arrays.copyOf(xy, xy.length * 2);
			colors = java.util.Arrays.copyOf(colors, colors.length * 2);
		}
		// The GUI pipeline culls back faces: vanilla's own quads run counter-clockwise on screen, so
		// flip any quad wound the other way (ring strips come out in either orientation).
		float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) + (cx - ax) * (dy - ay) - (cy - ay) * (dx - ax);
		vertex(base, ax, ay, ca);
		vertex(base + 2, cx, cy, cc);
		if (area > 0) {
			vertex(base + 1, dx, dy, cd);
			vertex(base + 3, bx, by, cb);
		} else {
			vertex(base + 1, bx, by, cb);
			vertex(base + 3, dx, dy, cd);
		}
		quadCount++;
	}

	private static void rect(float x0, float y0, float x1, float y1, int color) {
		if (x0 == x1 || y0 == y1) {
			return;
		}
		quad(x0, y0, color, x0, y1, color, x1, y1, color, x1, y0, color);
	}

	private static void submit(DrawContext context) {
		if (quadCount == 0) {
			return;
		}
		VeloShapeElement.submit(context, xy, colors, quadCount,
				(int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY));
	}

	/** One screen pixel in GUI units - the width of the anti-aliasing fringe. */
	private static float pixel() {
		return 1f / Math.max(1, ClientCompat.guiScale());
	}

	private static int transparent(int argb) {
		return argb & 0x00FFFFFF;
	}

	private static int scaleAlpha(int argb, float factor) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (alpha << 24) | (argb & 0x00FFFFFF);
	}

	private static int segmentsFor(float radius) {
		float px = radius * Math.max(1, ClientCompat.guiScale());
		return Math.max(1, Math.min(MAX_SEGMENTS, (int) Math.ceil(px / 2.2f) + 1));
	}

	/**
	 * Samples the outline of a rounded rectangle moved {@code inset} units inward (negative =
	 * outward) into {@code out}; returns the point count. Every call with the same segment count
	 * yields corresponding points, so two rings can be stitched into a strip.
	 */
	private static int ring(float[] out, float x, float y, float w, float h, float radius, boolean roundBottom,
			float inset, int segments) {
		int n = 0;
		for (int corner = 0; corner < 4; corner++) {
			float r = corner >= 2 && !roundBottom ? 0f : radius;
			float reach = Math.max(r, inset);
			float rad = reach - inset;
			float cx = corner == 0 || corner == 3 ? x + reach : x + w - reach;
			float cy = corner <= 1 ? y + reach : y + h - reach;
			double start = Math.PI + corner * Math.PI / 2;
			for (int s = 0; s <= segments; s++) {
				double a = start + (Math.PI / 2) * s / segments;
				out[n * 2] = cx + (float) Math.cos(a) * rad;
				out[n * 2 + 1] = cy + (float) Math.sin(a) * rad;
				n++;
			}
		}
		return n;
	}

	/** Gradient color for a point at height {@code py}. */
	private static int shade(float py, float y, float h, int top, int bottom) {
		if (top == bottom || h <= 0) {
			return top;
		}
		return VeloAnim.lerpArgb(top, bottom, (py - y) / h);
	}

	private static void fan(float[] ring, int n, float y, float h, int top, int bottom) {
		float ox = ring[0];
		float oy = ring[1];
		int oc = shade(oy, y, h, top, bottom);
		for (int i = 1; i + 1 < n; i += 2) {
			int j = Math.min(i + 2, n - 1);
			quad(ox, oy, oc,
					ring[i * 2], ring[i * 2 + 1], shade(ring[i * 2 + 1], y, h, top, bottom),
					ring[(i + 1) * 2], ring[(i + 1) * 2 + 1], shade(ring[(i + 1) * 2 + 1], y, h, top, bottom),
					ring[j * 2], ring[j * 2 + 1], shade(ring[j * 2 + 1], y, h, top, bottom));
		}
	}

	/** Stitches two corresponding rings into a strip, inner points tinted {@code inner*}, outer {@code outer*}. */
	private static void strip(float[] a, float[] b, int n, float y, float h,
			int aTop, int aBottom, int bTop, int bBottom) {
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			quad(a[i * 2], a[i * 2 + 1], shade(a[i * 2 + 1], y, h, aTop, aBottom),
					b[i * 2], b[i * 2 + 1], shade(b[i * 2 + 1], y, h, bTop, bBottom),
					b[j * 2], b[j * 2 + 1], shade(b[j * 2 + 1], y, h, bTop, bBottom),
					a[j * 2], a[j * 2 + 1], shade(a[j * 2 + 1], y, h, aTop, aBottom));
		}
	}

	private static void addRoundedFill(float x, float y, float w, float h, float radius, boolean roundBottom,
			int top, int bottom) {
		if (w <= 0 || h <= 0) {
			return;
		}
		radius = Math.max(0, Math.min(radius, Math.min(w, h) / 2f));
		float half = Math.min(pixel() / 2f, Math.min(w, h) / 2f);
		int segments = segmentsFor(radius);
		int n = ring(RINGS[0], x, y, w, h, radius, roundBottom, half, segments);
		ring(RINGS[1], x, y, w, h, radius, roundBottom, -half, segments);
		fan(RINGS[0], n, y, h, top, bottom);
		strip(RINGS[0], RINGS[1], n, y, h, top, bottom, transparent(top), transparent(bottom));
	}

	// ---------------------------------------------------------------- public API

	public static void strokeRect(DrawContext context, int x, int y, int width, int height, int color) {
		begin();
		rect(x, y, x + width, y + 1, color);
		rect(x, y + height - 1, x + width, y + height, color);
		rect(x, y + 1, x + 1, y + height - 1, color);
		rect(x + width - 1, y + 1, x + width, y + height - 1, color);
		submit(context);
	}

	/** Fills a rectangle with smooth, anti-aliased circular corners. */
	public static void fillRounded(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		fillRounded(context, (float) x, y, width, height, radius, color);
	}

	/** Float variant, for things that animate (sliding knobs, growing bars) without snapping to whole GUI units. */
	public static void fillRounded(DrawContext context, float x, float y, float width, float height, float radius, int color) {
		begin();
		addRoundedFill(x, y, width, height, radius, true, color, color);
		submit(context);
	}

	/** Rounded fill with a vertical gradient from {@code top} to {@code bottom}. */
	public static void fillRoundedGradient(DrawContext context, int x, int y, int width, int height, int radius,
			int top, int bottom) {
		begin();
		addRoundedFill(x, y, width, height, radius, true, top, bottom);
		submit(context);
	}

	public static void fillRoundedBounds(DrawContext context, int x1, int y1, int x2, int y2, int radius, int color) {
		fillRounded(context, x1, y1, x2 - x1, y2 - y1, radius, color);
	}

	/**
	 * Like {@link #fillRounded} but only the top two corners are rounded - for a header band sitting
	 * flush against a rounded panel's top edge.
	 */
	public static void fillRoundedTop(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		begin();
		addRoundedFill(x, y, width, height, radius, false, color, color);
		submit(context);
	}

	/** One-unit rounded outline matching {@link #fillRounded}'s curve exactly. */
	public static void strokeRounded(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		strokeRounded(context, (float) x, y, width, height, radius, 1f, color);
	}

	public static void strokeRounded(DrawContext context, float x, float y, float width, float height, float radius,
			float thickness, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		radius = Math.max(0, Math.min(radius, Math.min(width, height) / 2f));
		thickness = Math.min(thickness, Math.min(width, height) / 2f);
		float half = pixel() / 2f;
		int segments = segmentsFor(radius);
		begin();
		int n = ring(RINGS[0], x, y, width, height, radius, true, -half, segments);
		ring(RINGS[1], x, y, width, height, radius, true, Math.min(half, thickness / 2f), segments);
		ring(RINGS[2], x, y, width, height, radius, true, Math.max(thickness / 2f, thickness - half), segments);
		ring(RINGS[3], x, y, width, height, radius, true, thickness + half, segments);
		int clear = transparent(color);
		strip(RINGS[1], RINGS[0], n, y, height, color, color, clear, clear);
		strip(RINGS[1], RINGS[2], n, y, height, color, color, color, color);
		strip(RINGS[2], RINGS[3], n, y, height, color, color, clear, clear);
		submit(context);
	}

	/** Fills an anti-aliased circle. */
	public static void fillCircle(DrawContext context, int centerX, int centerY, int radius, int color) {
		fillCircle(context, (float) centerX, centerY, radius, color);
	}

	public static void fillCircle(DrawContext context, float centerX, float centerY, float radius, int color) {
		fillRounded(context, centerX - radius, centerY - radius, radius * 2, radius * 2, radius, color);
	}

	/**
	 * A soft drop shadow around a rounded rectangle: {@code color} at the shape's edge fading to
	 * nothing over {@code spread} units (three rings approximate a gaussian falloff). Drawn
	 * before the shape itself; the inside is left untouched so translucent panels stay clean.
	 */
	public static void shadow(DrawContext context, int x, int y, int width, int height, int radius, int spread,
			int offsetY, int color) {
		if (spread <= 0 || width <= 0 || height <= 0) {
			return;
		}
		float fy = y + offsetY;
		int segments = segmentsFor(radius + spread);
		begin();
		float[] insets = {0f, -spread * 0.3f, -spread * 0.62f, -spread};
		float[] strength = {1f, 0.42f, 0.13f, 0f};
		int n = 0;
		for (int i = 0; i < 4; i++) {
			n = ring(RINGS[i], x, fy, width, height, radius, true, insets[i], segments);
		}
		for (int i = 0; i < 3; i++) {
			int a = scaleAlpha(color, strength[i]);
			int b = scaleAlpha(color, strength[i + 1]);
			strip(RINGS[i], RINGS[i + 1], n, fy, height, a, a, b, b);
		}
		submit(context);
	}

	/** An anti-aliased line segment with round-ish ends - used for the close X, checkmarks and chevrons. */
	public static void line(DrawContext context, float x1, float y1, float x2, float y2, float thickness, int color) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length <= 0f) {
			return;
		}
		float half = pixel() / 2f;
		float ux = dx / length;
		float uy = dy / length;
		// Extend the ends by half the thickness so joined segments (chevrons, checks) meet cleanly.
		float ext = thickness / 2f;
		float sx = x1 - ux * ext;
		float sy = y1 - uy * ext;
		float ex = x2 + ux * ext;
		float ey = y2 + uy * ext;
		float nx = -uy;
		float ny = ux;
		float core = Math.max(0f, thickness / 2f - half);
		float outer = thickness / 2f + half;
		int clear = transparent(color);
		begin();
		quad(sx + nx * core, sy + ny * core, color, ex + nx * core, ey + ny * core, color,
				ex - nx * core, ey - ny * core, color, sx - nx * core, sy - ny * core, color);
		quad(sx + nx * core, sy + ny * core, color, ex + nx * core, ey + ny * core, color,
				ex + nx * outer, ey + ny * outer, clear, sx + nx * outer, sy + ny * outer, clear);
		quad(sx - nx * core, sy - ny * core, color, ex - nx * core, ey - ny * core, color,
				ex - nx * outer, ey - ny * outer, clear, sx - nx * outer, sy - ny * outer, clear);
		submit(context);
	}

	/** A small "X" glyph centered on (cx, cy). */
	public static void cross(DrawContext context, float cx, float cy, float size, float thickness, int color) {
		float h = size / 2f;
		line(context, cx - h, cy - h, cx + h, cy + h, thickness, color);
		line(context, cx - h, cy + h, cx + h, cy - h, thickness, color);
	}

	/** A chevron pointing right ({@code direction} 0), down (1), left (2) or up (3). */
	public static void chevron(DrawContext context, float cx, float cy, float size, float thickness, int direction, int color) {
		float h = size / 2f;
		float q = size / 4f;
		switch (direction & 3) {
			case 0 -> {
				line(context, cx - q, cy - h, cx + q, cy, thickness, color);
				line(context, cx + q, cy, cx - q, cy + h, thickness, color);
			}
			case 1 -> {
				line(context, cx - h, cy - q, cx, cy + q, thickness, color);
				line(context, cx, cy + q, cx + h, cy - q, thickness, color);
			}
			case 2 -> {
				line(context, cx + q, cy - h, cx - q, cy, thickness, color);
				line(context, cx - q, cy, cx + q, cy + h, thickness, color);
			}
			default -> {
				line(context, cx - h, cy + q, cx, cy - q, thickness, color);
				line(context, cx, cy - q, cx + h, cy + q, thickness, color);
			}
		}
	}

	/** A small magnifying-glass glyph (search fields). */
	public static void searchGlyph(DrawContext context, float cx, float cy, float size, int color) {
		float r = size * 0.32f;
		float ox = cx - size * 0.1f;
		float oy = cy - size * 0.1f;
		strokeRounded(context, ox - r, oy - r, r * 2, r * 2, r, Math.max(1f, size * 0.12f), color);
		float d = r * 0.72f;
		line(context, ox + d, oy + d, cx + size * 0.42f, cy + size * 0.42f, Math.max(1f, size * 0.13f), color);
	}

	/** A plain gradient rectangle (no rounding), e.g. for fades at the edge of a scroll list. */
	public static void fillGradient(DrawContext context, int x, int y, int width, int height, int top, int bottom) {
		begin();
		quad(x, y, top, x, y + height, bottom, x + width, y + height, bottom, x + width, y, top);
		submit(context);
	}
}
