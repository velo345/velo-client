package net.veloclient.velo.client.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloNavButton;
import net.veloclient.velo.client.gui.widget.VeloNavIcons;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * Catch-all "Settings" tab (gear icon) grouping actions that don't belong to
 * any particular module - previously two of their own standalone sidebar
 * rows in {@link ModMenuScreen} (Theme Editor, Open Mods Folder), folded in
 * here to cut down on side-nav clutter.
 */
public final class SettingsTabScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private Text status = Text.literal("");

	public SettingsTabScreen(Screen parent) {
		super(Text.literal("Settings"), 320, 200);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int y = contentY();

		VeloNavButton themeButton = new VeloNavButton(contentX(), y, contentWidth(), ROW_HEIGHT,
				VeloNavIcons.of("theme_editor"), Text.literal("Theme Editor"),
				b -> this.client.setScreen(new ThemeEditorScreen(this)));
		addDrawableChild(themeButton);
		y += ROW_HEIGHT + 4;

		VeloNavButton modsFolderButton = new VeloNavButton(contentX(), y, contentWidth(), ROW_HEIGHT,
				VeloNavIcons.of("mods_folder"), Text.literal("Open Mods Folder"),
				b -> FileManagerOpener.open(FabricLoader.getInstance().getGameDir().resolve("mods").toFile(),
						s -> status = Text.literal(s)));
		addDrawableChild(modsFolderButton);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Theme theme = ThemeManager.active();
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 12, theme.text());
	}
}
