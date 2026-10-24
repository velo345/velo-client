package net.veloclient.velo.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.modules.hud.HudStyleModule;

/**
 * How HUD elements draw text and panels, following {@link HudStyleModule} (font, background, accent, shadow).
 * With that module off everything falls back to the pre-0.9.8 look: Minecraft font, no panel.
 *
 * <p>The common case is {@link #stat}: a value and a small unit ("247 FPS", "18 ms") as one element - bold value,
 * muted unit, on a pill when there's a background.
 */
public final class HudLook {

	private static final int PAD_X = 6;
	private static final int PAD_Y = 4;
	private static final int GLASS = 0xA00C0C10;
	private static final int SOLID = 0xF0141418;

	private HudLook() {
	}

	private static HudStyleModule style() {
		HudStyleModule style = HudStyleModule.get();
		return style != null && style.isEnabled() ? style : null;
	}

	/** True when the 0.9.8 look is on (otherwise: vanilla font, no panels). */
	public static boolean on() {
		return style() != null;
	}

	public static String font() {
		HudStyleModule style = style();
		return style == null ? "Minecraft" : style.font();
	}

	/**
	 * The HUD fonts beyond Inter, by their name in HUD Style. All sit on the same baseline as Inter (shift 0) - Sora used
	 * the menus' display_sm, one pixel higher, so numbers stood above their units.
	 */
	private static final java.util.Map<String, String> FONT_IDS = java.util.Map.of("Sora", "hud_sora", "Space Grotesk", "hud_space_grotesk",
			"Poppins", "hud_poppins", "IBM Plex Sans", "hud_plex", "JetBrains Mono", "hud_mono", "Audiowide", "hud_audiowide");

	/** {@code s} in a HUD Style font by its name (the font menu shows each choice in its own font). */
	public static Text preview(String fontName, String s) {
		String id = FONT_IDS.get(fontName);
		return id != null ? MenuKit.inNamedFont(s, id) : fontName.equals("Inter") ? MenuKit.uiBold(s) : Text.literal(s);
	}

	/** A value in the HUD font, bold. */
	public static Text value(String s) {
		return preview(font(), s);
	}

	/** Secondary text (units, names) in the HUD font (Inter next to Sora, as before). */
	public static Text label(String s) {
		return switch (font()) {
			case "Inter", "Sora" -> MenuKit.ui(s);
			case "Minecraft" -> Text.literal(s);
			default -> preview(font(), s);
		};
	}

	private static Text inHudFont(Text t) {
		String id = FONT_IDS.get(font());
		return id != null ? MenuKit.inNamedFont(t, id) : MenuKit.inFont(t, false);
	}

	/** Line height of HUD text. */
	public static int lineHeight() {
		return font().equals("Minecraft") ? MinecraftClient.getInstance().textRenderer.fontHeight - 1 : MenuKit.H_UI;
	}

	/** True when HUD text uses Velo's fonts (style on and not the Minecraft font). */
	private static boolean fontActive() {
		return style() != null && !font().equals("Minecraft");
	}

	/**
	 * Every HUD element draws its text through this (instead of drawing with the vanilla font itself), so the whole HUD switches
	 * font together. Off / Minecraft font: exactly the vanilla call.
	 */
	public static void draw(DrawContext context, String s, int x, int y, int color) {
		if (!fontActive()) {
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, s, x, y, color);
			return;
		}
		text(context, value(s), x, y + 1, color);
	}

	public static void draw(DrawContext context, Text t, int x, int y, int color) {
		if (!fontActive()) {
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, t, x, y, color);
			return;
		}
		text(context, inHudFont(t), x, y + 1, color);
	}

	/** The width {@link #draw} will draw {@code s} at. */
	public static int measure(String s) {
		return fontActive() ? width(value(s)) : MinecraftClient.getInstance().textRenderer.getWidth(s);
	}

	public static int measure(Text t) {
		return fontActive() ? width(inHudFont(t)) : MinecraftClient.getInstance().textRenderer.getWidth(t);
	}

	public static int width(Text text) {
		return MinecraftClient.getInstance().textRenderer.getWidth(text);
	}

	/** {@link #width} without rounding up - TTF glyphs have fractional widths, and centring on the rounded one leans left. */
	public static float exactWidth(Text text) {
		//? if <26.1 {
		return MinecraftClient.getInstance().textRenderer.getTextHandler().getWidth(text);
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().font.getSplitter().stringWidth(text);
		*///?}
	}

	public static boolean hasBackground() {
		HudStyleModule style = style();
		return style != null && !style.background().equals("None");
	}

	public static int accent() {
		HudStyleModule style = style();
		return style == null ? 0xFFFF4444 : style.accent() | 0xFF000000;
	}

	/**
	 * A module's own colour setting against the global style: once the player changed it from the module's default it wins
	 * (that one module looks the way they set it), otherwise the style's {@code styled} colour applies while HUD Style is on.
	 */
	public static int own(int value, int moduleDefault, int styled) {
		return value != moduleDefault || !on() ? value : styled;
	}

	public static void text(DrawContext context, Text text, int x, int y, int color) {
		HudStyleModule style = style();
		if (style == null || style.shadow()) {
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x, y, color);
		} else {
			MenuKit.text(context, MinecraftClient.getInstance().textRenderer, text, x, y, color);
		}
	}

	/** The panel behind an element, per the style (nothing without a background). */
	public static void panel(DrawContext context, int x, int y, int w, int h) {
		HudStyleModule style = style();
		if (style == null || style.background().equals("None")) {
			return;
		}
		int radius = style.rounded() ? Math.min(h / 2, 7) : 2;
		VeloDraw.fillRounded(context, x, y, w, h, radius, style.background().equals("Solid") ? SOLID : GLASS);
		VeloDraw.strokeRounded(context, x, y, w, h, radius, 0x1CFFFFFF);
	}

	/** Extra width an element gets from its panel. */
	public static int padX() {
		return hasBackground() ? PAD_X : 0;
	}

	public static int padY() {
		return hasBackground() ? PAD_Y : 0;
	}

	// ---- "247 FPS" ----

	private static Text unitText(String unit) {
		return label(unit);
	}

	public static int statWidth(String value, String unit) {
		int w = width(value(value));
		if (unit != null && !unit.isEmpty()) {
			w += 3 + width(unitText(unit));
		}
		return w + padX() * 2;
	}

	public static int statHeight() {
		return lineHeight() + padY() * 2;
	}

	/** A value with a muted unit after it, as one HUD element (unit may be null). */
	public static void stat(DrawContext context, int x, int y, String value, String unit, int valueColor) {
		int w = statWidth(value, unit);
		panel(context, x, y, w, statHeight());
		int tx = x + padX();
		int ty = y + padY();
		Text v = value(value);
		text(context, v, tx, ty, valueColor);
		if (unit != null && !unit.isEmpty()) {
			text(context, unitText(unit), tx + width(v) + 3, ty, on() ? 0xFFB4B4BE : valueColor);
		}
	}

	/** A plain line of text in the HUD font on the HUD panel. */
	public static void line(DrawContext context, int x, int y, String s, int color) {
		Text t = value(s);
		panel(context, x, y, width(t) + padX() * 2, statHeight());
		text(context, t, x + padX(), y + padY(), color);
	}

	public static int lineWidth(String s) {
		return width(value(s)) + padX() * 2;
	}
}
