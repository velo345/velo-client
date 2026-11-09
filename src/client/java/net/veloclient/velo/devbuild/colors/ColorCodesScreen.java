package net.veloclient.velo.devbuild.colors;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Color Codes: pick a colour (saturation/brightness square, hue strip, hex field, the block you look at, recent colours)
 * and copy it in every chat format servers use - hex, &#hex (Essentials), §x codes, MiniMessage, rgb() and the nearest
 * classic &-code. Gradient mode: two colours and a text, copied as MiniMessage or per-letter &#hex.
 */
public final class ColorCodesScreen extends VeloWindow {

	private static final int SQ = 132;
	private static final int STRIP = 12;
	private static final List<Integer> RECENT = new ArrayList<>();
	private static int savedA = 0xFF5A5F;
	private static int savedB = 0x5AA9FF;
	private static boolean savedGradient;
	private static String savedText = "Velo Client";

	private record Hit(int x, int y, int w, int h, Runnable action) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private int editing;           // 0 = colour A, 1 = colour B (gradient end)
	private final float[][] hsb = new float[2][];
	private boolean gradient = savedGradient;
	private TextFieldWidget hexBox;
	private TextFieldWidget textBox;
	private boolean dragSquare;
	private boolean dragHue;
	private String copied;
	private long copiedAt;

	public ColorCodesScreen() {
		super(Text.literal("Color Codes"), 440, 312);
		hsb[0] = toHsb(savedA);
		hsb[1] = toHsb(savedB);
	}

	/** Opens with the colour of the block under the crosshair as colour A. */
	public static ColorCodesScreen pickingBlock(int rgb) {
		savedA = rgb;
		remember(rgb);
		return new ColorCodesScreen();
	}

	private static float[] toHsb(int rgb) {
		return Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
	}

	private int rgb(int which) {
		return Color.HSBtoRGB(hsb[which][0], hsb[which][1], hsb[which][2]) & 0xFFFFFF;
	}

	private static void remember(int rgb) {
		RECENT.remove((Integer) rgb);
		RECENT.add(0, rgb);
		while (RECENT.size() > 10) {
			RECENT.remove(RECENT.size() - 1);
		}
	}

	private int sqX() {
		return contentX();
	}

	private int sqY() {
		return contentY() + 4;
	}

	private int rightX() {
		return contentX() + SQ + STRIP + 22;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		hexBox = new TextFieldWidget(this.textRenderer, sqX() + 40, sqY() + SQ + 14, SQ + STRIP - 34, 16, Text.literal("Hex"));
		hexBox.setMaxLength(7);
		hexBox.setText(hex(rgb(editing)));
		hexBox.setChangedListener(this::typedHex);
		addDrawableChild(hexBox);
		textBox = new TextFieldWidget(this.textRenderer, rightX(), contentBottom() - 18, contentX() + contentWidth() - rightX(), 16, Text.literal("Text"));
		textBox.setMaxLength(64);
		textBox.setText(savedText);
		textBox.setChangedListener(t -> savedText = t);
		addDrawableChild(textBox);
	}

	private void typedHex(String text) {
		String h = text.startsWith("#") ? text.substring(1) : text;
		if (h.length() == 6) {
			try {
				hsb[editing] = toHsb(Integer.parseInt(h, 16));
				changed(false);
			} catch (NumberFormatException ignored) {
			}
		}
	}

	private void changed(boolean updateBox) {
		savedA = rgb(0);
		savedB = rgb(1);
		savedGradient = gradient;
		if (updateBox && hexBox != null) {
			hexBox.setText(hex(rgb(editing)));
		}
	}

	private static String hex(int rgb) {
		return String.format("#%06X", rgb);
	}

	// ---- formats ----

	private static final int[] LEGACY = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55,
			0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private static String nearestLegacy(int rgb) {
		int best = 0;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < LEGACY.length; i++) {
			long dr = (rgb >> 16 & 0xFF) - (LEGACY[i] >> 16 & 0xFF);
			long dg = (rgb >> 8 & 0xFF) - (LEGACY[i] >> 8 & 0xFF);
			long db = (rgb & 0xFF) - (LEGACY[i] & 0xFF);
			long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return "&" + Integer.toHexString(best);
	}

	private static String sectionX(int rgb) {
		StringBuilder b = new StringBuilder("§x");
		for (char c : String.format("%06x", rgb).toCharArray()) {
			b.append('§').append(c);
		}
		return b.toString();
	}

