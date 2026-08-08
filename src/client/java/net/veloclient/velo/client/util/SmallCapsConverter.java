package net.veloclient.velo.client.util;

//? if <26.1 {
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
//?} else {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
*///?}

import java.util.Optional;

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
 *
 * <p>Uppercase ASCII letters are folded to the same small-capital glyph as
 * their lowercase counterpart - the whole point of "small caps" styling is
 * a uniform cap-height look, so a literal already-uppercase letter has to
 * shrink too rather than being left at full height (which previously made
 * already-uppercase text, e.g. from a server's own MOTD/action bar strings,
 * visibly stick out taller than the rest of a small-caps line).
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
			char lower = Character.toLowerCase(c);
			if (lower == 'q') {
				result.append('Q');
				continue;
			}
			if (lower == 'x') {
				result.append('x');
				continue;
			}
			int index = LOWER.indexOf(lower);
			result.append(index >= 0 ? SMALL_CAPS[index] : c);
		}
		return result.toString();
	}

	/**
	 * Style-tree-preserving variant of {@link #toSmallCaps(String)} for real
	 * chat/action-bar {@code Text}/{@code Component} values - walks every
	 * independently-styled run via the vanilla styled visitor (which already
	 * resolves translatable text and merges parent/child style for each run)
	 * and rebuilds an equivalent tree with each run's own text converted but
	 * its own {@code Style} left untouched, rather than flattening the whole
	 * tree down to one plain string under a single root style. Flattening
	 * was a real, confirmed bug: any message built from multiple
	 * differently colored/formatted siblings (common for action bar text
	 * set by plugins, and for plenty of servers' chat) collapsed onto just
	 * the root's own style, silently discarding every other color/format in
	 * the message.
	 */
	//? if <26.1 {
	public static Text toSmallCapsStyled(Text input) {
		if (input == null) {
			return null;
		}
		MutableText result = Text.empty();
		input.visit((style, string) -> {
			result.append(Text.literal(toSmallCaps(string)).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return result;
	}
	//?} else {
	/*public static Component toSmallCapsStyled(Component input) {
		if (input == null) {
			return null;
		}
		MutableComponent result = Component.empty();
		input.visit((style, string) -> {
			result.append(Component.literal(toSmallCaps(string)).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return result;
	}
	*///?}
}
