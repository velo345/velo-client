package net.veloclient.velo.client.cosmetics.wear;

/**
 * Bodywear colours, shared with the launcher (copied in by its {@code bundleWearTint} task - plain Java only).
 *
 * <p>A colour code is 0 for the item's own colours, {@link #RGB} | 0xRRGGBB for a picked colour, or (from before colours
 * could be picked freely) 1-360 for a hue + 1. On the network a code is the 6 hex digits ("ff8800") or the old number.
 *
 * <p>{@link #colorize} paints the item's mask (its main material) in the colour: the mask's average brightness becomes
 * exactly the picked colour, darker and lighter pixels stay darker and lighter, so seams, shading and shine survive -
 * and black, white or any dark shade come out as picked.
 */
public final class WearTint {

	/** Marks a code as a picked RGB colour (keeps it clear of 0 and the old hue codes). */
	public static final int RGB = 0x1000000;

	/** The quick picks shown before the full colour picker. */
	public static final int[] PRESETS = {0x1C1C20, 0xF2F2F2, 0x8C8C94, 0xD62E3A, 0xF07A1F, 0xF4C21A, 0x7FD13B, 0x2E9E4F, 0x1FB5A8, 0x2F6FE0,
			0x8A46D8, 0xF06BB0, 0x7A4A2A, 0xD9A83A};
	public static final String[] PRESET_NAMES = {"Black", "White", "Grey", "Red", "Orange", "Yellow", "Lime", "Green", "Teal", "Blue", "Purple",
			"Pink", "Brown", "Gold"};

	private WearTint() {
	}

	public static int ofRgb(int rgb) {
		return RGB | (rgb & 0xFFFFFF);
	}

	/** A code's colour as 0xRRGGBB, or -1 for the item's own. */
	public static int rgb(int code) {
		if (code <= 0) {
			return -1;
		}
		if ((code & RGB) != 0) {
			return code & 0xFFFFFF;
		}
		// An old hue code: the same strong colour the hue slider showed.
		float h = (Math.min(360, code) - 1) / 60f;
		float s = 0.85f;
		int i = (int) Math.floor(h) % 6;
		float f = h - (float) Math.floor(h);
		float p = 1 - s;
		float q = 1 - s * f;
		float u = 1 - s * (1 - f);
		float[] c = switch (i) {
			case 0 -> new float[] {1, u, p};
			case 1 -> new float[] {q, 1, p};
			case 2 -> new float[] {p, 1, u};
			case 3 -> new float[] {p, q, 1};
			case 4 -> new float[] {u, p, 1};
			default -> new float[] {1, p, q};
		};
		return Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255);
	}

	/** True for codes this version understands (0, the old hues, picked colours). */
	public static boolean valid(int code) {
		return code >= 0 && code <= 360 || (code & ~0xFFFFFF) == RGB;
	}

	/** The network form of a code: null for the item's own, else 6 hex digits (old hue codes go out as their colour). */
	public static String encode(int code) {
		int rgb = rgb(code);
		return rgb < 0 ? null : String.format(java.util.Locale.ROOT, "%06x", rgb);
	}

	/** Reads a network colour: 6 hex digits, or an old hue number; 0 for anything else. */
	public static int decode(String text) {
		if (text == null) {
			return 0;
		}
		try {
			if (text.length() == 6) {
				return ofRgb(Integer.parseInt(text, 16));
			}
			int hue = Integer.parseInt(text);
			return hue >= 1 && hue <= 360 ? hue : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Paints {@code rgb} into the masked pixels of {@code argb} (in place). {@code mask} holds 0-255 per pixel (the mask
	 * image's red channel, already scaled to the texture's size).
	 */
	public static void colorize(int[] argb, int[] mask, int rgb) {
		double sum = 0;
		double weight = 0;
		for (int i = 0; i < argb.length; i++) {
			if (mask[i] > 5) {
				double w = mask[i] / 255.0;
				sum += lum(argb[i]) * w;
				weight += w;
			}
		}
		if (weight <= 0) {
			return;
		}
		float base = (float) Math.max(0.03, sum / weight);
		float tr = ((rgb >> 16) & 0xFF) / 255f;
		float tg = ((rgb >> 8) & 0xFF) / 255f;
		float tb = (rgb & 0xFF) / 255f;
		for (int i = 0; i < argb.length; i++) {
			if (mask[i] <= 5) {
				continue;
			}
			int p = argb[i];
			float ratio = lum(p) / base;
			float r;
			float g;
			float b;
			if (ratio <= 1) {
				r = tr * ratio;
				g = tg * ratio;
				b = tb * ratio;
			} else {
				// lighter than average: towards white, gently, so a highlight stays a highlight
				float lift = Math.min(1f, (ratio - 1) * 0.45f);
				r = tr + (1 - tr) * lift;
				g = tg + (1 - tg) * lift;
				b = tb + (1 - tb) * lift;
			}
			float amount = mask[i] / 255f;
			float or = ((p >> 16) & 0xFF) / 255f;
			float og = ((p >> 8) & 0xFF) / 255f;
			float ob = (p & 0xFF) / 255f;
			r = or + (r - or) * amount;
			g = og + (g - og) * amount;
			b = ob + (b - ob) * amount;
			argb[i] = (p & 0xFF000000) | channel(r) << 16 | channel(g) << 8 | channel(b);
		}
	}

	private static float lum(int p) {
		return (0.3f * ((p >> 16) & 0xFF) + 0.59f * ((p >> 8) & 0xFF) + 0.11f * (p & 0xFF)) / 255f;
	}

	private static int channel(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255)));
	}
}
