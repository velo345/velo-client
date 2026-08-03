package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloThemeRow;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.theme.ThemePresets;

/**
 * Theme picker (design spec section 7): click a theme to activate it, the
 * pencil to edit its colors/sliders in {@link ThemeEditScreen} (which
 * activates it first, so edits preview live), "✕" to delete it. Built-in
 * {@link ThemePresets} only ever get the plain activate area - they can't be
 * edited or deleted, so there's nothing for those icons to do.
 */
public final class ThemeEditorScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private VeloScrollRegion scrollRegion;
	private TextFieldWidget nameBox;
	private Text status = Text.literal("");

	public ThemeEditorScreen(Screen parent) {
		super(Text.literal("Themes"), 380, 380);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		Theme active = ThemeManager.active();

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

		scrollRegion.layout(ROW_HEIGHT, 2);
		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void addRow(net.minecraft.client.gui.widget.ClickableWidget widget) {
		addSelectableChild(widget);
		scrollRegion.addRow(widget);
	}

	private void addThemeRow(Theme theme, Theme active, boolean custom) {
		boolean isActive = theme.name().equals(active.name());
		VeloThemeRow row = new VeloThemeRow(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4,
				theme.name(), isActive, custom,
				() -> {
					ThemeManager.setActive(theme);
					layoutContent();
				},
				() -> {
					ThemeManager.setActive(theme);
					this.client.setScreen(new ThemeEditScreen(this));
				},
				() -> deleteTheme(theme.name()));
		addRow(row);
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
		// ThemeManager.deleteCustomTheme already falls back to the default
		// built-in preset if the deleted theme was the active one.
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
		// Sits in the 16px gap reserved above Done in layoutContent() - not
		// at contentBottom()-10, which is inside the Done button itself.
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 30, theme.text());
	}
}
