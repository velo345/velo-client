package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.Function;

/**
 * Dedicated editor for one custom theme's colors and sliders - split out
 * from the old combined theme list+editor screen (design spec section 7) so
 * picking a theme and editing one are two clearly separate steps instead of
 * one screen doing both. Always edits whatever theme is currently active -
 * {@link ThemeEditorScreen} activates a theme before opening this, so
 * changes here preview live exactly like before.
 */
public final class ThemeEditScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private VeloScrollRegion scrollRegion;

	public ThemeEditScreen(Screen parent) {
		super(Text.literal("Edit Theme: " + ThemeManager.active().name()), 400, 400);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		Theme active = ThemeManager.active();

		int doneY = contentBottom() - 20;
		int y = contentY();
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), Math.max(0, doneY - 8 - y));

		addColorRow("Background", active.background(), c -> withTheme(t -> new Theme(t.name(), c, t.surface(),
				t.accentStart(), t.accentEnd(), t.text(), t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), t.panelOpacity())));
		addColorRow("Surface", active.surface(), c -> withTheme(t -> new Theme(t.name(), t.background(), c,
				t.accentStart(), t.accentEnd(), t.text(), t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), t.panelOpacity())));
		addColorRow("Accent Start", active.accentStart(), c -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(),
				c, t.accentEnd(), t.text(), t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), t.panelOpacity())));
		addColorRow("Accent End", active.accentEnd(), c -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(),
				t.accentStart(), c, t.text(), t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), t.panelOpacity())));
		addColorRow("Text", active.text(), c -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(),
				t.accentStart(), t.accentEnd(), c, t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), t.panelOpacity())));

		addSliderRow("Corner Radius", 0, 16, () -> active.cornerRadius(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), (int) v, t.blurIntensity(), t.animationSpeed(), t.panelOpacity())),
				v -> String.valueOf((int) v));
		addSliderRow("Blur Intensity", 0, 1, () -> active.blurIntensity(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), t.cornerRadius(), (float) v, t.animationSpeed(), t.panelOpacity())),
				v -> Math.round(v * 100) + "%");
		addSliderRow("Animation Speed", 0, 2, () -> active.animationSpeed(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), t.cornerRadius(), t.blurIntensity(), (float) v, t.panelOpacity())),
				v -> String.format("%.1fx", v));
		addSliderRow("Panel Opacity", 0, 1, () -> active.panelOpacity(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), (float) v)),
				v -> Math.round(v * 100) + "%");

		scrollRegion.layout(ROW_HEIGHT, 2);
		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void addRow(ClickableWidget widget) {
		addSelectableChild(widget);
		scrollRegion.addRow(widget);
	}

	private void addColorRow(String label, int currentColor, java.util.function.IntConsumer apply) {
		Text swatch = Text.literal("●").styled(s -> s.withColor(currentColor | 0xFF000000));
		Text buttonText = Text.literal(label + ": ").append(swatch).append(Text.literal("  Edit"));
		VeloButton button = new VeloButton(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4, buttonText,
				b -> this.client.setScreen(new VeloColorPickerScreen(this, label, currentColor, true, argb -> {
					apply.accept(argb);
					layoutContent();
				})));
		addRow(button);
	}

	private void addSliderRow(String label, double min, double max, java.util.function.DoubleSupplier get,
			java.util.function.DoubleConsumer set, java.util.function.DoubleFunction<String> format) {
		VeloSlider slider = new VeloSlider(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 8, label,
				min, max, get, set, null, format);
		addRow(slider);
	}

	private void withTheme(Function<Theme, Theme> mutator) {
		ThemeManager.setActive(mutator.apply(ThemeManager.active()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layout(ROW_HEIGHT, 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, 2);
		}
	}
}
