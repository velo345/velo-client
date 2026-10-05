package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloModuleTile;
import net.veloclient.velo.client.gui.widget.VeloSectionHeader;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloNavButton;
import net.veloclient.velo.client.gui.widget.VeloNavIcons;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.ModuleConfigScreen;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.List;

/**
 * The main Velo panel (design spec section 5): a Lunar/Feather-style grid of
 * module tiles - click the icon to open that module's settings, flip the
 * switch at the bottom of the tile to enable/disable without opening
 * anything.
 */
public final class ModMenuScreen extends VeloWindow implements ModuleConfigScreen.Reopenable {

	private static final int SIDEBAR_WIDTH = 120;
	private static final int NAV_ROW_HEIGHT = 24;
	/** Minimum tile width; tiles stretch to fill the row. */
	private static final int TILE_SIZE = 88;
	/** Height of a tile's icon+name area (the on/off strip adds 22 below it). */
	private static final int TILE_ICON_AREA = 76;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_AREA + 22;
	private static final int TILE_GAP = 8;

	private static final int CLEAR_BUTTON_WIDTH = 18;
	private static final int SEARCH_HEIGHT = 22;
	private static final int SEARCH_ICON_SPACE = 22;

	private ModuleCategory selectedCategory = ModuleCategory.HUD;
	private TextFieldWidget searchBox;
	private net.veloclient.velo.client.gui.widget.VeloButton clearSearchButton;
	private VeloScrollRegion scrollRegion;
	private VeloScrollRegion sidebarRegion;
	private int gridColumns = 1;
	private int tileWidth = TILE_SIZE;

	public ModMenuScreen() {
		super(Text.literal("Velo Client"), 640, 480);
	}

	@Override
	public void reopen() {
		this.layoutContent();
	}

	/** Module categories in the order people look for them. */
	private static final ModuleCategory[] CATEGORY_ORDER = {
			ModuleCategory.HUD, ModuleCategory.QOL, ModuleCategory.PERFORMANCE, ModuleCategory.RENDERING,
			ModuleCategory.COSMETICS, ModuleCategory.SERVER_TOOLS, ModuleCategory.DEBUG};
	private static final int TOP_BAR = 22;

	private final java.util.List<net.veloclient.velo.client.gui.widget.VeloIconButton> toolButtons = new java.util.ArrayList<>();

	private int bodyY() {
		return contentY() + TOP_BAR + 10;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		toolButtons.clear();

		// Top bar: client features (not module categories) on the right, search on the left.
		int right = contentX() + contentWidth();
		int barY = contentY();
		// Only Menu, Friends and Settings live in the bar; every other feature is in the Menu dropdown.
		Object[][] tools = {
				{"settings", "Settings", (Runnable) () -> this.client.setScreen(new SettingsTabScreen(this))},
				{"friends", "Friends", (Runnable) () -> this.client.setScreen(new FriendsScreen(this))},
		};
		int bx = right;
		for (Object[] tool : tools) {
			bx -= TOP_BAR;
			var button = new net.veloclient.velo.client.gui.widget.VeloIconButton(bx, barY, TOP_BAR, VeloNavIcons.of((String) tool[0]),
					Text.literal((String) tool[1]), (Runnable) tool[2]);
			if (tool[0].equals("friends")) {
				button.badge(ModMenuScreen::friendsBadge);
			}
			toolButtons.add(button);
			addDrawableChild(button);
			bx -= 4;
		}
		menuButton = new net.veloclient.velo.client.gui.widget.VeloIconButton(0, barY, TOP_BAR, VeloNavIcons.of("menu"),
				Text.literal("Menu"), () -> menuOpen = !menuOpen).withPlainLabel().active(() -> menuOpen);
		bx -= menuButton.getWidth();
		menuButton.setX(bx);
		toolButtons.add(menuButton);
		addDrawableChild(menuButton);
		// Edit HUD sits right next to the search bar - the thing people open R-Shift for most.
		var editHud = new net.veloclient.velo.client.gui.widget.VeloIconButton(0, barY, TOP_BAR, VeloNavIcons.of("hud_layout"),
				Text.literal("Edit HUD Layout"), () -> this.client.setScreen(new HudEditScreen(this))).withLabel();
		bx -= 10 + editHud.getWidth();
		editHud.setX(bx);
		toolButtons.add(editHud);
		addDrawableChild(editHud);
		int searchRight = bx - 6;

		// Sidebar: module categories only.
		int sidebarX = contentX();
		int listX = sidebarX + SIDEBAR_WIDTH + 14;
		sidebarRegion = new VeloScrollRegion(sidebarX, bodyY(), SIDEBAR_WIDTH, contentBottom() - bodyY());
		sidebarRegion.addRow(new VeloSectionHeader(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT - 6, "Categories"));
		for (ModuleCategory category : CATEGORY_ORDER) {
			ModuleCategory cat = category;
			VeloNavButton button = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
					VeloNavIcons.of(categoryIcon(category)), Text.literal(category.displayName()), b -> {
						this.selectedCategory = cat;
						if (searchBox != null) {
							searchBox.setText("");
						}
						layoutContent();
					})
					.selected(category == selectedCategory);
			addSelectableChild(button);
			sidebarRegion.addRow(button);
		}
		sidebarRegion.layout(NAV_ROW_HEIGHT, 2);

