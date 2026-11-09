package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * The emoji list above the chat box while you type {@code :na}: up to six matches, Up/Down to choose, Tab to put the
 * emoji in (EmojiSuggestMixin wires it to the chat screen).
 */
public final class EmojiSuggest {

	public record Completion(String text, int cursor) {
	}

	private static final int MAX = 40;
	private static final int SHOWN = 8;
	private static int scroll;
	private static List<EmojiData.Suggestion> current = List.of();
	private static String query;
	private static int selected;

	private EmojiSuggest() {
	}

	public static void update(String text, int cursor) {
		String q = EmojiData.typedQuery(text, cursor);
		if (q == null) {
			current = List.of();
			query = null;
			return;
		}
		if (!q.equals(query)) {
			query = q;
			current = q.isEmpty() ? EmojiData.starters(MAX) : EmojiData.search(q, MAX);
			selected = 0;
			scroll = 0;
		}
	}

	public static boolean visible() {
		return !current.isEmpty();
	}

	public static void move(int delta) {
		if (!current.isEmpty()) {
			selected = Math.floorMod(selected + delta, current.size());
			if (selected < scroll) {
				scroll = selected;
			} else if (selected >= scroll + SHOWN) {
				scroll = selected - SHOWN + 1;
			}
		}
	}

	/** Replaces the {@code :query} before the cursor with the chosen emoji (and a space). */
	public static Completion complete(String text, int cursor) {
		if (current.isEmpty() || query == null) {
			return null;
		}
		int start = cursor - query.length() - 1;
		if (start < 0 || text.charAt(start) != ':') {
			return null;
		}
		String emoji = current.get(selected).emoji();
		String next = text.substring(0, start) + emoji + " " + text.substring(cursor);
		current = List.of();
		query = null;
		return new Completion(next, start + emoji.length() + 1);
	}

	/** Draws the list with its bottom edge at {@code bottom}, left-aligned at {@code x}. */
	public static void render(DrawContext context, int x, int bottom) {
		if (current.isEmpty()) {
			return;
		}
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int row = 12;
		int shown = Math.min(SHOWN, current.size() - scroll);
		int width = 0;
		for (EmojiData.Suggestion s : current) {
			width = Math.max(width, textRenderer.getWidth(":" + s.name() + ":") + (s.symbol() ? Math.max(0, textRenderer.getWidth(s.emoji()) - 10) : 0));
		}
		width += 34;
		int height = shown * row + 6;
		int top = bottom - height;
		context.fill(x, top, x + width, bottom, 0xE6121217);
		context.fill(x, top, x + 2, bottom, 0xFFE0262E);
		for (int k = 0; k < shown; k++) {
			int i = scroll + k;
			EmojiData.Suggestion s = current.get(i);
			int y = top + 3 + k * row;
			if (i == selected) {
				context.fill(x + 2, y - 1, x + width, y + row - 1, 0x66E0262E);
			}
			Text glyph = s.symbol() ? Text.literal(s.emoji()) : EmojiStyler.glyph(s.emoji());
			context.drawTextWithShadow(textRenderer, glyph, x + 6, y + 1, 0xFFFFFFFF);
			int nameX = x + 20 + (s.symbol() ? Math.max(0, textRenderer.getWidth(s.emoji()) - 10) : 0);
			context.drawTextWithShadow(textRenderer, Text.literal(":" + s.name() + ":"), nameX, y + 1, i == selected ? 0xFFFFFFFF : 0xFFB4B4BE);
		}
		if (current.size() > SHOWN) {
			// a thin scroll bar on the right
			int barH = Math.max(6, height * SHOWN / current.size());
			int barY = top + (height - barH) * scroll / Math.max(1, current.size() - SHOWN);
			context.fill(x + width - 2, barY, x + width, barY + barH, 0x80FFFFFF);
		}
		context.drawTextWithShadow(textRenderer, Text.literal("Tab"), x + width - textRenderer.getWidth("Tab") - 5, top + 3 + (selected - scroll) * row + 1, 0xFF8F8F9A);
	}
}
