package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.theme.ThemePresets;

import java.util.function.Function;

/**
 * Theme editor (design spec section 7): pick any built-in or custom theme,
 * create a new custom theme from the current one, delete a custom theme, and
 * - only for custom themes - edit its five colors through a real HSV picker
 * plus the corner radius/blur/animation/opacity sliders. Built-in {@link
 * ThemePresets} can be selected but never edited or deleted; the color rows
 * and sliders below the theme list simply don't appear while one is active,
 * rather than appearing but silently failing to save.
 */
public final class ThemeEditorScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private VeloScrollRegion scrollRegion;
	private TextFieldWidget nameBox;
	private Text status = Text.literal("");

	public ThemeEditorScreen(Screen parent) {
		super(Text.literal("Theme Editor"), 400, 420);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		Theme active = ThemeManager.active();
		boolean editable = !ThemeManager.isBuiltIn(active.name());

		int doneY = contentBottom() - 20;
		int listBottom = doneY - 16;
		int y = contentY();
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), Math.max(0, listBottom - y));

		for (Theme preset : ThemePresets.all().values()) {
			addThemeRow(preset, active, false);
		}
		for (Theme custom : ThemeManager.customThemes()) {
			addThemeRow(custom, active, true);
		}

		nameBox = new TextFieldWidget(this.textRenderer, scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 6, Text.literal("New theme name"));
		nameBox.setPlaceholder(Text.literal("New theme name..."));
		nameBox.setDrawsBackground(true);
		addRow(nameBox);

		VeloButton createButton = new VeloButton(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4,
				Text.literal("Create From Current"), b -> createTheme());
		addRow(createButton);

		if (editable) {
			VeloButton deleteButton = new VeloButton(contentX(), 0, contentWidth(), ROW_HEIGHT - 4,
					Text.literal("Delete \"" + active.name() + "\""), b -> deleteTheme(active.name()));
			addRow(deleteButton);

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
		}

		scrollRegion.layout(ROW_HEIGHT, 2);
		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void addRow(ClickableWidget widget) {
		addSelectableChild(widget);
		scrollRegion.addRow(widget);
	}

	private void addThemeRow(Theme theme, Theme active, boolean custom) {
		boolean isActive = theme.name().equals(active.name());
		VeloButton row = new VeloButton(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4,
				Text.literal(theme.name() + (custom ? "  [custom]" : "") + (isActive ? "  ✓" : "")),
				b -> {
					ThemeManager.setActive(theme);
					layoutContent();
				});
		row.selected(isActive);
		addRow(row);
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

	private void createTheme() {
		String name = nameBox.getText().trim();
		if (name.isEmpty()) {
			status = Text.literal("Type a name first.");
			return;
		}
		boolean created = ThemeManager.createCustomTheme(name, ThemeManager.active());
		if (!created) {
			status = Text.literal("\"" + name + "\" is already taken.");
			return;
		}
		status = Text.literal("Created \"" + name + "\"");
		nameBox.setText("");
		layoutContent();
	}

	private void deleteTheme(String name) {
		ThemeManager.deleteCustomTheme(name);
		status = Text.literal("Deleted \"" + name + "\"");
		layoutContent();
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
		Theme theme = ThemeManager.active();
		Text bottomLine = !status.getString().isEmpty() ? status
				: ThemeManager.isBuiltIn(theme.name())
						? Text.literal("Built-in themes can't be edited - create a custom one to change colors.")
						: Text.literal("");
		// Sits in the 16px gap reserved above Done in layoutContent() - not
		// at contentBottom()-10, which is inside the Done button itself.
		context.drawTextWithShadow(this.textRenderer, bottomLine, contentX(), contentBottom() - 30, theme.text());
	}
}
