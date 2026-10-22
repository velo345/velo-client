package net.veloclient.velo.client.gui.title;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloNavIcons;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The 0.9.7 menu look shared by the title screen and the pause menu (and the redesigned Velo screens): Sora for
 * headings, Inter for text, JetBrains Mono for numbers and keys; graphite glass panels with a hairline edge; the
 * theme accent only for what's primary or active. Same tokens as the launcher.
 */
public final class MenuKit {

	public static final int TEXT = 0xFFF4F4F6;
	public static final int TEXT_2 = 0xFFA9A9B3;
	public static final int TEXT_3 = 0xFF6E6E78;
	public static final int PANEL = 0xC40E0E12;
	public static final int SURFACE_2 = 0x0FFFFFFF;
	public static final int HAIRLINE = 0x17FFFFFF;
	public static final int GREEN = 0xFF3DDC97;
	public static final int GOLD = 0xFFF7C04A;
	public static final int BLUE = 0xFF5AA9FF;
	public static final int RED_TEXT = 0xFFFF7A7E;

	private static final Identifier LOGO = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final Identifier DISPLAY = Identifier.of("velo-client", "display");
	private static final Identifier DISPLAY_SM = Identifier.of("velo-client", "display_sm");
	private static final Identifier DISPLAY_LG = Identifier.of("velo-client", "display_lg");
	private static final Identifier HERO = Identifier.of("velo-client", "hero");
	private static final Identifier UI_SM = Identifier.of("velo-client", "ui_sm");
	private static final Identifier MONO = Identifier.of("velo-client", "mono");
	private static final Identifier UI = Identifier.of("velo-client", "ui");
	private static final Identifier UI_BOLD = Identifier.of("velo-client", "ui_bold");

	/** Line heights of the fonts above, in GUI pixels (for centring text in a row). */
	public static final int H_DISPLAY = 10;
	public static final int H_DISPLAY_SM = 8;
	public static final int H_DISPLAY_LG = 14;
	public static final int H_HERO = 21;
	public static final int H_MONO = 6;
	public static final int H_UI = 8;

	private MenuKit() {
	}

	// ---- Colours ----

	public static int accent() {
		return ThemeManager.active().accentStart() | 0xFF000000;
	}

	/** The accent lifted for text on dark glass (readable even for dark accents). */
	public static int accentText() {
		return VeloAnim.lerpArgb(accent(), 0xFFFFFFFF, 0.28f);
	}

	public static int onAccent() {
		return ThemeManager.active().onAccent();
	}

	// ---- Fonts ----

	public static Text display(String s) {
		return font(s, DISPLAY);
	}

	public static Text displaySm(String s) {
		return font(s, DISPLAY_SM);
	}

	public static Text displayLg(String s) {
		return font(s, DISPLAY_LG);
	}

	/** Inter SemiBold 7 - kickers ("WINGS · AFTERLIFE"), section labels and counts, like the launcher's small caps. */
	public static Text small(String s) {
		return font(s, UI_SM);
	}

	/** Sora Bold 26 - store hero titles. */
	public static Text hero(String s) {
		return font(s, HERO);
	}

	public static Text mono(String s) {
		return font(s, MONO);
	}

	public static Text ui(String s) {
		return font(s, UI);
	}

	public static Text uiBold(String s) {
		return font(s, UI_BOLD);
	}

	/** {@code text} (a vanilla button label, any language) in the given Velo font, keeping its words. */
	public static Text restyle(Text text, boolean bold) {
		return font(text.getString(), bold ? UI_BOLD : UI);
	}

	/** {@code text} keeping its own colours and formatting, in Inter semibold - or Sora when {@code sora} (the HUD's fonts). */
	//? if <26.1 {
	public static Text inFont(Text text, boolean sora) {
		var copy = text.copy();
		copy.setStyle(copy.getStyle().withFont(new net.minecraft.text.StyleSpriteSource.Font(sora ? DISPLAY_SM : UI_BOLD)));
		return copy;
	}
	//?} else {
	/*public static Text inFont(Text text, boolean sora) {
		var copy = text.copy();
		copy.setStyle(copy.getStyle().withFont(new net.minecraft.network.chat.FontDescription.Resource(sora ? DISPLAY_SM : UI_BOLD)));
		return copy;
	}
	*///?}

	/** {@code s} / {@code text} in one of Velo's fonts by name ("hud_sora", "ui_bold"...), keeping the text's own styling. */
	public static Text inNamedFont(String s, String fontPath) {
		return font(s, Identifier.of("velo-client", fontPath));
	}

	//? if <26.1 {
	public static Text inNamedFont(Text text, String fontPath) {
		var copy = text.copy();
		copy.setStyle(copy.getStyle().withFont(new net.minecraft.text.StyleSpriteSource.Font(Identifier.of("velo-client", fontPath))));
		return copy;
	}
	//?} else {
	/*public static Text inNamedFont(Text text, String fontPath) {
		var copy = text.copy();
		copy.setStyle(copy.getStyle().withFont(new net.minecraft.network.chat.FontDescription.Resource(Identifier.of("velo-client", fontPath))));
		return copy;
	}
	*///?}

