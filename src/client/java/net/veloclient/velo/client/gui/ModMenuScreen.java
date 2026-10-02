package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloModuleTile;
import net.veloclient.velo.client.gui.widget.VeloSectionHeader;
import net.veloclient.velo.client.gui.widget.VeloStyle;
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

	@Override
	protected void layoutContent() {
		this.clearChildren();

		int sidebarX = contentX();
		int listX = sidebarX + SIDEBAR_WIDTH + 14;

		// The sidebar (8 categories + 5 action buttons) can need more
		// vertical space than the window has at higher GUI scales, so it's
		// laid out through a VeloScrollRegion like the module grid rather
		// than absolute-positioned - nothing ever gets pushed off the
		// bottom of the window where it can't be seen or clicked.
		sidebarRegion = new VeloScrollRegion(sidebarX, contentY(), SIDEBAR_WIDTH, contentBottom() - contentY());
		sidebarRegion.addRow(new VeloSectionHeader(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT, "Modules"));
		for (ModuleCategory category : ModuleCategory.values()) {
			ModuleCategory cat = category;
			VeloNavButton button = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
					VeloNavIcons.of(categoryIcon(category)), Text.literal(category.displayName()), b -> {
						this.selectedCategory = cat;
						layoutContent();
					})
					.selected(category == selectedCategory);
			addSelectableChild(button);
			sidebarRegion.addRow(button);
		}

		sidebarRegion.addRow(new VeloSectionHeader(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT, "Social & Tools"));

		VeloNavButton friendsButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("friends"), Text.literal(friendsLabel()),
				b -> this.client.setScreen(new FriendsScreen()));
		addSelectableChild(friendsButton);
		sidebarRegion.addRow(friendsButton);

		VeloNavButton waypointsButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("waypoints"), Text.literal("Waypoints"),
				b -> this.client.setScreen(new WaypointsScreen(this)));
		addSelectableChild(waypointsButton);
		sidebarRegion.addRow(waypointsButton);

		VeloNavButton queueButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("queue"), Text.literal("Background Queue"),
				b -> this.client.setScreen(new BackgroundQueueSessionsScreen(this, null)));
		addSelectableChild(queueButton);
		sidebarRegion.addRow(queueButton);

		VeloNavButton capesButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("capes"), Text.literal("Capes"),
				b -> this.client.setScreen(new CapeEquipScreen(this)));
		addSelectableChild(capesButton);
		sidebarRegion.addRow(capesButton);

		VeloNavButton hudLayoutButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("hud_layout"), Text.literal("Edit HUD Layout"),
				b -> this.client.setScreen(new HudEditScreen(this)));
		addSelectableChild(hudLayoutButton);
		sidebarRegion.addRow(hudLayoutButton);

		VeloNavButton schematicsButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("schematics"), Text.literal("Schematics"),
				b -> this.client.setScreen(new SchematicsScreen(this)));
		addSelectableChild(schematicsButton);
		sidebarRegion.addRow(schematicsButton);

		VeloNavButton profilesButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("profiles"), Text.literal("Profiles"),
				b -> this.client.setScreen(new ProfileScreen(this)));
		addSelectableChild(profilesButton);
		sidebarRegion.addRow(profilesButton);

		sidebarRegion.addRow(new VeloSectionHeader(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT, "Client"));
		VeloNavButton settingsButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("settings"), Text.literal("Settings"),
				b -> this.client.setScreen(new SettingsTabScreen(this)));
		addSelectableChild(settingsButton);
		sidebarRegion.addRow(settingsButton);

		sidebarRegion.layout(NAV_ROW_HEIGHT, 2);

		int listWidth = contentWidth() - SIDEBAR_WIDTH - 14;
		int searchY = contentY();
		int searchFieldWidth = listWidth - SEARCH_ICON_SPACE - CLEAR_BUTTON_WIDTH - 4;
		searchBox = new TextFieldWidget(this.textRenderer, listX + SEARCH_ICON_SPACE, searchY + 5, searchFieldWidth, 12, Text.literal("Search"));
		searchBox.setPlaceholder(Text.literal("Search modules..."));
		searchBox.setDrawsBackground(false);
		searchBox.setChangedListener(s -> refreshGrid());
		addDrawableChild(searchBox);

		clearSearchButton = new net.veloclient.velo.client.gui.widget.VeloButton(
				listX + listWidth - CLEAR_BUTTON_WIDTH - 2, searchY + 2, CLEAR_BUTTON_WIDTH, 18, Text.literal(""),
				b -> {
					searchBox.setText("");
					refreshGrid();
				});
		addDrawableChild(clearSearchButton);

		int gridTop = searchY + SEARCH_HEIGHT + 24;
		int gridHeight = contentBottom() - gridTop;
		// Columns of at least TILE_SIZE, then stretched so the grid fills the full width (minus the scrollbar).
		int usable = listWidth - 8;
		gridColumns = Math.max(1, (usable + TILE_GAP) / (TILE_SIZE + TILE_GAP));
		tileWidth = (usable - TILE_GAP * (gridColumns - 1)) / gridColumns;
		scrollRegion = new VeloScrollRegion(listX, gridTop, listWidth, gridHeight);
		refreshGrid();
	}

	private static String friendsLabel() {
		int unread = net.veloclient.velo.client.network.SocialClient.totalUnread();
		var state = net.veloclient.velo.client.network.SocialClient.snapshot();
		int requests = state == null ? 0 : state.incoming.size();
		int badge = unread + requests;
		return badge > 0 ? "Friends (" + badge + ")" : "Friends";
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
		int listWidth = contentWidth() - SIDEBAR_WIDTH - 14;
		int searchY = contentY();
		// Sidebar sits on a slightly recessed column, divided from the grid by a hairline.
		context.fill(contentX() + SIDEBAR_WIDTH + 6, contentY(), contentX() + SIDEBAR_WIDTH + 7, contentBottom(), VeloStyle.border());

		boolean focused = searchBox != null && searchBox.isFocused();
		boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= searchY && mouseY < searchY + SEARCH_HEIGHT;
		VeloStyle.drawInputField(context, listX, searchY, listWidth, SEARCH_HEIGHT, focused, hovered);
		VeloDraw.searchGlyph(context, listX + 11f, searchY + SEARCH_HEIGHT / 2f, 9f, focused ? VeloStyle.accent() : VeloStyle.textMuted());

		String query = searchBox == null ? "" : searchBox.getText();
		String heading = query.isEmpty() ? selectedCategory.displayName() : "Search results";
		int count = scrollRegion == null ? 0 : scrollRegion.rowsSnapshot().size();
		int headingY = searchY + SEARCH_HEIGHT + 8;
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
	}
}