		int searchX = contentX();
		int searchWidth = searchRight - searchX;
		String previous = searchBox != null ? searchBox.getText() : "";
		searchBox = new TextFieldWidget(this.textRenderer, searchX + SEARCH_ICON_SPACE, barY + 5,
				searchWidth - SEARCH_ICON_SPACE - CLEAR_BUTTON_WIDTH - 4, 12, Text.literal("Search"));
		searchBox.setPlaceholder(Text.literal("Search all modules..."));
		searchBox.setDrawsBackground(false);
		searchBox.setText(previous);
		searchBox.setChangedListener(q -> refreshGrid());
		addDrawableChild(searchBox);
		searchBarX = searchX;
		searchBarWidth = searchWidth;

		clearSearchButton = new net.veloclient.velo.client.gui.widget.VeloButton(
				searchX + searchWidth - CLEAR_BUTTON_WIDTH - 2, barY + 2, CLEAR_BUTTON_WIDTH, 18, Text.literal(""),
				b -> {
					searchBox.setText("");
					refreshGrid();
				});
		addDrawableChild(clearSearchButton);

		int listWidth = contentWidth() - SIDEBAR_WIDTH - 14;
		int gridTop = bodyY() + 18;
		int gridHeight = contentBottom() - gridTop;
		int usable = listWidth - 8;
		gridColumns = Math.max(1, (usable + TILE_GAP) / (TILE_SIZE + TILE_GAP));
		tileWidth = (usable - TILE_GAP * (gridColumns - 1)) / gridColumns;
		scrollRegion = new VeloScrollRegion(listX, gridTop, listWidth, gridHeight);
		refreshGrid();
	}

	private int searchBarX;
	private int searchBarWidth;

	// ---- Feature menu (burger dropdown) ----

	private net.veloclient.velo.client.gui.widget.VeloIconButton menuButton;
	private boolean menuOpen;
	private float menuProgress;
	private long menuNanos;
	private final java.util.Map<String, Float> menuHover = new java.util.HashMap<>();
	private static final int MENU_COL = 190;
	private static final int MENU_ROW = 30;
	private static final int MENU_HEADER = 16;
	private static final int MENU_PAD = 6;

	private record MenuItem(String icon, String title, String hint, Runnable action) {
	}

	private record MenuSection(String title, List<MenuItem> items) {
		int height() {
			return MENU_HEADER + items.size() * MENU_ROW;
		}
	}

	/** A placed header ({@code item == null}) or row. */
	private record MenuSlot(int x, int y, String header, MenuItem item, int order) {
	}

	/** Dev-only (screenshot tour). */
	public void openMenuForTour() {
		menuOpen = true;
	}

	private List<MenuSection> menuSections() {
		return List.of(
				new MenuSection("Explore", List.of(
						new MenuItem("map", "World Map", "Everywhere you've explored  (M)",
								() -> this.client.setScreen(new net.veloclient.velo.client.worldmap.WorldMapScreen(this))),
						new MenuItem("waypoints", "Waypoints", "Saved places and death points", () -> this.client.setScreen(new WaypointsScreen(this))))),
				new MenuSection("Your look", List.of(
						new MenuItem("capes", "Capes & Store", "Your capes and the Velo Store", () -> this.client.setScreen(new CapeEquipScreen(this))),
						new MenuItem("skins", "Skins", "Switch your Minecraft skin", () -> this.client.setScreen(new VeloSkinsScreen(this))))),
				new MenuSection("Tools", List.of(
						new MenuItem("queue", "Background Queue", "Wait in queues while you play",
								() -> this.client.setScreen(new BackgroundQueueSessionsScreen(this, null))),
						new MenuItem("schematics", "Schematics", "Litematica & WorldEdit files", () -> this.client.setScreen(new SchematicsScreen(this))),
						new MenuItem("profiles", "Profiles", "Switch between setting profiles", () -> this.client.setScreen(new ProfileScreen(this))))));
	}

	private int menuY() {
		return contentY() + TOP_BAR + 6;
	}

	/** Sections stacked in one column, or side by side (2-3 columns) when the window is short. */
	private int menuColumns() {
		List<MenuSection> sections = menuSections();
		int available = contentBottom() - menuY() - MENU_PAD * 2 - 4;
		for (int columns = 1; columns < sections.size(); columns++) {
			if (columnHeights(sections, columns).stream().mapToInt(Integer::intValue).max().orElse(0) <= available) {
				return columns;
			}
		}
		return sections.size();
	}

	/** Puts the sections, in order, into {@code columns} columns (each section stays whole). */
	private static List<Integer> columnHeights(List<MenuSection> sections, int columns) {
		List<Integer> heights = new java.util.ArrayList<>();
		int perColumn = (sections.size() + columns - 1) / columns;
		for (int i = 0; i < sections.size(); i++) {
			if (i % perColumn == 0) {
				heights.add(0);
			}
			heights.set(heights.size() - 1, heights.getLast() + sections.get(i).height() + (i % perColumn == 0 ? 0 : 6));
		}
		return heights;
	}

	private int menuColumnWidth() {
		return Math.min(MENU_COL, (contentWidth() - MENU_PAD * 2) / menuColumns());
	}

	private int menuWidth() {
		return menuColumns() * menuColumnWidth() + MENU_PAD * 2;
	}

	private int menuHeight() {
		return columnHeights(menuSections(), menuColumns()).stream().mapToInt(Integer::intValue).max().orElse(0) + MENU_PAD * 2;
	}

	private int menuX() {
		// Lined up under the Menu button, kept inside the window.
		int anchor = menuButton != null ? menuButton.getX() + menuButton.getWidth() : contentX() + contentWidth();
		return Math.max(contentX(), Math.min(contentX() + contentWidth(), anchor + 60) - menuWidth());
	}

	private List<MenuSlot> menuLayout() {
		List<MenuSection> sections = menuSections();
		int columns = menuColumns();
		int perColumn = (sections.size() + columns - 1) / columns;
		int colW = menuColumnWidth();
		List<MenuSlot> slots = new java.util.ArrayList<>();
		int order = 0;
		int y = 0;
		for (int i = 0; i < sections.size(); i++) {
			int x = menuX() + MENU_PAD + (i / perColumn) * colW;
			if (i % perColumn == 0) {
				y = menuY() + MENU_PAD;
			} else {
				y += 6;
			}
			MenuSection section = sections.get(i);
			slots.add(new MenuSlot(x, y, section.title(), null, order++));
			y += MENU_HEADER;
			for (MenuItem item : section.items()) {
				slots.add(new MenuSlot(x, y, null, item, order++));
				y += MENU_ROW;
			}
		}
		return slots;
	}

	/** The dropdown: a small window under the Menu button that fades, slides and scales in. */
	private void drawMenu(DrawContext context, int mouseX, int mouseY) {
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(menuNanos, now);
		menuNanos = now;
		menuProgress = net.veloclient.velo.client.gui.widget.VeloAnim.step(menuProgress, menuOpen ? 1f : 0f, dt * 1.6f);
		if (menuProgress < 0.01f) {
			menuHover.clear();
			return;
		}
		float p = menuProgress;
		float eased = 1f - (1f - p) * (1f - p);
		int x = menuX();
		int y = menuY();
		int width = menuWidth();
		int h = menuHeight();
		int colW = menuColumnWidth();
		var theme = net.veloclient.velo.client.theme.ThemeManager.active();
		context.getMatrices().pushMatrix();
		float originX = menuButton != null ? menuButton.getX() + menuButton.getWidth() / 2f : x + width;
		context.getMatrices().translate(originX, y);
		context.getMatrices().scale(0.94f + 0.06f * eased, 0.94f + 0.06f * eased);
		context.getMatrices().translate(-originX, -y + (1f - eased) * -8f);

		// Same surface as Velo windows: soft shadow, opaque body, faint accent wash on top, hairline border.
		VeloDraw.shadow(context, x, y, width, h, 10, 14, 5, VeloUi.withAlpha(0xFF000000, Math.round(0x80 * p)));
		VeloDraw.fillRounded(context, x, y, width, h, 10, VeloUi.withAlpha(VeloStyle.window(), Math.round(0xFA * p)));
		VeloDraw.fillRoundedGradient(context, x, y, width, 26, 10, VeloUi.withAlpha(theme.accentStart(), Math.round(0x14 * p)), 0x00000000);
		VeloDraw.strokeRounded(context, x, y, width, h, 10, VeloUi.withAlpha(theme.text(), Math.round(0x26 * p)));
		for (int c = 1; c < menuColumns(); c++) {
			int lx = x + MENU_PAD + c * colW - 1;
			context.fill(lx, y + 10, lx + 1, y + h - 10, VeloUi.withAlpha(theme.text(), Math.round(0x14 * p)));
		}

		for (MenuSlot slot : menuLayout()) {
			// Rows arrive one after another.
			float rowP = Math.max(0f, Math.min(1f, p * 1.7f - slot.order() * 0.05f));
			int alpha = Math.round(255 * rowP);
			if (alpha < 4) {
				continue;
			}
			if (slot.item() == null) {
				String label = slot.header().toUpperCase(java.util.Locale.ROOT);
				int cursor = slot.x() + 8;
				for (int i = 0; i < label.length(); i++) {
					Text ch = TitleScreenTheme.tileFont(String.valueOf(label.charAt(i)));
					context.drawTextWithShadow(this.textRenderer, ch, cursor, slot.y() + 5, VeloUi.withAlpha(VeloStyle.textFaint(), alpha));
					cursor += this.textRenderer.getWidth(ch) + 1;
				}
				continue;
			}
			MenuItem item = slot.item();
			int rx = slot.x();
			int ry = slot.y();
			int rw = colW - 2;
			boolean hovered = menuOpen && mouseX >= rx && mouseX < rx + rw && mouseY >= ry && mouseY < ry + MENU_ROW - 2;
			float hover = net.veloclient.velo.client.gui.widget.VeloAnim.step(menuHover.getOrDefault(item.title(), 0f), hovered ? 1f : 0f, dt);
			menuHover.put(item.title(), hover);
			if (hover > 0.01f) {
				VeloDraw.fillRounded(context, rx, ry, rw, MENU_ROW - 2, 7, VeloUi.withAlpha(theme.text(), Math.round(0x12 * hover * rowP)));
				float bar = (MENU_ROW - 12) * hover;
				VeloDraw.fillRounded(context, (float) rx + 1, ry + (MENU_ROW - 2 - bar) / 2f, 3f, bar, 1.5f, theme.accentStart());
			}
			float nudge = hover * 1.5f;
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(nudge, 0f);
			// Icon in a little rounded tile that picks up the accent on hover.
			int tile = 20;
			int tx = rx + 7;
			int ty = ry + (MENU_ROW - 2 - tile) / 2;
			int tileBg = net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), 0x10),
					VeloUi.withAlpha(theme.accentStart(), 0x40), hover);
			VeloDraw.fillRounded(context, tx, ty, tile, tile, 6, VeloUi.withAlpha(tileBg, Math.round(((tileBg >>> 24) & 0xFF) * rowP)));
			int idle = VeloUi.withAlpha(theme.text(), 0xA8);
			int iconColor = net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(idle, theme.accentStart() | 0xFF000000, hover);
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, VeloNavIcons.of(item.icon()), tx + 4, ty + 4,
					0f, 0f, 12, 12, 1024, 1024, 1024, 1024, VeloUi.withAlpha(iconColor, alpha));
			int textX = tx + tile + 8;
			int textColor = net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(idle, VeloStyle.text(), hover);
			context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(item.title()), textX, ry + 4,
					VeloUi.withAlpha(textColor, alpha));
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(item.hint(), rx + rw - textX - 6), textX, ry + 15,
					VeloUi.withAlpha(VeloStyle.textFaint(), alpha));
			context.getMatrices().popMatrix();
		}
		context.getMatrices().popMatrix();
	}

	/** Handles a click while the menu is open: picks an item, or closes the menu. */
	private boolean menuClick(double mouseX, double mouseY) {
		if (!menuOpen) {
			return false;
		}
		int rw = menuColumnWidth() - 2;
		for (MenuSlot slot : menuLayout()) {
			if (slot.item() != null && mouseX >= slot.x() && mouseX < slot.x() + rw && mouseY >= slot.y() && mouseY < slot.y() + MENU_ROW - 2) {
				menuOpen = false;
				menuProgress = 0;
				slot.item().action().run();
				return true;
			}
		}
		boolean inside = mouseX >= menuX() && mouseX < menuX() + menuWidth() && mouseY >= menuY() && mouseY < menuY() + menuHeight();
		if (!inside) {
			menuOpen = false;
		}
		return true;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (menuOpen && menuButton != null && menuButton.isMouseOver(click.x(), click.y())) {
			menuOpen = false;
			return true;
		}
		if (menuClick(click.x(), click.y())) {
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (menuOpen && input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			menuOpen = false;
			return true;
		}
		return super.keyPressed(input);
	}

	private static int friendsBadge() {
		int unread = net.veloclient.velo.client.network.SocialClient.totalUnread();
		var state = net.veloclient.velo.client.network.SocialClient.snapshot();
		return unread + (state == null ? 0 : state.incoming.size());
	}

	private static String categoryIcon(ModuleCategory category) {
		return switch (category) {
			case PERFORMANCE -> "performance";
			case HUD -> "hud";
			case RENDERING -> "rendering";
			case SERVER_TOOLS -> "server_tools";
			case DEBUG -> "debug";
			case COSMETICS -> "cosmetics";
			case QOL -> "qol";
		};
	}

	private void refreshGrid() {
		if (scrollRegion == null) {
			return;
		}
		// Remove previously-added tile widgets so re-filtering doesn't stack
		// duplicates. Screen keeps widgets in two separate internal lists
		// (children for input, drawables for rendering) - children().removeAll(...)
		// only touched the first, so old tiles kept being drawn on top of the
		// new ones. remove(Element) clears a widget from both.
		for (var row : scrollRegion.rowsSnapshot()) {
			this.remove(row);
		}
		scrollRegion.clearRows();

		String query = searchBox.getText().toLowerCase();
		if (clearSearchButton != null) {
			clearSearchButton.visible = !query.isEmpty();
			clearSearchButton.active = !query.isEmpty();
		}
		// A non-empty search looks across every category (not just the
		// selected one) so switching tabs isn't required to find a module by
		// name; clearing it (or the × button) goes back to the normal
		// per-category view.
		List<Module> modules = query.isEmpty()
				? ModuleRegistry.byCategory(this.selectedCategory)
				: List.copyOf(ModuleRegistry.all());
		for (Module module : modules) {
			if (!query.isEmpty() && !module.displayName().toLowerCase().contains(query)) {
				continue;
			}
			VeloModuleTile tile = new VeloModuleTile(0, 0, TILE_ICON_AREA, module,
					() -> this.client.setScreen(module.id().equals("command-keybinds")
							? new CommandKeybindsScreen(this)
							: new ModuleConfigScreen(module, this)));
			tile.setWidth(tileWidth);
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}
		scrollRegion.layoutGrid(gridColumns, tileWidth, TILE_TOTAL_HEIGHT, TILE_GAP);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layoutGrid(gridColumns, tileWidth, TILE_TOTAL_HEIGHT, TILE_GAP);
			return true;
		}
		if (sidebarRegion != null && sidebarRegion.scroll(mouseX, mouseY, verticalAmount)) {
			sidebarRegion.layout(NAV_ROW_HEIGHT, 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		int listX = contentX() + SIDEBAR_WIDTH + 14;
		int barY = contentY();
		// Hairline under the top bar, and between the category list and the grid.
		context.fill(contentX(), barY + TOP_BAR + 4, contentX() + contentWidth(), barY + TOP_BAR + 5, VeloStyle.border());
		context.fill(contentX() + SIDEBAR_WIDTH + 6, bodyY(), contentX() + SIDEBAR_WIDTH + 7, contentBottom(), VeloStyle.border());

		boolean focused = searchBox != null && searchBox.isFocused();
		boolean hovered = mouseX >= searchBarX && mouseX < searchBarX + searchBarWidth && mouseY >= barY && mouseY < barY + SEARCH_HEIGHT;
		VeloStyle.drawInputField(context, searchBarX, barY, searchBarWidth, SEARCH_HEIGHT, focused, hovered);
		VeloDraw.searchGlyph(context, searchBarX + 11f, barY + SEARCH_HEIGHT / 2f, 9f, focused ? VeloStyle.accent() : VeloStyle.textMuted());

		String query = searchBox == null ? "" : searchBox.getText();
		String heading = query.isEmpty() ? selectedCategory.displayName() : "Search results";
		int count = scrollRegion == null ? 0 : scrollRegion.rowsSnapshot().size();
		int headingY = bodyY() + 2;
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(heading), listX, headingY, VeloStyle.text());
		int headingWidth = this.textRenderer.getWidth(TitleScreenTheme.tileFont(heading));
		context.drawTextWithShadow(this.textRenderer, count + (count == 1 ? " module" : " modules"),
				listX + headingWidth + 6, headingY, VeloStyle.textFaint());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (clearSearchButton != null && clearSearchButton.visible) {
			VeloDraw.cross(context, clearSearchButton.getX() + CLEAR_BUTTON_WIDTH / 2f, clearSearchButton.getY() + 9f, 5f, 1.2f, VeloStyle.textMuted());
		}
		// Sidebar buttons and module tiles are registered via
		// addSelectableChild (input only, not auto-rendered) so they can be
		// drawn here inside a GPU scissor - a row that's only partially
		// inside its region gets visually cut off at the edge instead of
		// either fully hiding or spilling past the window border.
		if (sidebarRegion != null) {
			sidebarRegion.renderRows(context, mouseX, mouseY, delta);
			sidebarRegion.renderScrollbar(context, NAV_ROW_HEIGHT, 2);
		}
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbarGrid(context, gridColumns, TILE_TOTAL_HEIGHT, TILE_GAP);
		}
		drawMenu(context, mouseX, mouseY);
		if (menuProgress < 0.01f) {
			for (var button : toolButtons) {
				net.veloclient.velo.client.gui.widget.VeloIconButton.drawTooltip(context, button);
			}
		}
	}
}
