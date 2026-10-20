package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;

import java.util.Locale;

/**
 * The launcher's store card, in game: a dark card with the picture on top, a thin line in the item's
 * colour, then a small coloured kicker ("WINGS · AFTERLIFE"), the name and the price - or an "Owned"
 * chip. Wing, aura and cape cards share it so the grids read as one store.
 */
public final class StoreCard {

	/** Height of the text block under the picture. */
	public static final int INFO_H = 38;
	private static final int RADIUS = 7;

	private StoreCard() {
	}

	/** The card body; returns how far it lifts on hover (draw the picture shifted by it). */
	public static int frame(DrawContext context, int x, int y, int w, int h, float hover, int accent) {
		int lift = Math.round(2 * hover);
		y -= lift;
		if (hover > 0.01f) {
			VeloDraw.shadow(context, x, y, w, h, RADIUS, 12, 5, VeloUi.withAlpha(0xFF000000, Math.round(0x90 * hover)));
		}
		VeloDraw.fillRounded(context, x, y, w, h, RADIUS, 0xFF15151B);
		VeloDraw.fillRoundedGradient(context, x, y, w, h - INFO_H, RADIUS, VeloUi.withAlpha(accent, 0x1C + Math.round(0x14 * hover)), 0x00191920);
		context.fill(x, y + h - INFO_H - 1, x + w, y + h - INFO_H + 1, VeloUi.withAlpha(accent, 0xB0));
		VeloDraw.strokeRounded(context, x, y, w, h, RADIUS, VeloAnim.lerpArgb(0x17FFFFFF, 0x40FFFFFF, hover));
		return lift;
	}

	/**
	 * The text block. {@code state} ("Owned", "Equipped") replaces the price when set.
	 */
	public static void info(DrawContext context, TextRenderer renderer, int x, int y, int w, int h, int lift, String kicker, int accent,
			String name, String state, int price) {
		int top = y - lift + h - INFO_H + 6;
		int kickerColor = VeloAnim.lerpArgb(accent | 0xFF000000, 0xFFFFFFFF, 0.25f);
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.small(kicker.toUpperCase(Locale.ROOT)), w - 14), x + 7, top, kickerColor);
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(name), w - 14), x + 7, top + 9, MenuKit.TEXT);
		int rowY = top + 21;
		if (state != null) {
			Text chip = MenuKit.ui(state);
			int cw = renderer.getWidth(chip) + 16;
			VeloDraw.fillRounded(context, x + 6, rowY - 2, cw, 11, 5, 0x263DDC97);
			VeloDraw.fillCircle(context, x + 11.5f, rowY + 3.5f, 1.8f, MenuKit.GREEN);
			MenuKit.text(context, renderer, chip, x + 16, rowY, MenuKit.GREEN);
		} else {
			coin(context, x + 11f, rowY + 3.5f, 3.6f);
			MenuKit.text(context, renderer, MenuKit.uiBold(String.format(Locale.ROOT, "%,d", price)), x + 17, rowY, 0xFFFFD56A);
		}
	}

	/** The Velo Coin: gold disc with a darker rim. */
	public static void coin(DrawContext context, float cx, float cy, float r) {
		VeloDraw.fillCircle(context, cx, cy, r, 0xFFC9861A);
		VeloDraw.fillCircle(context, cx, cy, r - 0.9f, 0xFFFFD56A);
	}
}
