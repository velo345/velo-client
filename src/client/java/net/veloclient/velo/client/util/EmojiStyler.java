package net.veloclient.velo.client.util;

import net.minecraft.util.Identifier;

import java.util.Optional;

//? if <26.1 {
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
*///?}

/**
 * Emoji in chat: every emoji the font has (and every {@code :shortcode:} typed by players without Velo) is drawn
 * from the emoji font at its own colours, like Discord - the rest of the line keeps its style.
 */
public final class EmojiStyler {

	public static final Identifier FONT_ID = Identifier.of("velo-client", "emoji");
	//? if <26.1 {
	public static final StyleSpriteSource FONT = new StyleSpriteSource.Font(FONT_ID);
	//?} else {
	/*public static final FontDescription FONT = new FontDescription.Resource(FONT_ID);
	*///?}

	private EmojiStyler() {
	}

	/** The emoji-font style for one glyph, keeping hover/click/insertion of the surrounding text. */
	private static Style emojiStyle(Style style) {
		return style.withFont(FONT).withColor(0xFFFFFF).withBold(false).withItalic(false);
	}

	//? if <26.1 {
	public static Text emojify(Text input) {
		if (input == null) {
			return null;
		}
		boolean[] changed = {false};
		MutableText result = Text.empty();
		input.visit((style, string) -> {
			String text = EmojiData.replaceShortcodes(string);
			StringBuilder plain = new StringBuilder();
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				int len = Character.charCount(cp);
				if (cp == 0xFE0F) {
					i += len;
					changed[0] = true;
					continue;
				}
				if (EmojiData.isEmoji(cp)) {
					if (plain.length() > 0) {
						result.append(Text.literal(plain.toString()).setStyle(style));
						plain.setLength(0);
					}
					result.append(Text.literal(text.substring(i, i + len)).setStyle(emojiStyle(style)));
					changed[0] = true;
				} else {
					plain.append(text, i, i + len);
				}
				i += len;
			}
			if (plain.length() > 0) {
				result.append(Text.literal(plain.toString()).setStyle(style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return changed[0] ? result : input;
	}

	/** A text drawn in the emoji font (suggestion list). */
	public static Text glyph(String emoji) {
		return Text.literal(emoji).setStyle(Style.EMPTY.withFont(FONT));
	}
	//?} else {
	/*public static Component emojify(Component input) {
		if (input == null) {
			return null;
		}
		boolean[] changed = {false};
		MutableComponent result = Component.empty();
		input.visit((style, string) -> {
			String text = EmojiData.replaceShortcodes(string);
			StringBuilder plain = new StringBuilder();
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				int len = Character.charCount(cp);
				if (cp == 0xFE0F) {
					i += len;
					changed[0] = true;
					continue;
				}
				if (EmojiData.isEmoji(cp)) {
					if (plain.length() > 0) {
						result.append(Component.literal(plain.toString()).setStyle(style));
						plain.setLength(0);
					}
					result.append(Component.literal(text.substring(i, i + len)).setStyle(emojiStyle(style)));
					changed[0] = true;
				} else {
					plain.append(text, i, i + len);
				}
				i += len;
			}
			if (plain.length() > 0) {
				result.append(Component.literal(plain.toString()).setStyle(style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return changed[0] ? result : input;
	}

	public static Component glyph(String emoji) {
		return Component.literal(emoji).setStyle(Style.EMPTY.withFont(FONT));
	}
	*///?}
}
