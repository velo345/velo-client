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

	private static final int MAX = 6;
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
			current = EmojiData.search(q, MAX);
			selected = 0;
		}
	}

	public static boolean visible() {
		return !current.isEmpty();
	}

	public static void move(int delta) {
		if (!current.isEmpty()) {
			selected = Math.floorMod(selected + delta, current.size());
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
		int width = 0;
		for (EmojiData.Suggestion s : current) {
			width = Math.max(width, textRenderer.getWidth(":" + s.name() + ":"));
		}
		width += 30;
		int height = current.size() * row + 6;
		int top = bottom - height;
		context.fill(x, top, x + width, bottom, 0xE6121217);
		context.fill(x, top, x + 2, bottom, 0xFFE0262E);
		for (int i = 0; i < current.size(); i++) {
			EmojiData.Suggestion s = current.get(i);
			int y = top + 3 + i * row;
			if (i == selected) {
				context.fill(x + 2, y - 1, x + width, y + row - 1, 0x66E0262E);
			}
			context.drawTextWithShadow(textRenderer, EmojiStyler.glyph(s.emoji()), x + 6, y + 1, 0xFFFFFFFF);
			context.drawTextWithShadow(textRenderer, Text.literal(":" + s.name() + ":"), x + 20, y + 1, i == selected ? 0xFFFFFFFF : 0xFFB4B4BE);
		}
		context.drawTextWithShadow(textRenderer, Text.literal("Tab"), x + width - textRenderer.getWidth("Tab") - 4, top + 3 + selected * row + 1, 0xFF8F8F9A);
	}
}
