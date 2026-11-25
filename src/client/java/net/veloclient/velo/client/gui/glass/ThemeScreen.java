package net.veloclient.velo.client.gui.glass;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Settings > Theme (2026-11-25): four things that each visibly change every Velo menu right away - the accent colour,
 * how dark the glass is, how strong the blur behind it is, and how round the corners are. See {@link GlassPrefs}.
 */
public final class ThemeScreen extends VeloWindow {

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record Slider(int x, int y, int w, IntConsumer set) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private final List<Slider> sliders = new ArrayList<>();
	private Slider dragging;

	public ThemeScreen(Screen parent) {
		super(Text.literal("Theme"), 380, 372);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		// drawn and hit-tested by hand
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		sliders.clear();
		int x = contentX();
		int y = contentY() + 2;
		int w = contentWidth();

		// Accent: the swatches and your own colour
		section(context, "Accent colour", GlassPrefs.accentName(), x, y);
		y += 16;
		VeloDraw.fillRounded(context, x, y, w, 40, 12, 0x14FFFFFF);
		int accent = GlassPrefs.accent();
		int n = GlassPrefs.SWATCHES.length + 1;
		float step = (w - 24f) / n;
		boolean preset = false;
		for (int i = 0; i < n; i++) {
			int cx = Math.round(x + 12 + step * i + step / 2f);
			int cy = y + 20;
			boolean custom = i == GlassPrefs.SWATCHES.length;
			int color = custom ? accent : GlassPrefs.SWATCHES[i];
			boolean selected = custom ? !preset : color == accent;
			preset |= !custom && selected;
			boolean hover = MenuKit.inside(mouseX, mouseY, cx - 11, cy - 11, 22, 22);
			if (selected || hover) {
				VeloDraw.fillCircle(context, cx, cy, 11f, selected ? 0xFFFFFFFF : 0x66FFFFFF);
				VeloDraw.fillCircle(context, cx, cy, 9.5f, 0xFF1A1A1E);
			}
			if (custom && !selected) {
				VeloDraw.fillCircle(context, cx, cy, 8f, 0x26FFFFFF);
				Text plus = MenuKit.uiBold("+");
				MenuKit.text(context, this.textRenderer, plus, cx - MenuKit.width(this.textRenderer, plus) / 2, cy - 4, 0xFFFFFFFF);
			} else {
				VeloDraw.fillCircle(context, cx, cy, selected || hover ? 7.5f : 8.5f, color | 0xFF000000);
			}
			if (custom) {
				hits.add(new Hit(cx - 11, cy - 11, 22, 22, () -> this.client.setScreen(new net.veloclient.velo.client.gui.VeloColorPickerScreen(
						this, "Accent colour", GlassPrefs.accent(), false, GlassPrefs::setAccent))));
			} else {
				hits.add(new Hit(cx - 11, cy - 11, 22, 22, () -> GlassPrefs.setAccent(color)));
			}
		}
		y += 52;

		y = slider(context, "Glass", "Clear", "Dark", GlassPrefs::glass, GlassPrefs::setGlass, x, y, w, mouseX, mouseY);
		y = slider(context, "Blur", "Off", "Strong", GlassPrefs::blur, GlassPrefs::setBlur, x, y, w, mouseX, mouseY);
		y = slider(context, "Corners", "Square", "Round", GlassPrefs::corners, GlassPrefs::setCorners, x, y, w, mouseX, mouseY);

		// reset
		Text reset = MenuKit.uiBold("Reset to default");
		int rw = MenuKit.width(this.textRenderer, reset) + 28;
		int ry = contentBottom() - 22;
		boolean hover = MenuKit.inside(mouseX, mouseY, x, ry, rw, 22);
		VeloDraw.fillRounded(context, x, ry, rw, 22, 11, hover ? 0x2EFFFFFF : 0x16FFFFFF);
		MenuKit.text(context, this.textRenderer, reset, x + 14, ry + 8, 0xFFFFFFFF);
		hits.add(new Hit(x, ry, rw, 22, GlassPrefs::reset));
	}

	private void section(DrawContext context, String title, String value, int x, int y) {
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(title), x + 2, y, 0xFFFFFFFF);
		if (value != null) {
			Text v = MenuKit.ui(value);
			MenuKit.text(context, this.textRenderer, v, contentX() + contentWidth() - 2 - MenuKit.width(this.textRenderer, v), y, MenuKit.TEXT_3);
		}
	}

	private int slider(DrawContext context, String title, String low, String high, IntSupplier get, IntConsumer set, int x, int y, int w,
			int mouseX, int mouseY) {
		int value = get.getAsInt();
		section(context, title, value + " %", x, y);
		y += 16;
		VeloDraw.fillRounded(context, x, y, w, 40, 12, 0x14FFFFFF);
		int tx = x + 14;
		int tw = w - 28;
		int ty = y + 13;
		VeloDraw.fillRounded(context, tx, ty, tw, 4, 2, 0x24FFFFFF);
		VeloDraw.fillRounded(context, tx, ty, Math.max(4, Math.round(tw * value / 100f)), 4, 2, MenuKit.accent());
		float knobX = tx + tw * value / 100f;
		boolean over = dragging != null && dragging.y() == ty || MenuKit.inside(mouseX, mouseY, tx - 6, ty - 8, tw + 12, 20);
		VeloDraw.fillCircle(context, knobX, ty + 2.6f, over ? 7f : 6f, 0x55000000);
		VeloDraw.fillCircle(context, knobX, ty + 2f, over ? 6.5f : 5.5f, 0xFFFFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.small(low.toUpperCase(java.util.Locale.ROOT)), tx, y + 26, MenuKit.TEXT_3);
		Text hi = MenuKit.small(high.toUpperCase(java.util.Locale.ROOT));
		MenuKit.text(context, this.textRenderer, hi, tx + tw - MenuKit.width(this.textRenderer, hi), y + 26, MenuKit.TEXT_3);
		sliders.add(new Slider(tx, ty, tw, set));
		return y + 50;
	}

	private void slide(Slider s, double mouseX) {
		s.set().accept((int) Math.round(Math.max(0, Math.min(1, (mouseX - s.x()) / s.w())) * 100));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (Slider s : sliders) {
			if (click.x() >= s.x() - 6 && click.x() <= s.x() + s.w() + 6 && click.y() >= s.y() - 9 && click.y() <= s.y() + 13) {
				dragging = s;
				slide(s, click.x());
				return true;
			}
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging != null) {
			slide(dragging, click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			return true;
		}
		return super.mouseReleased(click);
	}
}
