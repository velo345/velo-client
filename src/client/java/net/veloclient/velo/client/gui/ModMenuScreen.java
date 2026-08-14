package net.veloclient.velo.client.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.veloclient.velo.client.gui.widget.VeloModuleTile;
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
	private static final int TILE_SIZE = 88;
	private static final int TILE_TOTAL_HEIGHT = TILE_SIZE + 22;
	private static final int TILE_GAP = 8;

	private ModuleCategory selectedCategory = ModuleCategory.HUD;
	private TextFieldWidget searchBox;
	private VeloScrollRegion scrollRegion;
	private VeloScrollRegion sidebarRegion;
	private int gridColumns = 1;
	private Text statusText = Text.literal("");
	private long statusUntilMs;

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

		VeloNavButton themeButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("theme_editor"), Text.literal("Theme Editor"),
				b -> this.client.setScreen(new ThemeEditorScreen(this)));
		addSelectableChild(themeButton);
		sidebarRegion.addRow(themeButton);

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

		VeloNavButton crosshairsButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("crosshairs"), Text.literal("Crosshairs"),
				b -> this.client.setScreen(new CrosshairSelectScreen(this)));
		addSelectableChild(crosshairsButton);
		sidebarRegion.addRow(crosshairsButton);

		VeloNavButton modsFolderButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("mods_folder"), Text.literal("Open Mods Folder"),
				b -> openInFileManager(FabricLoader.getInstance().getGameDir().resolve("mods").toFile()));
		addSelectableChild(modsFolderButton);
		sidebarRegion.addRow(modsFolderButton);

		VeloNavButton profilesButton = new VeloNavButton(sidebarX, 0, SIDEBAR_WIDTH, NAV_ROW_HEIGHT,
				VeloNavIcons.of("profiles"), Text.literal("Profiles"),
				b -> this.client.setScreen(new ProfileScreen(this)));
		addSelectableChild(profilesButton);
		sidebarRegion.addRow(profilesButton);

		sidebarRegion.layout(NAV_ROW_HEIGHT, 2);

		int listWidth = contentWidth() - SIDEBAR_WIDTH - 14;
		int searchY = contentY();
		searchBox = new TextFieldWidget(this.textRenderer, listX, searchY, listWidth, 18, Text.literal("Search"));
		searchBox.setPlaceholder(Text.literal("Search modules..."));
		searchBox.setDrawsBackground(false);
		searchBox.setChangedListener(s -> refreshGrid());
		addDrawableChild(searchBox);

		int gridTop = searchY + 24;
		int gridHeight = contentBottom() - gridTop;
		gridColumns = Math.max(1, (listWidth + TILE_GAP) / (TILE_SIZE + TILE_GAP));
		scrollRegion = new VeloScrollRegion(listX, gridTop, listWidth, gridHeight);
		refreshGrid();
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
			case UTILITY -> "utility";
		};
	}

	private void showStatus(String text) {
		statusText = Text.literal(text);
		statusUntilMs = System.currentTimeMillis() + 3500;
	}

	/**
	 * Opens a folder in the system's file manager. The previous version tried
	 * {@code Util.getOperatingSystem().open(dir)} first, which on this KDE/
	 * Plasma setup (and likely others) shells out to {@code xdg-open} with a
	 * bare filesystem path rather than a {@code file://} URI - confirmed by
	 * hand that {@code xdg-open /some/path} exits 0 and opens nothing (KDE's
	 * mime lookup fails on the missing URI scheme), while {@code xdg-open
	 * file:///some/path} reliably opens Dolphin. Since that "succeeds" with
	 * no exception and no visible effect, every fallback after it (AWT
	 * Desktop, raw xdg-open) was unreachable dead code - the button looked
	 * broken because the very first attempt silently no-oped. This now uses
	 * the verified-working, OS-specific command first and only falls back if
	 * the process itself fails to *start* (tool missing), not based on exit
	 * code - several of these tools (explorer.exe in particular) return
	 * unreliable exit codes even on success.
	 */
	private void openInFileManager(java.io.File dir) {
		showStatus("Opening " + dir.getName() + "...");
		java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			StringBuilder attempts = new StringBuilder();
			String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			String[][] candidates;
			if (osName.contains("win")) {
				candidates = new String[][] {{"explorer.exe", dir.getAbsolutePath()}};
			} else if (osName.contains("mac") || osName.contains("darwin")) {
				candidates = new String[][] {{"open", dir.getAbsolutePath()}};
			} else {
				candidates = new String[][] {{"xdg-open", dir.toURI().toString()}};
			}
			for (String[] command : candidates) {
				try {
					new ProcessBuilder(command).start();
					MinecraftClient.getInstance().execute(() -> showStatus("Opened " + dir.getName() + " - check your taskbar if it's not on top"));
					return;
				} catch (Throwable t) {
					attempts.append(String.join(" ", command)).append(": ").append(t).append("; ");
				}
			}
			try {
				Util.getOperatingSystem().open(dir);
				MinecraftClient.getInstance().execute(() -> showStatus("Opened " + dir.getName() + " - check your taskbar if it's not on top"));
				return;
			} catch (Throwable t) {
				attempts.append("vanilla open: ").append(t).append("; ");
			}
			try {
				java.awt.Desktop.getDesktop().open(dir);
				MinecraftClient.getInstance().execute(() -> showStatus("Opened " + dir.getName() + " - check your taskbar if it's not on top"));
				return;
			} catch (Throwable t) {
				attempts.append("AWT Desktop: ").append(t);
			}
			net.veloclient.velo.VeloClient.LOGGER.error("Could not open {} in a file manager - all methods failed: {}", dir, attempts);
			MinecraftClient.getInstance().execute(() -> showStatus("Couldn't open a file manager - see the log"));
		});
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
		List<Module> modules = ModuleRegistry.byCategory(this.selectedCategory);
		for (Module module : modules) {
			if (!query.isEmpty() && !module.displayName().toLowerCase().contains(query)) {
				continue;
			}
			VeloModuleTile tile = new VeloModuleTile(0, 0, TILE_SIZE, module,
					() -> this.client.setScreen(module.id().equals("command-keybinds")
							? new CommandKeybindsScreen(this)
							: new ModuleConfigScreen(module, this)));
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}
		scrollRegion.layoutGrid(gridColumns, TILE_SIZE, TILE_TOTAL_HEIGHT, TILE_GAP);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layoutGrid(gridColumns, TILE_SIZE, TILE_TOTAL_HEIGHT, TILE_GAP);
			return true;
		}
		if (sidebarRegion != null && sidebarRegion.scroll(mouseX, mouseY, verticalAmount)) {
			sidebarRegion.layout(NAV_ROW_HEIGHT, 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
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
		if (System.currentTimeMillis() < statusUntilMs) {
			int width = this.textRenderer.getWidth(statusText);
			context.drawTextWithShadow(this.textRenderer, statusText,
					windowX + windowWidth - width - PADDING, windowY + windowHeight - 14, 0xFFAADDFF);
		}
	}
}
