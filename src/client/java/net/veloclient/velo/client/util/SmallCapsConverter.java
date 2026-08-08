package net.veloclient.velo.client.util;

/**
 * Converts lowercase ASCII letters to their Unicode "small capital" glyphs
 * (IPA Extensions / Latin Extended-B/D, e.g. U+1D00 LATIN LETTER SMALL
 * CAPITAL A) - the same technique long used by "fancy text" chat plugins,
 * rendered through Minecraft's completely normal text pipeline with no
 * custom font/resource pack needed.
 *
 * <p>Named to deliberately avoid "Text" anywhere in the class name -
 * stonecutter's bare-word {@code Text -> Component} rule (needed elsewhere
 * for real vanilla {@code Text} usages) isn't word-boundary-aware, and a
 * previous name containing "...TextUtil" got silently mangled into
 * "...ComponentUtil" for 26.1+, a real confirmed build failure, not a
 * hypothetical one.
 *
 * <p>Two letters are deliberately NOT mapped to their small-capital
 * codepoint: 'q' has no widely-supported one (the Unicode 14 addition,
 * U+A7AF, is too new to trust being in Minecraft's bundled font, so it's
 * left as a regular capital 'Q' instead - readable, guaranteed to render,
 * just very slightly taller than its neighbors) and 'x' is left as a plain
 * lowercase 'x' (its shape is already close enough to cap-height at a
 * glance that dedicated small-caps fonts commonly reuse it directly rather
 * than defining a separate glyph). Digits and everything else that isn't a
 * lowercase ASCII letter (numbers, punctuation, already-uppercase text)
 * pass through completely unchanged - they're already the right visual
 * height, nothing to convert.
 *
 * <p>Legacy formatting codes (the section-sign marker followed by one code
 * character, e.g. a reset or color code) are left completely untouched,
 * code character included - plenty of servers send plain strings with
 * these embedded directly in the text content rather than as real
 * {@code Style} data, and this used to convert the code character right
 * along with everything else, silently breaking the code (a real, confirmed
 * bug, not hypothetical).
 */
public final class SmallCapsConverter {

	private static final char FORMATTING_PREFIX = '§';
	private static final String LOWER = "abcdefghijklmnoprstuvwyz";
	private static final String[] SMALL_CAPS = {
			"ᴀ", "ʙ", "ᴄ", "ᴅ", "ᴇ", "ꜰ", "ɢ", "ʜ",
			"ɪ", "ᴊ", "ᴋ", "ʟ", "ᴍ", "ɴ", "ᴏ", "ᴘ",
			"ʀ", "ꜱ", "ᴛ", "ᴜ", "ᴠ", "ᴡ", "ʏ", "ᴢ"
	};

	private SmallCapsConverter() {
	}

	public static String toSmallCaps(String input) {
		if (input == null || input.isEmpty()) {
			return input;
		}
		StringBuilder result = new StringBuilder(input.length());
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == FORMATTING_PREFIX && i + 1 < input.length()) {
				result.append(c).append(input.charAt(i + 1));
				i++;
				continue;
			}
			if (c == 'q') {
				result.append('Q');
				continue;
			}
			int index = LOWER.indexOf(c);
			result.append(index >= 0 ? SMALL_CAPS[index] : c);
		}
		return result.toString();
	}
}
