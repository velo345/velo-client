package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small immediate-mode pieces shared by the pack screens and the texture editor: icon buttons with drawn glyphs,
 * sliders, chips, section labels, tooltips and hover animations.
 */
public final class PackUi {

	private static final Map<Object, Float> HOVER = new HashMap<>();
	private static long lastFrame;
	private static float frameDt = 1 / 60f;

	private PackUi() {
	}

	/** Call once per frame before drawing (advances hover animations). */
	public static void frame() {
		long now = System.nanoTime();
		frameDt = lastFrame == 0 ? 1 / 60f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
	}

	/** Eased 0..1 hover amount of {@code key}. */
	public static float hover(Object key, boolean hovered) {
		float h = HOVER.getOrDefault(key, 0f);
		h = VeloAnim.step(h, hovered ? 1f : 0f, frameDt);
		HOVER.put(key, h);
		return h;
	}

	public static void forget(Object key) {
		HOVER.remove(key);
	}

	/** A square icon button; {@code active} draws it in the accent. Returns its hit area. */
	public static VeloUi.Hit iconButton(DrawContext context, int x, int y, int size, String glyph, boolean active, int mouseX, int mouseY,
			Runnable action) {
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, size, size);
		float h = hover("icon:" + x + ":" + y + ":" + glyph, hovered);
		int fill = active ? VeloStyle.accent() : VeloAnim.lerpArgb(0x0DFFFFFF, 0x24FFFFFF, h);
		VeloDraw.fillRounded(context, x, y, size, size, 5, fill);
		if (!active) {
			VeloDraw.strokeRounded(context, x, y, size, size, 5, VeloAnim.lerpArgb(0x14FFFFFF, 0x30FFFFFF, h));
		}
		int color = active ? VeloStyle.onAccent() : VeloAnim.lerpArgb(0xFFB4B4BE, 0xFFFFFFFF, h);
		glyph(context, glyph, x + size / 2f, y + size / 2f, size * 0.6f, color);
		return new VeloUi.Hit(x, y, size, size, action);
	}

	/** A pill button with a label; tone as {@link VeloUi#pill}, plus a disabled look. */
	public static VeloUi.Hit button(DrawContext context, int x, int y, int w, int h, String label, int tone, boolean enabled, int mouseX,
			int mouseY, Runnable action) {
		if (!enabled) {
			VeloDraw.fillRounded(context, x, y, w, h, Math.min(h / 2, 6), 0x08FFFFFF);
			var renderer = MinecraftClient.getInstance().textRenderer;
			var shown = MenuKit.fit(renderer, MenuKit.ui(label), w - 8);
			MenuKit.text(context, renderer, shown, x + (w - renderer.getWidth(shown)) / 2, y + (h - 8) / 2, 0xFF5E5E68);
			return new VeloUi.Hit(x, y, 0, 0, () -> {
			});
		}
		return VeloUi.pill(context, x, y, w, h, label, tone, mouseX, mouseY, action);
	}

	/** A small rounded badge ("ANIM", "Zip"...). Returns its width. */
	public static int badge(DrawContext context, int x, int y, String label, int color) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		var text = MenuKit.small(label);
		int w = renderer.getWidth(text) + 8;
		VeloDraw.fillRounded(context, x, y, w, 10, 3, VeloUi.withAlpha(color, 0x38));
		MenuKit.text(context, renderer, text, x + 4, y + 2, color);
		return w;
	}

	public static void label(DrawContext context, String text, int x, int y, int color) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		MenuKit.text(context, renderer, MenuKit.ui(text), x, y, color);
	}

	public static void heading(DrawContext context, String text, int x, int y) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		MenuKit.text(context, renderer, MenuKit.small(text.toUpperCase(java.util.Locale.ROOT)), x, y, MenuKit.TEXT_3);
	}

	/** A dark tooltip box with lines, kept on screen. */
	public static void tooltip(DrawContext context, List<String> lines, int mouseX, int mouseY, int screenW, int screenH) {
		if (lines.isEmpty()) {
			return;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		int w = 0;
		for (String line : lines) {
			w = Math.max(w, renderer.getWidth(line));
		}
		w += 12;
		int h = lines.size() * 10 + 8;
		int x = Math.min(mouseX + 10, screenW - w - 4);
		int y = Math.min(mouseY + 12, screenH - h - 4);
		VeloDraw.shadow(context, x, y, w, h, 6, 8, 3, 0x80000000);
		VeloDraw.fillRounded(context, x, y, w, h, 6, 0xF2141419);
		VeloDraw.strokeRounded(context, x, y, w, h, 6, 0x24FFFFFF);
		int ty = y + 5;
		boolean first = true;
		for (String line : lines) {
			context.drawTextWithShadow(renderer, line, x + 6, ty, first ? MenuKit.TEXT : MenuKit.TEXT_2);
			first = false;
			ty += 10;
		}
	}

	// ---- Sliders ----

	/** Slider state while the mouse drags one. */
	public static final class SliderDrag {
		public String id;
		public int x;
		public int w;
		public double min;
		public double max;
		public java.util.function.DoubleConsumer set;

		public void update(double mouseX) {
			double t = Math.max(0, Math.min(1, (mouseX - x) / Math.max(1, w)));
			set.accept(min + (max - min) * t);
		}
	}

	/**
	 * A labelled horizontal slider; pressing it calls {@code onPress} with a ready {@link SliderDrag}.
	 * Returns its hit area (label row + track).
	 */
	public static VeloUi.Hit slider(DrawContext context, String id, int x, int y, int w, String label, String value, double v, double min,
			double max, int mouseX, int mouseY, SliderDrag active, java.util.function.DoubleConsumer set,
			java.util.function.Consumer<SliderDrag> onPress) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		MenuKit.text(context, renderer, MenuKit.ui(label), x, y, MenuKit.TEXT_2);
		var valueText = MenuKit.ui(value);
		MenuKit.text(context, renderer, valueText, x + w - renderer.getWidth(valueText), y, MenuKit.TEXT);
		int trackY = y + 12;
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, trackY - 3, w, 10);
		boolean dragging = active != null && id.equals(active.id);
		float h = hover("slider:" + id, hovered || dragging);
		float t = (float) Math.max(0, Math.min(1, (v - min) / (max - min)));
		VeloDraw.fillRounded(context, x, trackY, w, 4, 2, 0x22FFFFFF);
		VeloDraw.fillRounded(context, x, trackY, Math.max(4, Math.round(w * t)), 4, 2, VeloStyle.accent());
		float knob = 4 + h * 1.5f;
		VeloDraw.fillCircle(context, x + w * t, trackY + 2f, knob, 0xFFFFFFFF);
		return new VeloUi.Hit(x, trackY - 4, w, 12, () -> {
			SliderDrag drag = new SliderDrag();
			drag.id = id;
			drag.x = x;
			drag.w = w;
			drag.min = min;
			drag.max = max;
			drag.set = set;
			drag.update(mouseX);
			onPress.accept(drag);
		});
	}

	// ---- Icons (Lucide, see NOTICE.md - rendered by tools/icons/render_pack_icons.js, tinted at draw time) ----

	/** Draws the named icon ({@code textures/icon/packs/<name>.png}) centred at (cx, cy), {@code s} across, tinted {@code c}. */
	public static void glyph(DrawContext context, String name, float cx, float cy, float s, int c) {
		int size = Math.max(4, Math.round(s));
		int x = Math.round(cx - size / 2f);
		int y = Math.round(cy - size / 2f);
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, Identifier.of("velo-client", "textures/icon/packs/" + name + ".png"),
				x, y, 0f, 0f, size, size, 64, 64, 64, 64, c);
	}
}
