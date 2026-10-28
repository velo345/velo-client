package net.veloclient.velo.client.nowplaying;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;

/** The media symbols, drawn as shapes (crisp at any GUI scale) - centred on (cx, cy), {@code s} is the glyph's height. */
public final class MediaGlyphs {

	private MediaGlyphs() {
	}

	public static void play(DrawContext context, float cx, float cy, float s, int color) {
		float h = s / 2f;
		float w = s * 0.86f;
		float left = cx - w * 0.42f;
		VeloDraw.fillQuad(context, left, cy - h, left + w, cy, left + w, cy, left, cy + h, color);
	}

	public static void pause(DrawContext context, float cx, float cy, float s, int color) {
		float bw = s * 0.3f;
		float gap = s * 0.2f;
		VeloDraw.fillRounded(context, cx - gap / 2 - bw, cy - s / 2, bw, s, bw * 0.35f, color);
		VeloDraw.fillRounded(context, cx + gap / 2, cy - s / 2, bw, s, bw * 0.35f, color);
	}

	/** Two triangles and a bar; {@code dir} 1 = next, -1 = previous. */
	public static void skip(DrawContext context, float cx, float cy, float s, int dir, int color) {
		float h = s / 2f;
		float tw = s * 0.42f;
		float start = cx - dir * tw;
		for (int i = 0; i < 2; i++) {
			float a = start + dir * tw * i;
			VeloDraw.fillQuad(context, a, cy - h, a + dir * tw, cy, a + dir * tw, cy, a, cy + h, color);
		}
		float bar = s * 0.16f;
		float bx = dir > 0 ? cx + tw : cx - tw - bar;
		VeloDraw.fillRounded(context, bx, cy - h, bar, s, bar / 2f, color);
	}

	/** A speaker with 0-2 waves by level (0..1), struck through when muted. */
	public static void volume(DrawContext context, float cx, float cy, float s, double level, int color) {
		float left = cx - s * 0.5f;
		float boxH = s * 0.36f;
		VeloDraw.fillRounded(context, left, cy - boxH / 2, s * 0.22f, boxH, 0.6f, color);
		VeloDraw.fillQuad(context, left + s * 0.2f, cy - boxH / 2, left + s * 0.48f, cy - s * 0.44f, left + s * 0.48f, cy + s * 0.44f,
				left + s * 0.2f, cy + boxH / 2, color);
		float wx = left + s * 0.5f;
		if (level <= 0.001) {
			VeloDraw.line(context, wx + s * 0.08f, cy - s * 0.18f, wx + s * 0.44f, cy + s * 0.18f, Math.max(1f, s * 0.11f), color);
			VeloDraw.line(context, wx + s * 0.08f, cy + s * 0.18f, wx + s * 0.44f, cy - s * 0.18f, Math.max(1f, s * 0.11f), color);
			return;
		}
		float t = Math.max(1f, s * 0.1f);
		VeloDraw.strokeArc(context, wx - s * 0.06f, cy, s * 0.22f, t, 45, 90, color);
		if (level > 0.45) {
			VeloDraw.strokeArc(context, wx - s * 0.06f, cy, s * 0.42f, t, 45, 90, color);
		}
	}

	/** A note, for when there's no cover. */
	public static void note(DrawContext context, float cx, float cy, float s, int color) {
		float head = s * 0.2f;
		VeloDraw.fillCircle(context, cx - s * 0.16f, cy + s * 0.28f, head, color);
		VeloDraw.fillRounded(context, cx - s * 0.16f + head - s * 0.08f, cy - s * 0.42f, s * 0.1f, s * 0.72f, 0.5f, color);
		VeloDraw.line(context, cx - s * 0.16f + head - s * 0.04f, cy - s * 0.38f, cx + s * 0.3f, cy - s * 0.2f, Math.max(1f, s * 0.12f), color);
	}

	/**
	 * Three bouncing bars - the "it's playing" sign. {@code energy} 0..1 eases them flat when paused (the caller animates it).
	 * Returns the width drawn.
	 */
	public static float equalizer(DrawContext context, float x, float bottom, float h, float energy, int color) {
		double t = System.nanoTime() / 1_000_000_000.0;
		float bw = Math.max(1.5f, h * 0.22f);
		float gap = bw * 0.7f;
		double[] speed = {7.1, 9.7, 5.9};
		double[] phase = {0.0, 1.7, 3.1};
		for (int i = 0; i < 3; i++) {
			double wave = 0.5 + 0.5 * Math.sin(t * speed[i] + phase[i]) * Math.sin(t * speed[i] * 0.37 + phase[i] * 2);
			float bh = (float) (h * (0.22 + 0.78 * Math.abs(wave) * energy));
			VeloDraw.fillRounded(context, x + i * (bw + gap), bottom - bh, bw, bh, bw / 2f, color);
		}
		return bw * 3 + gap * 2;
	}
}