	private static int lerp(int a, int b, float t) {
		int r = Math.round((a >> 16 & 0xFF) * (1 - t) + (b >> 16 & 0xFF) * t);
		int g = Math.round((a >> 8 & 0xFF) * (1 - t) + (b >> 8 & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return r << 16 | g << 8 | bl;
	}

	private String[][] formats() {
		int c = rgb(0);
		if (!gradient) {
			return new String[][] {
					{"Hex", hex(c)},
					{"Essentials / &#", "&" + hex(c)},
					{"MiniMessage", "<" + hex(c).toLowerCase() + ">"},
					{"Section-sign codes", sectionX(c)},
					{"RGB", "rgb(" + (c >> 16 & 0xFF) + ", " + (c >> 8 & 0xFF) + ", " + (c & 0xFF) + ")"},
					{"Nearest &-code", nearestLegacy(c)}};
		}
		String text = textBox == null ? savedText : textBox.getText();
		StringBuilder amp = new StringBuilder();
		int letters = Math.max(1, text.replace(" ", "").length() - 1);
		int k = 0;
		for (char ch : text.toCharArray()) {
			if (ch == ' ') {
				amp.append(' ');
				continue;
			}
			amp.append('&').append(hex(lerp(c, rgb(1), k++ / (float) letters))).append(ch);
		}
		return new String[][] {
				{"MiniMessage", "<gradient:" + hex(c).toLowerCase() + ":" + hex(rgb(1)).toLowerCase() + ">" + text + "</gradient>"},
				{"Essentials / &#", amp.toString()},
				{"Start", hex(c)},
				{"End", hex(rgb(1))}};
	}

	private void copy(String label, String value) {
		MinecraftClient.getInstance().keyboard.setClipboard(value);
		remember(rgb(0));
		if (gradient) {
			remember(rgb(1));
		}
		copied = label;
		copiedAt = System.currentTimeMillis();
	}

	// ---- drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		var font = this.textRenderer;
		int sx = sqX();
		int sy = sqY();
		float[] cur = hsb[editing];
		// saturation / brightness square
		int pure = Color.HSBtoRGB(cur[0], 1f, 1f) & 0xFFFFFF;
		for (int col = 0; col < SQ; col++) {
			context.fill(sx + col, sy, sx + col + 1, sy + SQ, 0xFF000000 | lerp(0xFFFFFF, pure, col / (float) (SQ - 1)));
		}
		context.fillGradient(sx, sy, sx + SQ, sy + SQ, 0x00000000, 0xFF000000);
		VeloDraw.strokeRounded(context, sx - 1, sy - 1, SQ + 2, SQ + 2, 3, MenuKit.HAIRLINE);
		int cx = sx + Math.round(cur[1] * SQ);
		int cy = sy + Math.round((1 - cur[2]) * SQ);
		VeloDraw.fillCircle(context, (float) cx, (float) cy, 5f, 0xFFFFFFFF);
		VeloDraw.fillCircle(context, (float) cx, (float) cy, 3.5f, 0xFF000000 | rgb(editing));
		// hue strip
		int hx = sx + SQ + 8;
		for (int row = 0; row < SQ; row++) {
			context.fill(hx, sy + row, hx + STRIP, sy + row + 1, 0xFF000000 | (Color.HSBtoRGB(row / (float) SQ, 1f, 1f) & 0xFFFFFF));
		}
		int hy = sy + Math.round(cur[0] * SQ);
		VeloDraw.strokeRounded(context, hx - 2, hy - 2, STRIP + 4, 5, 2, 0xFFFFFFFF);
		// swatch + hex
		int swY = sy + SQ + 12;
		VeloDraw.fillRounded(context, sx, swY, 34, 20, 5, 0xFF000000 | rgb(editing));
		VeloDraw.strokeRounded(context, sx, swY, 34, 20, 5, MenuKit.HAIRLINE);
		VeloDraw.fillRounded(context, sx + 38, swY, SQ + STRIP - 30, 20, 5, 0x0FFFFFFF);
		VeloDraw.strokeRounded(context, sx + 38, swY, SQ + STRIP - 30, 20, 5, hexBox != null && hexBox.isFocused() ? MenuKit.accent() : MenuKit.HAIRLINE);
		// buttons under: pick block, gradient toggle, A/B
		int by = swY + 28;
		int bw = (SQ + STRIP + 8 - 6) / 2;
		pill(context, font, sx, by, bw, "Block", "pickaxe", false, mouseX, mouseY, this::pickBlock);
		pill(context, font, sx + bw + 6, by, bw, "Gradient", "theme_editor", gradient, mouseX, mouseY, () -> {
			gradient = !gradient;
			if (!gradient) {
				editing = 0;
			}
			changed(true);
		});
		if (gradient) {
			int ty = by + 26;
			pill(context, font, sx, ty, bw, "Start", "play", editing == 0, mouseX, mouseY, () -> {
				editing = 0;
				changed(true);
			});
			pill(context, font, sx + bw + 6, ty, bw, "End", "flag", editing == 1, mouseX, mouseY, () -> {
				editing = 1;
				changed(true);
			});
		}
		// recent colours
		int ry = contentBottom() - 14;
		MenuKit.text(context, font, MenuKit.ui("Recent"), sx, ry - 11, MenuKit.TEXT_3);
		for (int i = 0; i < RECENT.size(); i++) {
			int c = RECENT.get(i);
			int rx = sx + i * 14;
			VeloDraw.fillRounded(context, rx, ry, 11, 11, 3, 0xFF000000 | c);
			hits.add(new Hit(rx, ry, 11, 11, () -> {
				hsb[editing] = toHsb(c);
				changed(true);
			}));
		}

		// ---- right: preview + formats ----
		int x = rightX();
		int w = contentX() + contentWidth() - x;
		MenuKit.panel(context, x, sy, w, 34, 8);
		String text = textBox == null ? savedText : textBox.getText();
		int letters = Math.max(1, text.replace(" ", "").length() - 1);
		int px = x + 10;
		int k = 0;
		for (char ch : text.toCharArray()) {
			int color = gradient ? lerp(rgb(0), rgb(1), (ch == ' ' ? k : k++) / (float) letters) : rgb(0);
			var t = MenuKit.uiBold(String.valueOf(ch));
			MenuKit.text(context, font, t, px, sy + 13, 0xFF000000 | color);
			px += MenuKit.width(font, t);
			if (px > x + w - 10) {
				break;
			}
		}
		int fy = sy + 44;
		for (String[] f : formats()) {
			boolean over = MenuKit.inside(mouseX, mouseY, x, fy, w, 21);
			float hv = MenuKit.hover("cc-" + f[0], over);
			VeloDraw.fillRounded(context, x, fy, w, 21, 6, VeloAnim.lerpArgb(0x0CFFFFFF, 0x1FFFFFFF, hv));
			MenuKit.text(context, font, MenuKit.ui(f[0]), x + 9, fy + 3, MenuKit.TEXT_3);
			MenuKit.text(context, font, MenuKit.fit(font, MenuKit.mono(f[1].replace('\u00a7', '&')), w - 46), x + 8, fy + 13, MenuKit.TEXT);
			boolean justCopied = f[0].equals(copied) && System.currentTimeMillis() - copiedAt < 1500;
			MenuKit.icon(context, "copy", x + w - 17, fy + 6, 9, justCopied ? MenuKit.GREEN : MenuKit.TEXT_2);
			if (justCopied) {
				var done = MenuKit.ui("Copied");
				MenuKit.text(context, font, done, x + w - 22 - MenuKit.width(font, done), fy + 7, MenuKit.GREEN);
			}
			String value = f[1];
			String label = f[0];
			hits.add(new Hit(x, fy, w, 21, () -> copy(label, value)));
			fy += 23;
		}
		MenuKit.text(context, font, MenuKit.ui(gradient ? "Text for the gradient" : "Preview text"), x, contentBottom() - 31, MenuKit.TEXT_3);
	}

	private void pill(DrawContext context, net.minecraft.client.font.TextRenderer font, int x, int y, int w, String label, String icon,
			boolean on, int mouseX, int mouseY, Runnable action) {
		float hv = MenuKit.hover("cc-pill-" + label, MenuKit.inside(mouseX, mouseY, x, y, w, 20));
		VeloDraw.fillRounded(context, x, y, w, 20, 6, on ? (MenuKit.accent() & 0xFFFFFF) | 0x50000000 : VeloAnim.lerpArgb(0x12FFFFFF, 0x26FFFFFF, hv));
		VeloDraw.strokeRounded(context, x, y, w, 20, 6, on ? MenuKit.accent() : MenuKit.HAIRLINE);
				var t = MenuKit.fit(font, MenuKit.uiBold(label), w - 26);
		int tw = 12 + MenuKit.width(font, t);
		int ix = x + (w - tw) / 2;
		MenuKit.icon(context, icon, ix, y + 6, 8, on ? MenuKit.TEXT : MenuKit.TEXT_2);
		MenuKit.text(context, font, t, ix + 12, y + (20 - MenuKit.H_UI) / 2, MenuKit.TEXT);
		hits.add(new Hit(x, y, w, 20, action));
	}

	private void pickBlock() {
		int c = BlockColor.underCrosshair();
		if (c >= 0) {
			hsb[editing] = toHsb(c);
			remember(c);
			changed(true);
		}
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int mx = (int) click.x();
		int my = (int) click.y();
		if (MenuKit.inside(mx, my, sqX(), sqY(), SQ, SQ)) {
			dragSquare = true;
			square(mx, my);
			return true;
		}
		if (MenuKit.inside(mx, my, sqX() + SQ + 8, sqY(), STRIP, SQ)) {
			dragHue = true;
			hue(my);
			return true;
		}
		for (Hit h : List.copyOf(hits)) {
			if (MenuKit.inside(mx, my, h.x(), h.y(), h.w(), h.h())) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (dragSquare) {
			square((int) click.x(), (int) click.y());
			return true;
		}
		if (dragHue) {
			hue((int) click.y());
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragSquare = false;
		dragHue = false;
		return super.mouseReleased(click);
	}

	private void square(int mx, int my) {
		hsb[editing][1] = Math.clamp((mx - sqX()) / (float) SQ, 0f, 1f);
		hsb[editing][2] = 1f - Math.clamp((my - sqY()) / (float) SQ, 0f, 1f);
		changed(true);
	}

	private void hue(int my) {
		hsb[editing][0] = Math.clamp((my - sqY()) / (float) SQ, 0f, 0.999f);
		changed(true);
	}
}
