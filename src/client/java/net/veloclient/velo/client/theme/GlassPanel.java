package net.veloclient.velo.client.theme;

import net.minecraft.client.gui.DrawContext;

/**
 * Draws a themed "glass" panel background: the theme's surface color at its
 * configured opacity, plus a top accent-gradient hairline. True GPU backdrop
 * blur (a real post-processing shader sampling the framebuffer behind the
 * panel) is a documented follow-up - this approximates the glass look with
 * layered translucency, which is cheap and doesn't require a custom shader
 * pipeline to get the panel system working end-to-end first.
 */
public final class GlassPanel {

	private GlassPanel() {
	}

	public static void draw(DrawContext context, int x, int y, int width, int height) {
		Theme theme = ThemeManager.active();
		context.fill(x, y, x + width, y + height, theme.surfaceWithOpacity());
		drawAccentGradient(context, x, y, width, theme);
		int borderAlpha = (int) (40 + theme.blurIntensity() * 40) << 24;
		context.fill(x, y, x + width, y + 1, borderAlpha | 0xFFFFFF);
	}

	private static void drawAccentGradient(DrawContext context, int x, int y, int width, Theme theme) {
		int steps = Math.max(4, width / 24);
		for (int i = 0; i < steps; i++) {
			float t = i / (float) (steps - 1);
			int color = lerpArgb(theme.accentStart(), theme.accentEnd(), t);
			int segmentX = x + Math.round(i * width / (float) steps);
			int segmentWidth = Math.round(width / (float) steps) + 1;
			context.fill(segmentX, y, segmentX + segmentWidth, y + 2, color);
		}
	}

	private static int lerpArgb(int a, int b, float t) {
		int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int ra = Math.round(aa + (ba - aa) * t);
		int rr = Math.round(ar + (br - ar) * t);
		int rg = Math.round(ag + (bg - ag) * t);
		int rb = Math.round(ab + (bb - ab) * t);
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}
}
