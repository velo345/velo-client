package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo - prepared text memo. Every frame the game "prepares" each piece of text again before drawing it: walks
 * it character by character, resolves every glyph in the font set, applies styles and builds a glyph list
 * ({@code Font.prepareText}). Text in the world - holograms, NPC names, signs - is the same frame after frame, so on a
 * server spawn this was 13% of the render thread (LuckySMP /spawn, 71 text displays, 2026-11-21), with Anti Coords
 * Leak scanning every line on top.
 *
 * <p>Now the prepared glyph list is kept and handed out again for the same text object with the same position, colour,
 * shadow and background (and the same Anti Coords Leak state). Prepared text is read-only once built, so reusing it
 * draws exactly the same thing. Never kept: obfuscated (§k) text, which changes every frame, and anything prepared off
 * the render thread. Everything is dropped whenever fonts reload (TurboFontReloadMixin), so no glyph from an old font
 * atlas is ever drawn. Off with {@code -Dvelo.textMemo=false} or Turbo off. 26.2+.
 */
public final class FontPrepareMemo {

	public static final boolean ON = !"false".equals(System.getProperty("velo.textMemo"));

	private FontPrepareMemo() {
	}

	//? if >=26.2 {
	/*private static final int SIZE = 4096;
	private static final Object[] TEXT = new Object[SIZE];
	private static final Object[] FONT = new Object[SIZE];
	private static final float[] X = new float[SIZE];
	private static final float[] Y = new float[SIZE];
	private static final int[] COLOR = new int[SIZE];
	private static final int[] BACKGROUND = new int[SIZE];
	private static final int[] FLAGS = new int[SIZE];
	private static final long[] USED = new long[SIZE];
	private static final net.minecraft.client.gui.Font.PreparedText[] VALUE = new net.minecraft.client.gui.Font.PreparedText[SIZE];
	private static long clock;
	private static final boolean PROFILE = Boolean.getBoolean("velo.renderProfile");
	public static int hits;
	public static int misses;

	public static net.minecraft.client.gui.Font.PreparedText get(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence text,
			float x, float y, int color, boolean shadow, boolean includeEmpty, int background,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.client.gui.Font.PreparedText> original) {
		if (!ON || !PerformanceBoostModule.turbo || text == null || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			return original.call(text, x, y, color, shadow, includeEmpty, background);
		}
		// Anti Coords Leak changes what the text turns into: off / scrambled / fake numbers are separate entries
		int flags = (shadow ? 1 : 0) | (includeEmpty ? 2 : 0) | (net.veloclient.velo.client.modules.qol.AntiCoordsLeakModule.hiding()
				? (net.veloclient.velo.client.privacy.CoordScrambler.fake ? 8 : 4) : 0);
		int h = System.identityHashCode(text) * 0x9E3779B1 ^ Float.floatToRawIntBits(x) * 31 ^ Float.floatToRawIntBits(y) * 17 ^ color * 7 ^ background;
		int a = (h ^ (h >>> 16)) & (SIZE - 1);
		int b = a ^ 1;
		long now = ++clock;
		if (PROFILE && (now & 0x3FFFF) == 0) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo text memo: {} hits, {} misses", hits, misses);
			hits = 0;
			misses = 0;
		}
		if (matches(a, font, text, x, y, color, background, flags)) {
			USED[a] = now;
			hits++;
			return VALUE[a];
		}
		if (matches(b, font, text, x, y, color, background, flags)) {
			USED[b] = now;
			hits++;
			return VALUE[b];
		}
		misses++;
		net.minecraft.client.gui.Font.PreparedText prepared = original.call(text, x, y, color, shadow, includeEmpty, background);
		if (obfuscated(text)) {
			return prepared;
		}
		int slot = USED[a] <= USED[b] ? a : b;
		TEXT[slot] = text;
		FONT[slot] = font;
		X[slot] = x;
		Y[slot] = y;
		COLOR[slot] = color;
		BACKGROUND[slot] = background;
		FLAGS[slot] = flags;
		USED[slot] = now;
		VALUE[slot] = prepared;
		return prepared;
	}

	private static boolean matches(int i, Object font, Object text, float x, float y, int color, int background, int flags) {
		return TEXT[i] == text && FONT[i] == font && X[i] == x && Y[i] == y && COLOR[i] == color && BACKGROUND[i] == background
				&& FLAGS[i] == flags;
	}

	private static boolean obfuscated(net.minecraft.util.FormattedCharSequence text) {
		return !text.accept((index, style, codePoint) -> !style.isObfuscated());
	}

	// Fonts reloaded (resource packs, GUI scale font switch...): every kept glyph list points into the old atlas.
	public static void clear() {
		java.util.Arrays.fill(TEXT, null);
		java.util.Arrays.fill(FONT, null);
		java.util.Arrays.fill(VALUE, null);
	}
	*///?}
}
