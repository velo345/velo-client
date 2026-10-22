package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.namestyle.NameStyle;
import net.veloclient.velo.client.namestyle.NameStyleEffects;
import net.veloclient.velo.client.namestyle.NameStyles;

import java.util.ArrayList;
import java.util.List;

/**
 * Locker > Name: your Velo+ name style. Live previews on the stage (above your head, in chat, in the tab list) and the
 * editor next to it - presets, effect, colours, speed and spread, font, icon, letter style and where it shows. Every
 * change is saved at once to the file the launcher's Locker shares, so your name changes in game (and for other Velo
 * players) while you click. Without Velo+ you can try everything here, but it isn't switched on.
 */
public final class NameStyleScreen extends VeloWindow {

	private static final int CHAT_SLOT = 0;
	private static final int TAB_SLOT = 1;
	private static final int TAG_SLOT = 2;
	private static final int PRESET_SLOT = 10;
	private static final int EFFECT_SLOT = 40;
	private static final int FONT_SLOT = 70;
	private static final int GAP = 6;

	private record Hit(int x, int y, int w, int h, int button, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private NameStyle style;
	private boolean enabled;
	private final List<Hit> hits = new ArrayList<>();
	private double scroll;
	private int contentHeight;
	private int editorTop;
	private int editorBottom;
	/** What's being dragged: 0 nothing, 1 speed, 2 spread, 3 the colour square, 4 the hue bar. */
	private int dragging;
	private int sliderX;
	private int sliderW;

	public NameStyleScreen(Screen parent) {
		super(Text.literal("Locker"), 940, 580);
		returnTo(parent);
		style = NameStyles.own();
		enabled = NameStyles.ownEnabled();
	}

	@Override
	protected String[] headerTabs() {
		return CapeEquipScreen.LOCKER_TABS;
	}

	@Override
	protected int headerTab() {
		return 4;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index != 4) {
			this.client.setScreen(CapeEquipScreen.open(returnScreen(), index));
		}
	}

	@Override
	protected void layoutContent() {
		// Everything here is drawn and hit-tested by hand.
	}

	/** Dev-only (screenshot tour): scroll the editor. */
	public void scrollForTour(double amount) {
		scroll = amount;
	}

	private String playerName() {
		String name = net.veloclient.velo.client.gui.title.MenuInfo.username();
		return name == null || name.isEmpty() ? "Steve" : name;
	}

	private boolean plus() {
		return NameStyles.plus();
	}

	/** Saves the edited style (switched on only for Velo+ members) - the game and the launcher pick it up at once. */
	private void commit(NameStyle changed) {
		style = changed;
		NameStyles.save(style, enabled && plus());
	}

	private int stageX() {
		return contentX() + CapeEquipScreen.SLOTS_W + 12;
	}

	private int editorX() {
		return stageX() + CapeEquipScreen.SHOWCASE_WIDTH + 14;
	}

	private int editorWidth() {
		return contentX() + contentWidth() - editorX();
	}

