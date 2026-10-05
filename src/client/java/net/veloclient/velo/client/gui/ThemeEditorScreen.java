package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.theme.ThemePresets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Themes, all on one screen: the theme list on the left (click to use it), and on the right a live
 * preview plus every setting of the active theme - colors as real swatches with their hex code,
 * and sliders. Everything applies instantly to the whole Velo UI, this window included. Changing a
 * built-in theme quietly makes an editable copy first, so presets are never lost.
 */
public final class ThemeEditorScreen extends VeloWindow {

	private static final int LIST_WIDTH = 190;
	private static final int ROW = 26;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget nameBox;
	private String status = "";
	private float listScroll;

	public ThemeEditorScreen(Screen parent) {
		super(Text.literal("Themes"), 660, 440);
		returnTo(parent);
	}

	private int rightX() {
		return contentX() + LIST_WIDTH + 16;
	}

	private int rightWidth() {
		return contentX() + contentWidth() - rightX();
	}

	private static final int PREVIEW_H = 86;

	private int colorsY() {
		return contentY() + 24 + PREVIEW_H + 8;
	}

	private int slidersY() {
		return colorsY() + 3 * 21 + 6;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		Theme active = ThemeManager.active();
		boolean custom = !ThemeManager.isBuiltIn(active.name());
		nameBox = null;
		if (custom) {
			nameBox = new TextFieldWidget(this.textRenderer, rightX(), contentY(), Math.min(220, rightWidth() - 110), 16, Text.literal("Theme name"));
			nameBox.setMaxLength(32);
			nameBox.setText(active.name());
			addDrawableChild(nameBox);
		}
		int sx = rightX();
		int sw = (rightWidth() - 10) / 2;
		int sy = slidersY();
		addDrawableChild(new VeloSlider(sx, sy, sw, 16, "Corner radius", 0, 16, () -> ThemeManager.active().cornerRadius(),
				v -> edit(t -> copy(t, null, null, null, null, null, (int) Math.round(v), null, null, null)), Math::rint,
				v -> (int) Math.round(v) + " px"));
		addDrawableChild(new VeloSlider(sx + sw + 10, sy, sw, 16, "Panel opacity", 0.3, 1, () -> ThemeManager.active().panelOpacity(),
				v -> edit(t -> copy(t, null, null, null, null, null, null, null, null, (float) v)), null, v -> Math.round(v * 100) + "%"));
		addDrawableChild(new VeloSlider(sx, sy + 26, sw, 16, "Background blur", 0, 1, () -> ThemeManager.active().blurIntensity(),
				v -> edit(t -> copy(t, null, null, null, null, null, null, (float) v, null, null)), null, v -> Math.round(v * 100) + "%"));
		addDrawableChild(new VeloSlider(sx + sw + 10, sy + 26, sw, 16, "Animation speed", 0, 2, () -> ThemeManager.active().animationSpeed(),
				v -> edit(t -> copy(t, null, null, null, null, null, null, null, (float) v, null)), null,
				v -> String.format(Locale.ROOT, "%.1fx", v)));
	}

	// ---- Editing ----

	private static Theme copy(Theme t, Integer background, Integer surface, Integer accentStart, Integer accentEnd, Integer text,
			Integer radius, Float blur, Float speed, Float opacity) {
		return new Theme(t.name(), background != null ? background : t.background(), surface != null ? surface : t.surface(),
				accentStart != null ? accentStart : t.accentStart(), accentEnd != null ? accentEnd : t.accentEnd(),
				text != null ? text : t.text(), radius != null ? radius : t.cornerRadius(), blur != null ? blur : t.blurIntensity(),
				speed != null ? speed : t.animationSpeed(), opacity != null ? opacity : t.panelOpacity());
	}

	/** Applies a change to the active theme - duplicating a built-in one first. */
	private void edit(UnaryOperator<Theme> change) {
		Theme active = ThemeManager.active();
		if (ThemeManager.isBuiltIn(active.name())) {
			String name = freeName(active.name() + " (mine)");
			ThemeManager.createCustomTheme(name, active);
			status = "Made an editable copy: " + name;
			layoutContent();
		}
		ThemeManager.setActive(change.apply(ThemeManager.active()));
	}

	private static String freeName(String base) {
		java.util.Set<String> taken = new java.util.HashSet<>(ThemePresets.all().keySet());
		ThemeManager.customThemes().forEach(t -> taken.add(t.name()));
		String name = base;
		for (int n = 2; taken.contains(name); n++) {
			name = base + " " + n;
		}
		return name;
	}

