package net.veloclient.velo.client.gui.widget;

/** Small animation helpers shared by every custom widget: frame-rate-independent easing and ARGB color lerp. */
public final class VeloAnim {

	private static final float RATE = 12f;

	private VeloAnim() {
	}

	/** Exponential ease toward {@code target}, independent of frame rate given a per-frame {@code deltaSeconds}. */
	public static float step(float current, float target, float deltaSeconds) {
		if (deltaSeconds <= 0) {
			return current;
		}
		float t = 1f - (float) Math.exp(-RATE * deltaSeconds);
		return current + (target - current) * t;
	}

	public static int lerpArgb(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int ra = Math.round(aa + (ba - aa) * t);
		int rr = Math.round(ar + (br - ar) * t);
		int rg = Math.round(ag + (bg - ag) * t);
		int rb = Math.round(ab + (bb - ab) * t);
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}
}
