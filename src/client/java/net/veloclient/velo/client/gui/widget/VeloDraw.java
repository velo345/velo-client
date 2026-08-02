package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;

/** Shared low-level drawing helpers for the custom widget toolkit. */
public final class VeloDraw {

	private VeloDraw() {
	}

	public static void strokeRect(DrawContext context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y, x + 1, y + height, color);
		context.fill(x + width - 1, y, x + width, y + height, color);
	}

	/**
	 * Fills a rectangle with staircase-cut corners - a cheap, pixel-art-appropriate
	 * approximation of rounded corners using only {@code DrawContext#fill}, since
	 * there's no arc-fill primitive available. {@code radius} of 3-5px reads as
	 * "softened" at typical UI scale without needing a texture atlas.
	 */
	public static void fillRounded(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		radius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		if (radius == 0) {
			context.fill(x, y, x + width, y + height, color);
			return;
		}
		// Core cross: full-width middle band + full-height center band.
		context.fill(x, y + radius, x + width, y + height - radius, color);
		context.fill(x + radius, y, x + width - radius, y + radius, color);
		context.fill(x + radius, y + height - radius, x + width - radius, y + height, color);
		// Staircase the four corners.
		for (int i = 0; i < radius; i++) {
			int inset = radius - i;
			int rowY1 = y + i;
			int rowY2 = y + height - i - 1;
			context.fill(x + inset, rowY1, x + width - inset, rowY1 + 1, color);
			context.fill(x + inset, rowY2, x + width - inset, rowY2 + 1, color);
		}
	}

	public static void fillRoundedBounds(DrawContext context, int x1, int y1, int x2, int y2, int radius, int color) {
		fillRounded(context, x1, y1, x2 - x1, y2 - y1, radius, color);
	}

	/**
	 * Fills an actual circle, row by row via the circle equation. {@link
	 * #fillRounded} looks fine for small corner radii, but its linear
	 * staircase produces a diamond (not a circle) once the radius covers the
	 * whole shape - exactly the case for something like the sound radar.
	 */
	public static void fillCircle(DrawContext context, int centerX, int centerY, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			int halfWidth = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
			if (halfWidth <= 0) {
				continue;
			}
			context.fill(centerX - halfWidth, centerY + dy, centerX + halfWidth, centerY + dy + 1, color);
		}
	}
}
