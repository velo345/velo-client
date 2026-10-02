package net.veloclient.velo.client.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloValueRow;
import net.veloclient.velo.client.gui.window.VeloWindow;

/**
 * Catch-all "Settings" tab (gear icon) grouping actions that don't belong to
 * any particular module - previously two of their own standalone sidebar
 * rows in {@link ModMenuScreen} (Theme Editor, Open Mods Folder), folded in
 * here to cut down on side-nav clutter.
 */
public final class SettingsTabScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 22;
	private static final int CARD_PAD = 8;
	private static final int SECTION_GAP = 22;

	private Text status = Text.literal("");
	/** Card rectangles + their section titles, computed in layout and drawn behind the rows. */
	private final java.util.List<int[]> cards = new java.util.ArrayList<>();
	private final java.util.List<String> cardTitles = new java.util.ArrayList<>();

	public SettingsTabScreen(Screen parent) {
		super(Text.literal("Settings"), 340, 250);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		cards.clear();
		cardTitles.clear();
		int x = contentX() + CARD_PAD;
		int width = contentWidth() - CARD_PAD * 2;
		int y = contentY() + 12;

		y = section("Appearance", y, 1);
		addDrawableChild(new VeloValueRow(x, y, width, ROW_HEIGHT, Text.literal("Theme"), VeloValueRow.Kind.LINK,
				() -> net.veloclient.velo.client.theme.ThemeManager.active().name(), null,
				b -> this.client.setScreen(new ThemeEditorScreen(this))));
		y += ROW_HEIGHT + CARD_PAD + SECTION_GAP;

		y = section("Controls", y, 1);
		var menuKey = net.veloclient.velo.client.keybind.VeloKeybinds.OPEN_MOD_MENU;
		addDrawableChild(new VeloValueRow(x, y, width, ROW_HEIGHT, Text.literal("Open Velo menu"), VeloValueRow.Kind.KEY,
				() -> menuKey.getBoundKeyLocalizedText().getString(), null,
				b -> {
					listeningForMenuKey = true;
					layoutContent();
				}).listening(listeningForMenuKey));
		y += ROW_HEIGHT + CARD_PAD + SECTION_GAP;

		y = section("Files", y, 1);
		addDrawableChild(new VeloValueRow(x, y, width, ROW_HEIGHT, Text.literal("Mods folder"), VeloValueRow.Kind.LINK,
				() -> "Open", null,
				b -> FileManagerOpener.open(FabricLoader.getInstance().getGameDir().resolve("mods").toFile(),
						msg -> status = Text.literal(msg))));
	}

	/** Reserves a titled card for {@code rows} rows starting at {@code y}; returns the first row's y. */
	private int section(String title, int y, int rows) {
		int height = rows * ROW_HEIGHT + CARD_PAD;
		cards.add(new int[] {contentX(), y, contentWidth(), height});
		cardTitles.add(title);
		return y + CARD_PAD / 2;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		for (int i = 0; i < cards.size(); i++) {
			int[] c = cards.get(i);
			context.drawTextWithShadow(this.textRenderer, net.veloclient.velo.client.gui.title.TitleScreenTheme.tileFont(cardTitles.get(i)),
					c[0] + 2, c[1] - 12, VeloStyle.textMuted());
			VeloDraw.fillRounded(context, c[0], c[1], c[2], c[3], VeloStyle.RADIUS_CARD, VeloStyle.card());
			VeloDraw.strokeRounded(context, c[0], c[1], c[2], c[3], VeloStyle.RADIUS_CARD, VeloStyle.border());
		}
	}

	private boolean listeningForMenuKey;

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (listeningForMenuKey) {
			listeningForMenuKey = false;
			if (input.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				var key = net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(input.key());
				//? if <26.1 {
				net.veloclient.velo.client.keybind.MenuKeySync.set(key.getTranslationKey());
				//?} else {
				/*net.veloclient.velo.client.keybind.MenuKeySync.set(key.getName());
				*///?}
				status = Text.literal("Saved - the launcher shows the same key.");
			}
			layoutContent();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (listeningForMenuKey) {
			context.drawTextWithShadow(this.textRenderer, "Press the new key - Esc cancels", contentX() + 2, contentBottom() - 10, VeloStyle.textMuted());
		} else {
			context.drawTextWithShadow(this.textRenderer, status, contentX() + 2, contentBottom() - 10, VeloStyle.textMuted());
		}
	}
}