	//? if <26.1 {
	private static Text font(String s, Identifier id) {
		return Text.literal(s).setStyle(Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(id)));
	}
	//?} else {
	/*private static Text font(String s, Identifier id) {
		return Text.literal(s).setStyle(Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(id)));
	}
	*///?}

	public static int width(TextRenderer renderer, Text text) {
		return renderer.getWidth(text);
	}

	/** Text without the vanilla drop shadow. */
	public static void text(DrawContext context, TextRenderer renderer, Text text, int x, int y, int color) {
		context.drawText(renderer, text, x, y, color, false);
	}

	/** Text cut with "..." only as a last resort, at {@code maxWidth}. */
	public static Text fit(TextRenderer renderer, Text text, int maxWidth) {
		if (renderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String s = text.getString();
		Style style = text.getStyle();
		for (int n = s.length() - 1; n > 0; n--) {
			Text cut = Text.literal(s.substring(0, n).stripTrailing() + "...").setStyle(style);
			if (renderer.getWidth(cut) <= maxWidth) {
				return cut;
			}
		}
		return Text.literal("...").setStyle(style);
	}

	/** Word-wraps {@code text} measured in the font {@code style} applies (Sora is wider than the default font). */
	public static java.util.List<Text> wrap(TextRenderer renderer, String text, java.util.function.Function<String, Text> style, int maxWidth) {
		java.util.List<Text> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && renderer.getWidth(style.apply(candidate)) > maxWidth) {
				lines.add(style.apply(line.toString()));
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0) {
			lines.add(style.apply(line.toString()));
		}
		return lines;
	}

	// ---- Shapes ----

	public static void logo(DrawContext context, int x, int y, int size) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0f, 0f, size, size, 500, 500, 500, 500);
	}

	public static void icon(DrawContext context, String key, int x, int y, int size, int color) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, VeloNavIcons.of(key), x, y, 0f, 0f, size, size, 1024, 1024, 1024, 1024, color);
	}

	public static void texture(DrawContext context, Identifier texture, int x, int y, int size, int sourceSize) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, sourceSize, sourceSize, sourceSize, sourceSize);
	}

	/** A glass card: soft shadow, dark translucent fill, hairline edge. */
	public static void panel(DrawContext context, int x, int y, int w, int h, int radius) {
		VeloDraw.shadow(context, x, y, w, h, radius, 14, 4, 0x55000000);
		VeloDraw.fillRounded(context, x, y, w, h, radius, PANEL);
		VeloDraw.strokeRounded(context, x, y, w, h, radius, HAIRLINE);
	}

	/** An inner tile on a panel. */
	public static void tile(DrawContext context, int x, int y, int w, int h, int radius) {
		VeloDraw.fillRounded(context, x, y, w, h, radius, SURFACE_2);
	}

	public static void hairline(DrawContext context, int x, int y, int w) {
		context.fill(x, y, x + w, y + 1, HAIRLINE);
	}

	/** A small switch (on = accent); {@code progress} 0..1 animates the knob. */
	public static void toggle(DrawContext context, int x, int y, float progress) {
		int w = 16;
		int h = 9;
		int track = VeloAnim.lerpArgb(0xFF3A3A42, accent(), progress);
		VeloDraw.fillRounded(context, x, y, w, h, h / 2, track);
		float knobX = x + 1 + (w - h) * progress;
		VeloDraw.fillCircle(context, knobX + (h - 2) / 2f, y + h / 2f, (h - 2) / 2f, 0xFFFFFFFF);
	}

	/** A keyboard key hint ("ESC", "R-SHIFT") in mono, right-aligned at {@code right}. */
	public static void keyHint(DrawContext context, TextRenderer renderer, String key, int right, int y, int color) {
		if (key == null || key.isEmpty()) {
			return;
		}
		Text t = mono(key);
		text(context, renderer, t, right - renderer.getWidth(t), y, color);
	}

	// ---- Motion ----

	private static final Map<Object, float[]> HOVER = new WeakHashMap<>();

	/** Eased 0..1 hover progress per object (a widget, a hotspot), stepped every frame it's asked. */
	public static float hover(Object key, boolean hovered) {
		float[] state = HOVER.computeIfAbsent(key, k -> new float[] {0f, 0f});
		long now = System.nanoTime() / 1_000_000L;
		float dt = state[1] == 0f ? 0f : Math.min(0.1f, (now - state[1]) / 1000f);
		state[1] = now;
		state[0] = VeloAnim.step(state[0], hovered ? 1f : 0f, dt);
		return state[0];
	}

	public static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	/** A horizontal fade (for the dark scrim behind a menu column): {@code left} at x to {@code right} at x + w. */
	public static void hGradient(DrawContext context, int x, int y, int w, int h, int left, int right) {
		int steps = Math.max(1, Math.min(48, w / 4));
		for (int i = 0; i < steps; i++) {
			int x0 = x + w * i / steps;
			int x1 = x + w * (i + 1) / steps;
			context.fill(x0, y, x1, y + h, VeloAnim.lerpArgb(left, right, (i + 0.5f) / steps));
		}
	}
}