	private void commitName() {
		if (nameBox == null) {
			return;
		}
		String old = ThemeManager.active().name();
		String wanted = nameBox.getText().trim();
		if (!wanted.equals(old)) {
			if (ThemeManager.renameCustomTheme(old, wanted)) {
				status = "Renamed to " + wanted;
			} else {
				status = wanted.isEmpty() ? "A theme needs a name" : "\"" + wanted + "\" is already taken";
				nameBox.setText(old);
			}
		}
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		Theme active = ThemeManager.active();
		drawList(context, active, mouseX, mouseY);

		int x = rightX();
		int y = contentY();
		int width = rightWidth();
		boolean custom = !ThemeManager.isBuiltIn(active.name());
		if (!custom) {
			context.drawTextWithShadow(this.textRenderer, active.name(), x, y + 4, VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, "Built-in - change anything to get your own copy",
					x + this.textRenderer.getWidth(active.name()) + 8, y + 4, VeloStyle.textFaint());
		} else {
			hits.add(VeloUi.pill(context, x + width - 96, y, 96, 16, "Delete theme", 3, mouseX, mouseY, () -> {
				String name = ThemeManager.active().name();
				this.client.setScreen(new VeloConfirmScreen(this, "Delete \"" + name + "\"?", "This theme is gone for good.", "Delete", () -> {
					ThemeManager.deleteCustomTheme(name);
					layoutContent();
				}));
			}));
		}

		drawPreview(context, x, y + 24, width, PREVIEW_H, active);

		// Colors: swatch + name + readable hex, two columns.
		int cy = colorsY();
		int colW = (width - 10) / 2;
		colorRow(context, x, cy, colW, "Background", active.background(), mouseX, mouseY,
				c -> t -> copy(t, c, null, null, null, null, null, null, null, null));
		colorRow(context, x + colW + 10, cy, colW, "Panels", active.surface(), mouseX, mouseY,
				c -> t -> copy(t, null, c, null, null, null, null, null, null, null));
		colorRow(context, x, cy + 21, colW, "Accent", active.accentStart(), mouseX, mouseY,
				c -> t -> copy(t, null, null, c, null, null, null, null, null, null));
		colorRow(context, x + colW + 10, cy + 21, colW, "Accent fade", active.accentEnd(), mouseX, mouseY,
				c -> t -> copy(t, null, null, null, c, null, null, null, null, null));
		colorRow(context, x, cy + 42, colW, "Font color", active.text(), mouseX, mouseY,
				c -> t -> copy(t, null, null, null, null, c, null, null, null, null));

		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, width), x, contentBottom() - 10, VeloStyle.textMuted());
		}
	}

	private void colorRow(DrawContext context, int x, int y, int width, String label, int color, int mouseX, int mouseY,
			java.util.function.IntFunction<UnaryOperator<Theme>> change) {
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, 18);
		VeloDraw.fillRounded(context, x, y, width, 18, 6, hovered ? VeloStyle.cardHover() : VeloStyle.card());
		// Swatch over a checker so transparency shows.
		context.fill(x + 4, y + 3, x + 30, y + 15, 0xFF8A8A8A);
		context.fill(x + 4, y + 3, x + 17, y + 9, 0xFFCFCFCF);
		context.fill(x + 17, y + 9, x + 30, y + 15, 0xFFCFCFCF);
		context.fill(x + 4, y + 3, x + 30, y + 15, color);
		VeloDraw.strokeRect(context, x + 4, y + 3, 26, 12, 0x60FFFFFF);
		String hex = String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(label, width - 48 - this.textRenderer.getWidth(hex)), x + 36, y + 5, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, hex, x + width - 6 - this.textRenderer.getWidth(hex), y + 5, VeloStyle.textMuted());
		hits.add(new VeloUi.Hit(x, y, width, 18, () ->
				this.client.setScreen(new VeloColorPickerScreen(this, label, color, true, argb -> edit(change.apply(argb))))));
	}

	/** A small mock window drawn with the active theme: header, module cards, a toggle, a button. */
	private void drawPreview(DrawContext context, int x, int y, int width, int height, Theme theme) {
		int radius = Math.min(theme.cornerRadius() + 2, 14);
		VeloDraw.fillRounded(context, x, y, width, height, radius, theme.background() | 0xFF000000);
		VeloDraw.fillRoundedTop(context, x, y, width, 22, radius, VeloAnim.lerpArgb(theme.surfaceWithOpacity() | 0xFF000000, theme.accentStart(), 0.18f));
		VeloDraw.fillRounded(context, x + 10, y + 7, 3, 9, 1, theme.accentStart() | 0xFF000000);
		context.drawTextWithShadow(this.textRenderer, "Preview", x + 18, y + 7, theme.text());
		int cardW = (width - 30) / 2;
		for (int i = 0; i < 2; i++) {
			int cx = x + 10 + i * (cardW + 10);
			int cy = y + 28;
			VeloDraw.fillRounded(context, cx, cy, cardW, 30, Math.min(theme.cornerRadius(), 10), theme.surfaceWithOpacity());
			context.drawTextWithShadow(this.textRenderer, i == 0 ? "FPS Counter" : "Zoom", cx + 8, cy + 5, theme.text());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(i == 0 ? "Shows your frames" : "Press C to zoom", cardW - 44), cx + 8, cy + 17,
					VeloAnim.lerpArgb(theme.text(), theme.background(), 0.45f));
			int tx = cx + cardW - 30;
			boolean on = i == 0;
			VeloDraw.fillRounded(context, tx, cy + 9, 22, 12, 6, on ? theme.accentStart() | 0xFF000000 : 0xFF3A3A40);
			VeloDraw.fillCircle(context, on ? tx + 16 : tx + 6, cy + 15, 4, 0xFFFFFFFF);
		}
		int by = y + height - 24;
		VeloDraw.fillRoundedGradient(context, x + 10, by, 110, 18, Math.min(theme.cornerRadius(), 9),
				theme.accentStart() | 0xFF000000, theme.accentEnd() | 0xFF000000);
		String label = "Button";
		context.drawTextWithShadow(this.textRenderer, label, x + 10 + (110 - this.textRenderer.getWidth(label)) / 2, by + 5, 0xFFFFFFFF);
		VeloDraw.fillRounded(context, x + 130, by, width - 140, 18, Math.min(theme.cornerRadius(), 9), 0x30000000);
		context.drawTextWithShadow(this.textRenderer, "Search modules...", x + 138, by + 5, VeloAnim.lerpArgb(theme.text(), theme.background(), 0.5f));
	}

	private void drawList(DrawContext context, Theme active, int mouseX, int mouseY) {
		int x = contentX();
		int top = contentY();
		int bottom = contentBottom() - 24;
		VeloDraw.fillRounded(context, x, top, LIST_WIDTH, contentBottom() - top, 8, VeloStyle.sunken());
		List<Object> rows = new ArrayList<>();
		rows.add("Built-in");
		rows.addAll(ThemePresets.all().values());
		rows.add("Your themes");
		rows.addAll(ThemeManager.customThemes());
		int total = 0;
		for (Object row : rows) {
			total += row instanceof String ? 16 : ROW + 2;
		}
		float max = Math.max(0, total - (bottom - top - 8));
		listScroll = Math.clamp(listScroll, 0, max);
		context.enableScissor(x, top + 4, x + LIST_WIDTH, bottom);
		int y = top + 6 - Math.round(listScroll);
		for (Object row : rows) {
			if (row instanceof String header) {
				context.drawTextWithShadow(this.textRenderer, header.toUpperCase(Locale.ROOT), x + 10, y + 4, VeloStyle.textFaint());
				y += 16;
				continue;
			}
			Theme theme = (Theme) row;
			boolean selected = theme.name().equals(active.name());
			boolean hovered = VeloUi.inside(mouseX, mouseY, x + 4, y, LIST_WIDTH - 8, ROW) && mouseY < bottom;
			VeloDraw.fillRounded(context, x + 4, y, LIST_WIDTH - 8, ROW, 7,
					selected ? VeloStyle.accentSoft(0.22f) : hovered ? VeloStyle.cardHover() : VeloStyle.card());
			if (selected) {
				VeloDraw.strokeRounded(context, x + 4, y, LIST_WIDTH - 8, ROW, 7, VeloStyle.accent());
			}
			// Mini palette: background, panel, accent fade, text.
			int px = x + 10;
			VeloDraw.fillRounded(context, px, y + 7, 30, 12, 4, theme.background() | 0xFF000000);
			context.fill(px + 8, y + 9, px + 16, y + 17, theme.surface() | 0xFF000000);
			VeloDraw.fillRoundedGradient(context, px + 18, y + 9, 10, 8, 2, theme.accentStart() | 0xFF000000, theme.accentEnd() | 0xFF000000);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(theme.name(), LIST_WIDTH - 56), px + 38, y + 9, VeloStyle.text());
			if (mouseY < bottom) {
				hits.add(new VeloUi.Hit(x + 4, Math.max(y, top + 4), LIST_WIDTH - 8, ROW, () -> {
					commitName();
					ThemeManager.setActive(theme);
					status = "";
					layoutContent();
				}));
			}
			y += ROW + 2;
		}
		context.disableScissor();
		hits.add(VeloUi.pill(context, x + 6, contentBottom() - 20, LIST_WIDTH - 12, 16, "+  New theme", 1, mouseX, mouseY, () -> {
			commitName();
			String name = freeName("My Theme");
			ThemeManager.createCustomTheme(name, ThemeManager.active());
			status = "Created " + name + " - edit it on the right";
			layoutContent();
		}));
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		boolean result = super.mouseClicked(click, doubled);
		if (nameBox != null && !nameBox.isFocused()) {
			commitName();
		}
		return result;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (nameBox != null && nameBox.isFocused() && (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
			commitName();
			nameBox.setFocused(false);
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX < contentX() + LIST_WIDTH) {
			listScroll -= (float) verticalAmount * 20;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void requestClose() {
		commitName();
		super.requestClose();
	}
}
