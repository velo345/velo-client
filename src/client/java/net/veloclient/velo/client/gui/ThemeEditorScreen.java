package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.theme.ThemePresets;

import java.util.function.Function;

/**
 * Theme editor (design spec section 7): preset picker plus sliders for
 * corner radius, blur intensity, animation speed, and panel opacity. Mirrors
 * what the launcher's theme editor exposes, since both read the same
 * {@code theme.json}.
 */
public final class ThemeEditorScreen extends VeloWindow {

	private static final int PRESET_WIDTH = 130;
	private static final int PRESET_HEIGHT = 20;
	private static final int PRESET_GAP = 6;

	public ThemeEditorScreen(Screen parent) {
		super(Text.literal("Theme Editor"), 460, 300);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		Theme active = ThemeManager.active();

		int x = contentX();
		int y = contentY();
		int columns = Math.max(1, (contentWidth() + PRESET_GAP) / (PRESET_WIDTH + PRESET_GAP));
		int col = 0;
		for (Theme preset : ThemePresets.all().values()) {
			Theme p = preset;
			VeloButton button = new VeloButton(x, y, PRESET_WIDTH, PRESET_HEIGHT, Text.literal(preset.name()), b -> {
				ThemeManager.setActive(p);
				layoutContent();
			});
			button.selected(preset.name().equals(active.name()));
			addDrawableChild(button);
			col++;
			if (col >= columns) {
				col = 0;
				x = contentX();
				y += PRESET_HEIGHT + PRESET_GAP;
			} else {
				x += PRESET_WIDTH + PRESET_GAP;
			}
		}
		if (col != 0) {
			y += PRESET_HEIGHT + PRESET_GAP;
		}
		y += 12;

		y = addSlider(y, "Corner Radius", 0, 16, () -> ThemeManager.active().cornerRadius(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), (int) v, t.blurIntensity(), t.animationSpeed(), t.panelOpacity())),
				v -> String.valueOf((int) v));
		y = addSlider(y, "Blur Intensity", 0, 1, () -> ThemeManager.active().blurIntensity(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), t.cornerRadius(), (float) v, t.animationSpeed(), t.panelOpacity())),
				v -> Math.round(v * 100) + "%");
		y = addSlider(y, "Animation Speed", 0, 2, () -> ThemeManager.active().animationSpeed(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), t.cornerRadius(), t.blurIntensity(), (float) v, t.panelOpacity())),
				v -> String.format("%.1fx", v));
		y = addSlider(y, "Panel Opacity", 0, 1, () -> ThemeManager.active().panelOpacity(),
				v -> withTheme(t -> new Theme(t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(),
						t.text(), t.cornerRadius(), t.blurIntensity(), t.animationSpeed(), (float) v)),
				v -> Math.round(v * 100) + "%");

		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, 100, 20, Text.literal("Done"), b -> requestClose()));
	}

	private int addSlider(int y, String label, double min, double max, java.util.function.DoubleSupplier get,
			java.util.function.DoubleConsumer onChange, java.util.function.DoubleFunction<String> format) {
		addDrawableChild(new VeloSlider(contentX(), y + 10, contentWidth(), 16, label, min, max,
				get, onChange, null, format));
		return y + 24;
	}

	private void withTheme(Function<Theme, Theme> mutator) {
		ThemeManager.setActive(mutator.apply(ThemeManager.active()));
	}
}