	// ---- Rendering ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		CapeEquipScreen.drawSlots(context, this.textRenderer, contentX(), contentY(), 4, mouseX, mouseY);
		CapeEquipScreen.drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);
		renderStage(context);
		renderEditor(context, mouseX, mouseY);
	}

	private Text mc(String s) {
		return MenuKit.inNamedFont(s, "mc");
	}

	private void renderStage(DrawContext context) {
		int x = stageX();
		int y = contentY();
		int w = CapeEquipScreen.SHOWCASE_WIDTH;
		int h = contentBottom() - y;
		VeloDraw.fillRounded(context, x, y, w, h, 10, 0xFF111116);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, 10, VeloUi.withAlpha(MenuKit.accent(), 0x14), 0x00111116);
		VeloDraw.strokeRounded(context, x, y, w, h, 10, 0x17FFFFFF);
		String name = playerName();
		MenuKit.text(context, this.textRenderer, MenuKit.small("LIVE PREVIEW"), x + 12, y + 12, MenuKit.TEXT_3);

		// Above your head: a name tag, big.
		int tagY = y + 34;
		Text tag = NameStyles.preview(name, style, Style.EMPTY, TAG_SLOT);
		int tagW = this.textRenderer.getWidth(tag);
		float scale = Math.min(2.4f, (w - 40) / (float) Math.max(1, tagW + 4));
		int boxW = (int) ((tagW + 4) * scale);
		int boxH = (int) (12 * scale);
		int boxX = x + (w - boxW) / 2;
		VeloDraw.fillRounded(context, boxX, tagY, boxW, boxH, 3, 0x66000000);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(boxX + 2 * scale, tagY + 2 * scale);
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(this.textRenderer, tag, 0, 0, 0xFFFFFFFF);
		context.getMatrices().popMatrix();
		MenuKit.text(context, this.textRenderer, MenuKit.small("ABOVE YOUR HEAD"), x + 12, tagY + boxH + 6, MenuKit.TEXT_3);

		// In chat.
		int chatY = tagY + boxH + 24;
		int lineH = 11;
		VeloDraw.fillRounded(context, x + 10, chatY, w - 20, lineH * 4 + 8, 4, 0x80000000);
		Text[] lines = {
				Text.empty().append(mc("<")).append(NameStyles.preview(name, style, Style.EMPTY, CHAT_SLOT)).append(mc("> gg, that was close")),
				Text.empty().append(mc("<Steve> clean")),
				Text.empty().append(mc("<Alex> nice name, ")).append(NameStyles.preview(name, style, Style.EMPTY, CHAT_SLOT + 3)),
				Text.empty().append(NameStyles.preview(name, style, Style.EMPTY, CHAT_SLOT + 4)).append(mc(" joined the game").copy()
						.setStyle(Style.EMPTY.withColor(0xFFFF55)))};
		for (int i = 0; i < lines.length; i++) {
			context.drawTextWithShadow(this.textRenderer, MenuKit.fit(this.textRenderer, lines[i], w - 32), x + 16, chatY + 5 + i * lineH, 0xFFFFFFFF);
		}
		MenuKit.text(context, this.textRenderer, MenuKit.small("CHAT"), x + 12, chatY + lineH * 4 + 14, MenuKit.TEXT_3);

		// In the tab list.
		int tabY = chatY + lineH * 4 + 32;
		VeloDraw.fillRounded(context, x + 10, tabY, w - 20, 14, 2, 0x55000000);
		context.fill(x + 13, tabY + 3, x + 21, tabY + 11, 0xFF6B4A35);
		context.drawTextWithShadow(this.textRenderer, NameStyles.preview(name, style, Style.EMPTY, TAB_SLOT), x + 25, tabY + 3, 0xFFFFFFFF);
		for (int b = 0; b < 4; b++) {
			context.fill(x + w - 26 + b * 3, tabY + 10 - b * 2, x + w - 24 + b * 3, tabY + 11, 0xFF3DDC97);
		}
		MenuKit.text(context, this.textRenderer, MenuKit.small("TAB LIST"), x + 12, tabY + 20, MenuKit.TEXT_3);

		// What the others see.
		int noteY = tabY + 40;
		String note = !plus() ? "Name Styles are part of Velo+. Try anything here - it switches on once you're a member."
				: enabled ? "On. Every Velo player sees your name like this, wherever you picked."
				: "Off. Switch it on at the top right to show it.";
		for (Text line : MenuKit.wrap(this.textRenderer, note, MenuKit::ui, w - 24)) {
			if (noteY + 10 > contentBottom() - 8) {
				break;
			}
			MenuKit.text(context, this.textRenderer, line, x + 12, noteY, MenuKit.TEXT_2);
			noteY += 11;
		}
	}

	private void renderEditor(DrawContext context, int mouseX, int mouseY) {
		int x = editorX();
		int w = editorWidth();
		editorTop = contentY();
		editorBottom = contentBottom();
		int maxScroll = Math.max(0, contentHeight - (editorBottom - editorTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		context.enableScissor(x - 2, editorTop, x + w + 2, editorBottom);
		int y = editorTop - (int) scroll;
		int start = y;

		// On/off + Velo+.
		y = header(context, x, y, w, mouseX, mouseY);
		y = section(context, "PRESETS", x, y);
		y = presets(context, x, y, w, mouseX, mouseY);
		y = section(context, "EFFECT", x, y);
		y = effects(context, x, y, w, mouseX, mouseY);
		y = section(context, "COLOURS", x, y);
		y = colors(context, x, y, w, mouseX, mouseY);
		y = section(context, "MOTION", x, y);
		y = motion(context, x, y, w, mouseX, mouseY);
		y = section(context, "FONT", x, y);
		y = fonts(context, x, y, w, mouseX, mouseY);
		y = section(context, "ICON", x, y);
		y = icons(context, x, y, w, mouseX, mouseY);
		y = section(context, "LETTERS", x, y);
		y = chips(context, x, y, w, mouseX, mouseY, new String[] {"Bold", "Italic", "Underline", "Glow"},
				new boolean[] {style.bold(), style.italic(), style.underline(), style.glow()}, i -> commit(style.withFlags(
						i == 0 ? !style.bold() : style.bold(), i == 1 ? !style.italic() : style.italic(), i == 2 ? !style.underline() : style.underline(),
						i == 3 ? !style.glow() : style.glow(), style.reverse())));
		y = section(context, "SHOW IT IN", x, y);
		y = chips(context, x, y, w, mouseX, mouseY, new String[] {"Chat", "Tab list", "Above your head"},
				new boolean[] {style.chat(), style.tab(), style.nameTag()}, i -> commit(style.withPlaces(
						i == 0 ? !style.chat() : style.chat(), i == 1 ? !style.tab() : style.tab(), i == 2 ? !style.nameTag() : style.nameTag())));
		y += 8;
		button(context, x, y, 120, 20, "Reset to default", false, mouseX, mouseY, () -> commit(NameStyle.defaults()));
		y += 30;
		contentHeight = y - start;
		context.disableScissor();
		if (maxScroll > 0) {
			int trackH = editorBottom - editorTop;
			int thumbH = Math.max(24, trackH * trackH / Math.max(1, contentHeight));
			int thumbY = editorTop + (int) ((trackH - thumbH) * (scroll / maxScroll));
			VeloDraw.fillRounded(context, x + w + 4, thumbY, 3, thumbH, 1, 0x40FFFFFF);
		}
	}

	private int header(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		VeloDraw.fillRounded(context, x, y, w, 40, 8, 0xFF131318);
		VeloDraw.strokeRounded(context, x, y, w, 40, 8, plus() ? 0x17FFFFFF : VeloUi.withAlpha(0xFFFF2D3C, 0x70));
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Name Style"), x + 12, y + 9, MenuKit.TEXT);
		String sub = plus() ? style.effect().label + " · " + style.colors().length + (style.colors().length == 1 ? " colour" : " colours")
				: "Part of Velo+";
		MenuKit.text(context, this.textRenderer, MenuKit.ui(sub), x + 12, y + 22, plus() ? MenuKit.TEXT_2 : MenuKit.RED_TEXT);
		if (plus()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(enabled ? "On" : "Off"), x + w - 46, y + 15, enabled ? MenuKit.TEXT : MenuKit.TEXT_3);
			MenuKit.toggle(context, x + w - 28, y + 16, enabled ? 1f : 0f);
			hit(x + w - 60, y + 8, 56, 24, 0, () -> {
				enabled = !enabled;
				commit(style);
			});
		} else {
			button(context, x + w - 92, y + 10, 82, 20, "Get Velo+", true, mouseX, mouseY, () -> {
				net.veloclient.velo.client.gui.store.StoreScreen.showPlus();
				this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this));
			});
		}
		return y + 48;
	}

	private int section(DrawContext context, String title, int x, int y) {
		MenuKit.text(context, this.textRenderer, MenuKit.small(title), x + 2, y + 6, MenuKit.TEXT_3);
		return y + 18;
	}

	private int presets(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		int columns = 3;
		int tileW = (w - GAP * (columns - 1)) / columns;
		int tileH = 30;
		String name = playerName();
		List<NameStyle.Preset> presets = NameStyle.PRESETS;
		for (int i = 0; i < presets.size(); i++) {
			NameStyle.Preset preset = presets.get(i);
			int tx = x + (i % columns) * (tileW + GAP);
			int ty = y + (i / columns) * (tileH + GAP);
			NameStyle look = preset.style().withPlaces(style.chat(), style.tab(), style.nameTag());
			boolean selected = look.equals(style);
			tile(context, tx, ty, tileW, tileH, selected, mouseX, mouseY);
			Text shown = MenuKit.fit(this.textRenderer, NameStyles.preview(name, preset.style(), Style.EMPTY, PRESET_SLOT + i), tileW - 12);
			context.drawTextWithShadow(this.textRenderer, shown, tx + 7, ty + 6, 0xFFFFFFFF);
			MenuKit.text(context, this.textRenderer, MenuKit.small(preset.name().toUpperCase(java.util.Locale.ROOT)), tx + 7, ty + 19, MenuKit.TEXT_3);
			hit(tx, ty, tileW, tileH, 0, () -> commit(look));
		}
		return y + ((presets.size() + columns - 1) / columns) * (tileH + GAP) + 4;
	}

	private int effects(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		int columns = 3;
		int tileW = (w - GAP * (columns - 1)) / columns;
		int tileH = 22;
		NameStyle.Effect[] all = NameStyle.Effect.values();
		for (int i = 0; i < all.length; i++) {
			NameStyle.Effect effect = all[i];
			int tx = x + (i % columns) * (tileW + GAP);
			int ty = y + (i / columns) * (tileH + GAP);
			tile(context, tx, ty, tileW, tileH, style.effect() == effect, mouseX, mouseY);
			NameStyle look = style.withEffect(effect).withIcon(0).withFlags(true, false, false, style.glow(), style.reverse());
			context.drawTextWithShadow(this.textRenderer, NameStyles.preview(effect.label, look, Style.EMPTY, EFFECT_SLOT + i), tx + 8, ty + 7, 0xFFFFFFFF);
			hit(tx, ty, tileW, tileH, 0, () -> commit(style.withEffect(effect)));
		}
		y += ((all.length + columns - 1) / columns) * (tileH + GAP);
		MenuKit.text(context, this.textRenderer, MenuKit.ui(style.effect().blurb), x + 2, y + 2, MenuKit.TEXT_2);
		return y + 16;
	}

	/** Quick colours under the swatches: one click puts it into the selected colour. */
	private static final int[] PALETTE = {0xFF2D3C, 0xFF7A00, 0xFFC93C, 0xFFF06B, 0x7CFF00, 0x3DDC97, 0x00E5FF, 0x2DD4BF, 0x5AA9FF, 0x3B4CFF,
			0x7B2FF7, 0xB46CFF, 0xFF4FD8, 0xFFB7C5, 0xFFFFFF, 0xA9A9B3, 0x55555F, 0x111111};

	/** The colour being edited (its swatch has a ring, the picker below edits it). */
	private int selectedColor;
	private int svX;
	private int svY;
	private int svW;
	private int svH;
	private int hueY;

	private int colors(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		int[] colors = style.colors();
		selectedColor = Math.max(0, Math.min(colors.length - 1, selectedColor));
		int size = 22;
		int step = size + 8;
		for (int i = 0; i < colors.length; i++) {
			int index = i;
			int cx = x + i * step + size / 2;
			int cy = y + size / 2 + 2;
			boolean hovered = MenuKit.inside(mouseX, mouseY, cx - size / 2, cy - size / 2, size, size);
			boolean selected = i == selectedColor;
			if (selected) {
				VeloDraw.fillCircle(context, cx, cy, size / 2 + 3, 0xFFFFFFFF);
				VeloDraw.fillCircle(context, cx, cy, size / 2 + 1.5f, 0xFF131318);
			} else if (hovered) {
				VeloDraw.fillCircle(context, cx, cy, size / 2 + 2, 0x50FFFFFF);
			}
			VeloDraw.fillCircle(context, cx, cy, size / 2f, 0xFF000000 | colors[i]);
			hit(cx - size / 2, cy - size / 2, size, size, 0, () -> selectedColor = index);
			if (colors.length > 1) {
				hit(cx - size / 2, cy - size / 2, size, size, 1, () -> {
					int[] next = new int[colors.length - 1];
					for (int k = 0, j = 0; k < colors.length; k++) {
						if (k != index) {
							next[j++] = colors[k];
						}
					}
					selectedColor = Math.min(selectedColor, next.length - 1);
					commit(style.withColors(next));
				});
			}
		}
		if (colors.length < NameStyle.MAX_COLORS) {
			int cx = x + colors.length * step + size / 2;
			int cy = y + size / 2 + 2;
			boolean hovered = MenuKit.inside(mouseX, mouseY, cx - size / 2, cy - size / 2, size, size);
			VeloDraw.fillCircle(context, cx, cy, size / 2f, hovered ? 0x30FFFFFF : 0x14FFFFFF);
			VeloDraw.cross(context, cx, cy, 4f, 1.4f, MenuKit.TEXT_2);
			hit(cx - size / 2, cy - size / 2, size, size, 0, () -> {
				int[] next = java.util.Arrays.copyOf(colors, colors.length + 1);
				next[colors.length] = NameStyleEffects.mix(colors[colors.length - 1], 0xFFFFFF, 0.5);
				selectedColor = colors.length;
				commit(style.withColors(next));
			});
		}
		// The hex code of the selected colour, click to open the full picker (alpha, typing a hex).
		Text hex = MenuKit.mono(String.format(java.util.Locale.ROOT, "#%06X", colors[selectedColor] & 0xFFFFFF));
		int hexW = this.textRenderer.getWidth(hex) + 16;
		int hexX = x + w - hexW;
		boolean hexHover = MenuKit.inside(mouseX, mouseY, hexX, y + 3, hexW, 18);
		VeloDraw.fillRounded(context, hexX, y + 3, hexW, 18, 6, hexHover ? 0x1AFFFFFF : 0x0AFFFFFF);
		MenuKit.text(context, this.textRenderer, hex, hexX + 8, y + 9, MenuKit.TEXT_2);
		int editing = selectedColor;
		hit(hexX, y + 3, hexW, 18, 0, () -> this.client.setScreen(new VeloColorPickerScreen(this, "Colour " + (editing + 1),
				0xFF000000 | style.colors()[editing], false, argb -> setColor(editing, argb & 0xFFFFFF))));
		y += size + 12;

		// Inline picker: saturation/brightness square and a hue bar for the selected colour.
		float[] hsv = toHsv(colors[selectedColor]);
		svX = x;
		svY = y;
		svW = w;
		svH = 64;
		int pure = 0xFF000000 | NameStyleEffects.hsv(hsv[0], 1, 1);
		MenuKit.hGradient(context, svX, svY, svW, svH, 0xFFFFFFFF, pure);
		VeloDraw.fillGradient(context, svX, svY, svW, svH, 0x00000000, 0xFF000000);
		VeloDraw.strokeRounded(context, svX, svY, svW, svH, 4, 0x30FFFFFF);
		float kx = svX + hsv[1] * svW;
		float ky = svY + (1 - hsv[2]) * svH;
		VeloDraw.fillCircle(context, kx, ky, 5, 0xFFFFFFFF);
		VeloDraw.fillCircle(context, kx, ky, 3.5f, 0xFF000000 | colors[selectedColor]);
		hit(svX, svY, svW, svH, 0, () -> dragging = 3);
		hueY = svY + svH + 8;
		int[] stops = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
		for (int i = 0; i < 6; i++) {
			int x0 = x + w * i / 6;
			int x1 = x + w * (i + 1) / 6;
			MenuKit.hGradient(context, x0, hueY, x1 - x0, 8, stops[i], stops[i + 1]);
		}
		float hx = x + hsv[0] * w;
		VeloDraw.fillRounded(context, hx - 2, hueY - 2, 4, 12, 2, 0xFFFFFFFF);
		hit(x, hueY - 3, w, 14, 0, () -> dragging = 4);
		y = hueY + 16;

		// Quick colours.
		int dot = 14;
		int columns = Math.max(1, (w + 6) / (dot + 6));
		for (int i = 0; i < PALETTE.length; i++) {
			int color = PALETTE[i];
			int cx = x + (i % columns) * (dot + 6) + dot / 2;
			int cy = y + (i / columns) * (dot + 6) + dot / 2;
			boolean hovered = MenuKit.inside(mouseX, mouseY, cx - dot / 2, cy - dot / 2, dot, dot);
			if (hovered) {
				VeloDraw.fillCircle(context, cx, cy, dot / 2f + 2, 0x60FFFFFF);
			}
			VeloDraw.fillCircle(context, cx, cy, dot / 2f, 0xFF000000 | color);
			hit(cx - dot / 2, cy - dot / 2, dot, dot, 0, () -> setColor(selectedColor, color));
		}
		y += ((PALETTE.length + columns - 1) / columns) * (dot + 6) + 4;

		// The whole range as a strip, so you see how the colours blend.
		for (int px = 0; px < w; px += 2) {
			context.fill(x + px, y, x + Math.min(w, px + 2), y + 6, 0xFF000000 | NameStyleEffects.gradient(colors, px / (double) w));
		}
		String hint = style.effect().usesColors() ? "Pick a colour, drag in the square. Right-click a colour to remove it." : style.effect().label
				+ " makes its own colours - these are used by the other effects.";
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(hint), w), x + 2, y + 12, MenuKit.TEXT_3);
		return y + 28;
	}

	private void setColor(int index, int rgb) {
		int[] next = style.colors().clone();
		if (index < next.length && next[index] != rgb) {
			next[index] = rgb;
			commit(style.withColors(next));
		}
	}

	/** Drag in the square (saturation/brightness) or on the hue bar. */
	private void applyPicker(double mouseX, double mouseY) {
		float[] hsv = toHsv(style.colors()[selectedColor]);
		if (dragging == 3) {
			hsv[1] = (float) Math.max(0, Math.min(1, (mouseX - svX) / (double) svW));
			hsv[2] = (float) Math.max(0, Math.min(1, 1 - (mouseY - svY) / (double) svH));
		} else {
			hsv[0] = (float) Math.max(0, Math.min(0.999, (mouseX - svX) / (double) svW));
			if (hsv[1] < 0.05f) {
				hsv[1] = 1;
			}
			if (hsv[2] < 0.05f) {
				hsv[2] = 1;
			}
		}
		setColor(selectedColor, NameStyleEffects.hsv(hsv[0], hsv[1], hsv[2]));
	}

	private static float[] toHsv(int rgb) {
		float r = (rgb >> 16 & 0xFF) / 255f;
		float g = (rgb >> 8 & 0xFF) / 255f;
		float b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h = 0;
		if (d > 0) {
			h = max == r ? ((g - b) / d) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
			h /= 6;
			if (h < 0) {
				h += 1;
			}
		}
		return new float[] {h, max == 0 ? 0 : d / max, max};
	}

	private int motion(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		boolean animated = style.effect().animated();
		y = slider(context, x, y, w, "Speed", (style.speed() - NameStyle.MIN_SPEED) / (double) (NameStyle.MAX_SPEED - NameStyle.MIN_SPEED),
				String.format(java.util.Locale.ROOT, "%.1fx", style.speed() / 10.0), animated, 1);
		y = slider(context, x, y, w, "Spread", (style.spread() - 1) / 8.0, style.spread() == 5 ? "Normal" : style.spread() < 5 ? "Tight" : "Wide",
				style.effect().usesSpread(), 2);
		return chips(context, x, y, w, mouseX, mouseY, new String[] {"Reverse direction"}, new boolean[] {style.reverse()},
				i -> commit(style.withFlags(style.bold(), style.italic(), style.underline(), style.glow(), !style.reverse())));
	}

	private int slider(DrawContext context, int x, int y, int w, String label, double value, String shown, boolean active, int id) {
		int color = active ? MenuKit.TEXT : MenuKit.TEXT_3;
		MenuKit.text(context, this.textRenderer, MenuKit.ui(label), x + 2, y + 4, color);
		MenuKit.text(context, this.textRenderer, MenuKit.ui(shown), x + w - this.textRenderer.getWidth(MenuKit.ui(shown)) - 2, y + 4, MenuKit.TEXT_2);
		int trackX = x + 2;
		int trackW = w - 4;
		int trackY = y + 18;
		VeloDraw.fillRounded(context, trackX, trackY, trackW, 4, 2, 0xFF2A2A31);
		int fill = (int) (trackW * Math.max(0, Math.min(1, value)));
		VeloDraw.fillRounded(context, trackX, trackY, Math.max(4, fill), 4, 2, active ? MenuKit.accent() : 0xFF4A4A52);
		VeloDraw.fillCircle(context, trackX + fill, trackY + 2, 5, active ? 0xFFFFFFFF : 0xFF8A8A92);
		if (active) {
			hit(trackX - 4, trackY - 6, trackW + 8, 16, 0, () -> {
				dragging = id;
				sliderX = trackX;
				sliderW = trackW;
			});
		}
		return y + 32;
	}

	private void applySlider(double mouseX) {
		double t = Math.max(0, Math.min(1, (mouseX - sliderX) / Math.max(1, sliderW)));
		if (dragging == 1) {
			int speed = NameStyle.MIN_SPEED + (int) Math.round(t * (NameStyle.MAX_SPEED - NameStyle.MIN_SPEED));
			if (speed != style.speed()) {
				commit(style.withSpeed(speed));
			}
		} else if (dragging == 2) {
			int spread = 1 + (int) Math.round(t * 8);
			if (spread != style.spread()) {
				commit(style.withSpread(spread));
			}
		}
	}

	private int fonts(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		int columns = 3;
		int tileW = (w - GAP * (columns - 1)) / columns;
		int tileH = 30;
		String name = playerName();
		for (int i = 0; i < NameStyle.FONTS.length; i++) {
			int font = i;
			int tx = x + (i % columns) * (tileW + GAP);
			int ty = y + (i / columns) * (tileH + GAP);
			tile(context, tx, ty, tileW, tileH, style.font() == i, mouseX, mouseY);
			Text shown = MenuKit.fit(this.textRenderer, NameStyles.preview(name, style.withFont(i).withIcon(0), Style.EMPTY, FONT_SLOT + i), tileW - 12);
			context.drawTextWithShadow(this.textRenderer, shown, tx + 7, ty + 6, 0xFFFFFFFF);
			MenuKit.text(context, this.textRenderer, MenuKit.small(NameStyle.FONTS[i][0].toUpperCase(java.util.Locale.ROOT)), tx + 7, ty + 19, MenuKit.TEXT_3);
			hit(tx, ty, tileW, tileH, 0, () -> commit(style.withFont(font)));
		}
		return y + ((NameStyle.FONTS.length + columns - 1) / columns) * (tileH + GAP) + 4;
	}

	private int icons(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		int size = 22;
		int columns = Math.max(1, (w + GAP) / (size + GAP));
		for (int i = 0; i < NameStyle.ICONS.length; i++) {
			int icon = i;
			int ix = x + (i % columns) * (size + GAP);
			int iy = y + (i / columns) * (size + GAP);
			tile(context, ix, iy, size, size, style.icon() == i, mouseX, mouseY);
			Text glyph = i == 0 ? MenuKit.ui("-") : Text.literal(NameStyle.ICONS[i]).setStyle(Style.EMPTY.withColor(style.colors()[0]));
			int gw = this.textRenderer.getWidth(glyph);
			context.drawTextWithShadow(this.textRenderer, glyph, ix + (size - gw) / 2, iy + 7, i == 0 ? MenuKit.TEXT_3 : 0xFFFFFFFF);
			hit(ix, iy, size, size, 0, () -> commit(style.withIcon(icon)));
		}
		return y + ((NameStyle.ICONS.length + columns - 1) / columns) * (size + GAP) + 4;
	}

	private int chips(DrawContext context, int x, int y, int w, int mouseX, int mouseY, String[] labels, boolean[] on, java.util.function.IntConsumer toggle) {
		int cx = x;
		int cy = y;
		for (int i = 0; i < labels.length; i++) {
			int index = i;
			Text label = MenuKit.uiBold(labels[i]);
			int cw = this.textRenderer.getWidth(label) + 30;
			if (cx + cw > x + w) {
				cx = x;
				cy += 26;
			}
			boolean hovered = MenuKit.inside(mouseX, mouseY, cx, cy, cw, 20);
			VeloDraw.fillRounded(context, cx, cy, cw, 20, 10, on[i] ? VeloUi.withAlpha(MenuKit.accent(), 0x30) : hovered ? 0x1AFFFFFF : 0x0AFFFFFF);
			VeloDraw.strokeRounded(context, cx, cy, cw, 20, 10, on[i] ? VeloUi.withAlpha(MenuKit.accent(), 0xA0) : 0x17FFFFFF);
			VeloDraw.fillCircle(context, cx + 11f, cy + 10f, 3f, on[i] ? MenuKit.accent() : 0x40FFFFFF);
			MenuKit.text(context, this.textRenderer, label, cx + 20, cy + 6, on[i] ? MenuKit.TEXT : MenuKit.TEXT_2);
			hit(cx, cy, cw, 20, 0, () -> toggle.accept(index));
			cx += cw + GAP;
		}
		return cy + 28;
	}

	private void tile(DrawContext context, int x, int y, int w, int h, boolean selected, int mouseX, int mouseY) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, w, h) && mouseY >= editorTop && mouseY < editorBottom;
		VeloDraw.fillRounded(context, x, y, w, h, 7, selected ? VeloUi.withAlpha(MenuKit.accent(), 0x1F) : hovered ? 0xFF1A1A21 : 0xFF131318);
		VeloDraw.strokeRounded(context, x, y, w, h, 7, selected ? VeloUi.withAlpha(MenuKit.accent(), 0xB0) : hovered ? 0x30FFFFFF : 0x17FFFFFF);
	}

	private void button(DrawContext context, int x, int y, int w, int h, String label, boolean primary, int mouseX, int mouseY, Runnable action) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, w, h);
		VeloDraw.fillRounded(context, x, y, w, h, 7, primary ? (hovered ? VeloUi.withAlpha(MenuKit.accent(), 0xFF) : MenuKit.accent())
				: hovered ? 0x1AFFFFFF : 0x0AFFFFFF);
		if (!primary) {
			VeloDraw.strokeRounded(context, x, y, w, h, 7, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		}
		Text text = MenuKit.uiBold(label);
		MenuKit.text(context, this.textRenderer, text, x + (w - this.textRenderer.getWidth(text)) / 2, y + (h - 8) / 2,
				primary ? MenuKit.onAccent() : MenuKit.TEXT);
		hit(x, y, w, h, 0, action);
	}

	/** A click target - only where the editor is actually visible (it scrolls). */
	private void hit(int x, int y, int w, int h, int button, Runnable action) {
		int top = Math.max(y, editorTop);
		int bottom = Math.min(y + h, editorBottom);
		if (bottom > top) {
			hits.add(new Hit(x, top, w, bottom - top, button, action));
		}
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (CapeEquipScreen.loadoutClick(click.x(), click.y(), click.button(), contentX(), contentY(), contentBottom())) {
			return true;
		}
		int slot = CapeEquipScreen.slotAt((int) click.x(), (int) click.y(), contentX(), contentY());
		if (slot >= 0) {
			selectHeaderTab(slot);
			return true;
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.button() == click.button() && hit.contains(click.x(), click.y())) {
				hit.action().run();
				if (dragging >= 3) {
					applyPicker(click.x(), click.y());
				} else if (dragging != 0) {
					applySlider(click.x());
				}
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging >= 3) {
			applyPicker(click.x(), click.y());
			return true;
		}
		if (dragging != 0) {
			applySlider(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragging = 0;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX >= editorX() - 4) {
			scroll -= verticalAmount * 24;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
}
