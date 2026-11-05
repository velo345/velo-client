package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * Tab list layout with Velo's badge and Name Styles. Vanilla sizes the name column from the plain name, but Velo draws
 * a badge in front and styled (often wider) names - so long names ran into the next column and the ping. Here the
 * column is measured the way Velo draws the name, and when vanilla caps the column (narrow window, many players) the
 * name is cut with "…" before the score / ping area instead of overlapping it.
 *
 * <p>Vanilla's row: {@code width = min(cols * (head + maxName + score + 13), screen - 50) / cols}; the name starts after
 * the head, the score sits right after the widest name, the ping icon takes the last 12 pixels.
 */
public final class TabNames {

	/** This frame's row bounds (vanilla's per-row background fill) and the score column width. */
	private static int rowLeft;
	private static int rowRight = Integer.MAX_VALUE;
	private static int scoreMax;
	private static boolean hearts;
	private static boolean objective;

	private TabNames() {
	}

	/** Start of vanilla's tab render. */
	public static void begin(boolean hasObjective, boolean heartsObjective) {
		objective = hasObjective;
		hearts = heartsObjective;
		scoreMax = 0;
		rowLeft = 0;
		rowRight = Integer.MAX_VALUE;
	}

	/** Vanilla measured one score's text: the score column is the widest plus a space. */
	public static int scoreWidth(TextRenderer textRenderer, int width) {
		if (width > 0) {
			scoreMax = Math.max(scoreMax, textRenderer.getWidth(" ") + width);
		}
		return width;
	}

	/** Vanilla's background fill for one row. */
	public static void row(int left, int right) {
		rowLeft = left;
		rowRight = right;
	}

	private static int scoreColumn() {
		return !objective ? 0 : hearts ? 90 : scoreMax;
	}

	/** Width of the name as Velo draws it (styled, badge in front): what vanilla sizes the column with. */
	public static int measure(TextRenderer textRenderer, Object name) {
		if (name instanceof Text text) {
			Object[] row = rowFor(text);
			if (row != null) {
				int width = textRenderer.getWidth((Text) row[0]);
				return (Boolean) row[1] ? width + badgeShift((Boolean) row[2]) : width;
			}
		}
		//? if <26.1 {
		return textRenderer.getWidth((net.minecraft.text.StringVisitable) name);
		//?} else {
		/*return textRenderer.width((net.minecraft.network.chat.FormattedText) name);
		*///?}
	}

	public static int badgeShift(boolean plus) {
		return VeloBadge.width(plus) + 3;
	}

	/** Room for the name drawn at {@code x}: up to where the score column (then the ping) begins. */
	public static int room(int x) {
		if (rowRight == Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return rowRight - 12 - scoreColumn() - 1 - x;
	}

	/** The score's x range moved left when the row is narrower than vanilla planned, so it stays clear of the ping. */
	public static int[] scoreRange(int left, int right) {
		int limit = rowRight == Integer.MAX_VALUE ? right : rowRight - 12;
		if (right > limit) {
			int shift = right - limit;
			return new int[] {Math.max(rowLeft, left - shift), right - shift};
		}
		return new int[] {left, right};
	}

	/** {styled name, Velo user?, Velo+?} for a tab row, cached per row text (null without a player). */
	public static Object[] rowFor(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		Object[] row = (Object[]) net.veloclient.velo.client.util.TabListCache.row(text);
		if (row == null) {
			// Name Styles, also when another mod kept NameStyleTabMixin from tagging the row.
			Text tagged = net.veloclient.velo.client.namestyle.NameStyles.tagTabRow(text);
			String plain = tagged.getString();
			String own = client.player.getGameProfile().name();
			boolean known = VeloBadge.isKnownVeloName(plain, own);
			row = new Object[] {tagged, known, known && VeloBadge.isPlus(plain, own)};
			net.veloclient.velo.client.util.TabListCache.storeRow(text, row);
		}
		return row;
	}

	/** Draws {@code text} at x/y, cut with "…" to {@code room} pixels. */
	public static void draw(net.minecraft.client.gui.DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color, int room) {
		if (room == Integer.MAX_VALUE || textRenderer.getWidth(text) <= room) {
			context.drawTextWithShadow(textRenderer, text, x, y, color);
			return;
		}
		String dots = "…";
		int dotsWidth = textRenderer.getWidth(dots);
		if (room <= dotsWidth) {
			return;
		}
		//? if <26.1 {
		var ordered = net.minecraft.util.Language.getInstance().reorder(textRenderer.trimToWidth(text, room - dotsWidth));
		//?} else {
		/*var ordered = net.minecraft.locale.Language.getInstance().getVisualOrder(textRenderer.substrByWidth(text, room - dotsWidth));
		*///?}
		context.drawTextWithShadow(textRenderer, ordered, x, y, color);
		context.drawTextWithShadow(textRenderer, Text.literal(dots).setStyle(text.getStyle()), x + textRenderer.getWidth(ordered), y, color);
	}
}
