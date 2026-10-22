package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloCapeTile;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Cape library/equip menu (design spec section 6.5): a grid of real cape
 * previews (see {@link VeloCapeTile}) instead of a plain text list. Click a
 * preview to equip it, click the already-equipped one to unequip, the pencil
 * to rename/tune its physics ({@link CapeEditScreen}), "✕" to delete it, or
 * "+ Import" to add a new PNG straight from a native file picker - no
 * separate name field, the filename becomes the cape's name (rename it
 * afterward if you want something else).
 */
public final class CapeEquipScreen extends VeloWindow {

	private static final int TILE_WIDTH = 86;
	private static final int TILE_ICON_HEIGHT = 112;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_HEIGHT + 20;
	private static final int TILE_GAP = 8;
	static final int SHOWCASE_WIDTH = 220;
	/** The slot list on the left (Cape / Wings / Aura, like the launcher's Locker). */
	static final int SLOTS_W = 150;
	static final int SLOT_H = 38;

	private VeloScrollRegion scrollRegion;
	private int gridColumns = 1;
	private Text status = Text.literal("");
	/** The cape shown on the player preview (not necessarily the equipped one). */
	private String selectedId;
	/** Showing the 3D wings instead of the capes. */
	/** Wings / aura picture tiles in the collection grid. */
	private static final int TILE_H = 72;
	private boolean wingsTab;
	/** The wings shown on the player preview ("" = none), null = the equipped pair. */
	private String selectedWings;
	/** Showing the auras. */
	private boolean aurasTab;
	/** The aura shown on the player preview ("" = none), null = the equipped one. */
	private String selectedAura;

	public CapeEquipScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Locker"), 940, 580);
		returnTo(parent);
		onClosed(() -> {
			CapeManager.clearPreviewOverride();
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
		});
	}

	// ---- Header (launcher look): Capes / Wings / Auras tabs, "Get more" to the Store ----

	/** The Locker's tabs: Skin is {@link VeloSkinsScreen} (same header, so it reads as one Locker). */
	public static final String[] LOCKER_TABS = {"Skin", "Capes", "Wings", "Auras"};

	/** Opens the Locker on a tab: 0 Skin, 1 Capes, 2 Wings, 3 Auras. */
	public static net.minecraft.client.gui.screen.Screen open(net.minecraft.client.gui.screen.Screen parent, int tab) {
		if (tab == 0) {
			return new VeloSkinsScreen(parent);
		}
		CapeEquipScreen locker = new CapeEquipScreen(parent);
		locker.wingsTab = tab == 2;
		locker.aurasTab = tab == 3;
		return locker;
	}

	@Override
	protected String[] headerTabs() {
		return LOCKER_TABS;
	}

	@Override
	protected int headerTab() {
		return aurasTab ? 3 : wingsTab ? 2 : 1;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index == 0) {
			this.client.setScreen(new VeloSkinsScreen(returnScreen()));
			return;
		}
		wingsTab = index == 2;
		aurasTab = index == 3;
		if (!wingsTab) {
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
		}
		if (!aurasTab) {
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
		}
		layoutContent();
	}

	/** Dev-only (screenshot tour): switch tab. */
	public void showTabForTour(int index) {
		selectHeaderTab(index + 1);
	}

	private int getMoreX;
	private int getMoreW;

	@Override
	protected void renderHeaderExtras(DrawContext context, int mouseX, int mouseY) {
		Text label = MenuKit.ui("Get more");
		getMoreW = 8 + 10 + 4 + this.textRenderer.getWidth(label) + 8;
		getMoreX = headerRight() - getMoreW;
		int y = windowY + (HEADER_HEIGHT - 18) / 2;
		boolean hovered = MenuKit.inside(mouseX, mouseY, getMoreX, y, getMoreW, 18);
		VeloDraw.fillRounded(context, getMoreX, y, getMoreW, 18, 6, hovered ? 0x1AFFFFFF : 0x0DFFFFFF);
		VeloDraw.strokeRounded(context, getMoreX, y, getMoreW, 18, 6, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		MenuKit.icon(context, "store", getMoreX + 8, y + 4, 10, hovered ? MenuKit.TEXT : MenuKit.TEXT_2);
		MenuKit.text(context, this.textRenderer, label, getMoreX + 22, y + 5, hovered ? MenuKit.TEXT : MenuKit.TEXT_2);
	}

	@Override
	protected boolean headerClick(int mouseX, int mouseY) {
		int y = windowY + (HEADER_HEIGHT - 18) / 2;
		if (MenuKit.inside(mouseX, mouseY, getMoreX, y, getMoreW, 18)) {
			this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this));
			return true;
		}
		return false;
	}

	private void addTabs(int x) {
		// The tabs live in the window header now.
	}

	/** Height of the pinned wing settings block (heading + three slider rows). */
	private static final int SETTINGS_H = 16 + 3 * 24;
	private int regionTileW = TILE_WIDTH;
	private int regionTileH = TILE_TOTAL_HEIGHT;

	private void startTileRegion(int x, int y, int w, int h, int columns, int tileW) {
		scrollRegion = new VeloScrollRegion(x, y, w, Math.max(TILE_H, h));
		gridColumns = columns;
		regionTileW = tileW;
		regionTileH = TILE_H;
	}

	private void addTile(net.minecraft.client.gui.widget.ClickableWidget tile) {
		addSelectableChild(tile);
		scrollRegion.addRow(tile);
	}

	private void finishTileRegion() {
		scrollRegion.layoutGrid(gridColumns, regionTileW, regionTileH, TILE_GAP);
	}

	private int showcaseX() {
		return contentX() + SLOTS_W + 12;
	}

	private int gridX() {
		return showcaseX() + SHOWCASE_WIDTH + 14;
	}

	/** The slot list: what you're wearing per slot; click one to open its tab. */
	private void renderSlots(DrawContext context, int mouseX, int mouseY) {
		drawSlots(context, this.textRenderer, contentX(), contentY(), headerTab(), mouseX, mouseY);
		drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);
	}

	/** The Locker's slot column (shared with the Skin tab, {@link VeloSkinsScreen}). */
	static void drawSlots(DrawContext context, net.minecraft.client.font.TextRenderer font, int x, int y, int activeTab, int mouseX, int mouseY) {
		String cape = CapeManager.equipped().map(CapeDefinition::name).orElse("None");
		String wingsId = net.veloclient.velo.client.cosmetics.wings.WingsManager.equippedId();
		String wings = wingsId == null ? "None" : net.veloclient.velo.client.cosmetics.wings.WingsManager.type(wingsId)
				.map(net.veloclient.velo.client.cosmetics.wings.WingsManager.WingType::name).orElse(wingsId);
		String auraId = net.veloclient.velo.client.cosmetics.aura.AuraManager.equippedId();
		String aura = auraId == null ? "None" : net.veloclient.velo.client.cosmetics.aura.AuraManager.type(auraId)
				.map(net.veloclient.velo.client.cosmetics.aura.AuraManager.AuraType::name).orElse(auraId);
		String skin = net.veloclient.velo.client.skins.SkinLibrary.equippedName();
		if (skin == null || skin.isEmpty()) {
			skin = net.veloclient.velo.client.gui.title.MenuInfo.username();
		}
		String[][] rows = {{"SKIN", skin == null || skin.isEmpty() ? "Your skin" : skin, "skins"}, {"CAPE", cape, "capes"},
				{"WINGS", wings, "wings"}, {"AURA", aura, "bolt"}};
		for (int i = 0; i < rows.length; i++) {
			int ry = y + i * (SLOT_H + 6);
			boolean active = activeTab == i;
			boolean hovered = MenuKit.inside(mouseX, mouseY, x, ry, SLOTS_W, SLOT_H);
			VeloDraw.fillRounded(context, x, ry, SLOTS_W, SLOT_H, 8, active ? VeloUi.withAlpha(MenuKit.accent(), 0x1F) : hovered ? 0xFF1A1A21 : 0xFF131318);
			VeloDraw.strokeRounded(context, x, ry, SLOTS_W, SLOT_H, 8, active ? VeloUi.withAlpha(MenuKit.accent(), 0x80) : 0x17FFFFFF);
			if (active) {
				VeloDraw.fillRounded(context, x + 1, ry + 8, 2, SLOT_H - 16, 1, MenuKit.accent());
			}
			VeloDraw.fillRounded(context, x + 8, ry + 7, 24, 24, 6, active ? VeloUi.withAlpha(MenuKit.accent(), 0x30) : 0x14FFFFFF);
			MenuKit.icon(context, rows[i][2], x + 14, ry + 13, 12, rows[i][1].equals("None") ? MenuKit.TEXT_3 : active ? MenuKit.accent() : MenuKit.TEXT);
			MenuKit.text(context, font, MenuKit.small(rows[i][0]), x + 40, ry + 9, MenuKit.TEXT_3);
			MenuKit.text(context, font, MenuKit.fit(font, MenuKit.uiBold(rows[i][1]), SLOTS_W - 48), x + 40, ry + 19,
					rows[i][1].equals("None") ? MenuKit.TEXT_2 : MenuKit.TEXT);
		}
		// Sync note under the slots.
		int ny = y + rows.length * (SLOT_H + 6) + 6;
		for (Text line : MenuKit.wrap(font, "Synced: other Velo players see what you wear.", MenuKit::ui, SLOTS_W - 4)) {
			MenuKit.text(context, font, line, x + 2, ny, MenuKit.TEXT_3);
			ny += 11;
		}
	}

	// ---- Loadouts (whole looks, shared with the launcher's Locker) under the slots ----

	static final int LOADOUT_H = 18;

	private static int loadoutsTop(int y) {
		return y + LOCKER_TABS.length * (SLOT_H + 6) + 36;
	}

	/** The look rows that fit above {@code bottom} (the last row is always "Save current look"). */
	private static int loadoutRows(int y, int bottom, int count) {
		int room = (bottom - (loadoutsTop(y) + 13)) / (LOADOUT_H + 4);
		return Math.max(0, Math.min(count, room - 1));
	}

	static void drawLoadouts(DrawContext context, net.minecraft.client.font.TextRenderer font, int x, int y, int bottom, int mouseX, int mouseY) {
		var looks = net.veloclient.velo.client.cosmetics.Loadouts.all();
		int top = loadoutsTop(y);
		if (top + 13 + LOADOUT_H > bottom) {
			return;
		}
		MenuKit.text(context, font, MenuKit.small("LOADOUTS"), x + 2, top, MenuKit.TEXT_3);
		int ry = top + 13;
		int shown = loadoutRows(y, bottom, looks.size());
		for (int i = 0; i < shown; i++) {
			var look = looks.get(i);
			boolean worn = look.isWorn();
			boolean hovered = MenuKit.inside(mouseX, mouseY, x, ry, SLOTS_W, LOADOUT_H);
			VeloDraw.fillRounded(context, x, ry, SLOTS_W, LOADOUT_H, 6, worn ? VeloUi.withAlpha(MenuKit.accent(), 0x1F) : hovered ? 0xFF1A1A21 : 0xFF131318);
			VeloDraw.strokeRounded(context, x, ry, SLOTS_W, LOADOUT_H, 6, worn ? VeloUi.withAlpha(MenuKit.accent(), 0x80) : 0x17FFFFFF);
			VeloDraw.fillCircle(context, x + 9f, ry + LOADOUT_H / 2f, 2.5f, worn ? MenuKit.GREEN : 0x40FFFFFF);
			MenuKit.text(context, font, MenuKit.fit(font, MenuKit.uiBold(look.name()), SLOTS_W - 40), x + 17, ry + 5, worn ? MenuKit.TEXT : MenuKit.TEXT_2);
			if (hovered) {
				boolean onDelete = mouseX >= x + SLOTS_W - 16;
				VeloDraw.cross(context, x + SLOTS_W - 9f, ry + LOADOUT_H / 2f, 3f, 1.2f, onDelete ? 0xFFE5484D : MenuKit.TEXT_3);
			}
			ry += LOADOUT_H + 4;
		}
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, ry, SLOTS_W, LOADOUT_H);
		VeloDraw.fillRounded(context, x, ry, SLOTS_W, LOADOUT_H, 6, hovered ? 0x1AFFFFFF : 0x0AFFFFFF);
		VeloDraw.strokeRounded(context, x, ry, SLOTS_W, LOADOUT_H, 6, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		MenuKit.text(context, font, MenuKit.uiBold("+  Save current look"), x + 10, ry + 5, hovered ? MenuKit.TEXT : MenuKit.TEXT_2);
		if (hovered && !looks.isEmpty()) {
			net.minecraft.text.Text tip = MenuKit.ui("Right-click a look to update it");
			MenuKit.text(context, font, MenuKit.fit(font, tip, SLOTS_W), x + 2, ry + LOADOUT_H + 4, MenuKit.TEXT_3);
		}
	}

	/** What the last loadout click did ("Wearing Purple", "Couldn't put on: ..."), for the screen's status line. */
	static String loadoutMessage = "";

	/** A click on the loadouts: wear (left), update (right), delete (the cross) or save. True if it was one. */
	static boolean loadoutClick(double mouseX, double mouseY, int button, int x, int y, int bottom) {
		var looks = net.veloclient.velo.client.cosmetics.Loadouts.all();
		int top = loadoutsTop(y);
		if (top + 13 + LOADOUT_H > bottom) {
			return false;
		}
		int ry = top + 13;
		int shown = loadoutRows(y, bottom, looks.size());
		for (int i = 0; i < shown; i++) {
			var look = looks.get(i);
			if (MenuKit.inside((int) mouseX, (int) mouseY, x, ry, SLOTS_W, LOADOUT_H)) {
				if (button == 1) {
					net.veloclient.velo.client.cosmetics.Loadouts.update(look.name());
					loadoutMessage = "Updated \"" + look.name() + "\"";
				} else if (mouseX >= x + SLOTS_W - 16) {
					net.veloclient.velo.client.cosmetics.Loadouts.delete(look.name());
					loadoutMessage = "Deleted \"" + look.name() + "\"";
				} else {
					var missing = net.veloclient.velo.client.cosmetics.Loadouts.wear(look);
					loadoutMessage = missing.isEmpty() ? "Wearing \"" + look.name() + "\"" : "Couldn't put on: " + String.join(", ", missing);
				}
				return true;
			}
			ry += LOADOUT_H + 4;
		}
		if (MenuKit.inside((int) mouseX, (int) mouseY, x, ry, SLOTS_W, LOADOUT_H)) {
			loadoutMessage = "Saved \"" + net.veloclient.velo.client.cosmetics.Loadouts.saveCurrent() + "\"";
			return true;
		}
		return false;
	}

	/** Which slot row is under the mouse (-1 = none). */
	static int slotAt(int mouseX, int mouseY, int x, int y) {
		for (int i = 0; i < LOCKER_TABS.length; i++) {
			if (MenuKit.inside(mouseX, mouseY, x, y + i * (SLOT_H + 6), SLOTS_W, SLOT_H)) {
				return i;
			}
		}
		return -1;
	}

	/** The stage behind the live preview. */
	private void renderStage(DrawContext context) {
		int x = showcaseX();
		int y = contentY();
		int h = contentBottom() - y;
		VeloDraw.fillRounded(context, x, y, SHOWCASE_WIDTH, h, 10, 0xFF111116);
		VeloDraw.fillRoundedGradient(context, x, y, SHOWCASE_WIDTH, h, 10, VeloUi.withAlpha(MenuKit.accent(), 0x14), 0x00111116);
		VeloDraw.strokeRounded(context, x, y, SHOWCASE_WIDTH, h, 10, 0x17FFFFFF);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		renderSlots(context, mouseX, mouseY);
		renderStage(context);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (loadoutClick(click.x(), click.y(), click.button(), contentX(), contentY(), contentBottom())) {
			// Show the look just put on, not what was being browsed.
			selectedId = null;
			selectedWings = null;
			selectedAura = null;
			status = Text.literal(loadoutMessage);
			layoutContent();
			return true;
		}
		int slot = slotAt((int) click.x(), (int) click.y(), contentX(), contentY());
		if (slot >= 0) {
			if (slot != headerTab()) {
				selectHeaderTab(slot);
				restartPageIn();
			}
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	/** The Auras tab: pick one (shown on you right away), equip it; Customize opens its settings. */
	private void layoutAuras() {
		String equipped = net.veloclient.velo.client.cosmetics.aura.AuraManager.equippedId();
		if (selectedAura == null) {
			selectedAura = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(selectedAura.isEmpty() ? null : selectedAura);
		int showcaseX = showcaseX();
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, contentBottom() - contentY() - 42).noStage().zoom(1.35f));
		boolean isEquipped = selectedAura.isEmpty() ? equipped == null : selectedAura.equals(equipped);
		boolean owned = selectedAura.isEmpty() || net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(selectedAura);
		VeloButton equip = new VeloButton(showcaseX + 8, contentBottom() - 28, SHOWCASE_WIDTH - 16, 20,
				Text.literal(selectedAura.isEmpty() ? (equipped == null ? "No aura" : "Take aura off") : isEquipped ? "Unequip"
						: owned ? "Equip" : "Get in the Store"), b -> {
					var type = net.veloclient.velo.client.cosmetics.aura.AuraManager.type(selectedAura);
					if (!owned) {
						type.ifPresent(t -> this.client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(this, t)));
						return;
					}
					if (selectedAura.isEmpty() || isEquipped) {
						net.veloclient.velo.client.cosmetics.aura.AuraManager.equip(null);
						selectedAura = "";
						status = Text.literal("Aura off");
					} else {
						net.veloclient.velo.client.cosmetics.aura.AuraManager.equip(selectedAura);
						status = Text.literal("Equipped " + type.map(net.veloclient.velo.client.cosmetics.aura.AuraManager.AuraType::name).orElse(selectedAura));
					}
					layoutContent();
				});
		if (!isEquipped && !selectedAura.isEmpty()) {
			equip.primary();
		}
		addDrawableChild(equip);

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		int y = contentY() + 20;
		int columns = Math.max(3, (listWidth + 8) / 128);
		int tileW = (listWidth - 6 - (columns - 1) * 8) / columns;
		java.util.List<String[]> tiles = new java.util.ArrayList<>();
		tiles.add(new String[] {"", "None"});
		for (var type : net.veloclient.velo.client.cosmetics.aura.AuraManager.catalog()) {
			if (net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(type.id()) || type.inStore()) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		int customizeY = contentBottom() - 20;
		startTileRegion(gridX, y, listWidth, customizeY - 8 - y, columns, tileW);
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i)[0];
			boolean tileOwned = id.isEmpty() || net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(id);
			addTile(new net.veloclient.velo.client.gui.widget.VeloCosmeticTile(0, 0, tileW, TILE_H, net.veloclient.velo.client.gui.widget.VeloCosmeticTile.Kind.AURA, id,
					tiles.get(i)[1], id.equals(selectedAura), id.equals(equipped == null ? "" : equipped), tileOwned, () -> {
						selectedAura = id;
						layoutContent();
					}));
		}
		finishTileRegion();
		if (!selectedAura.isEmpty()) {
			net.veloclient.velo.client.cosmetics.aura.AuraManager.type(selectedAura).ifPresent(type -> addDrawableChild(new VeloButton(gridX, customizeY,
					Math.min(200, listWidth), 20, Text.literal("Customize: size, speed, colour..."),
					b -> this.client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(this, type)))));
		}
	}

	/** The Wings tab: pick a pair (shown on you right away), equip it, and tune how you wear it. */
	private void layoutWings() {
		String equipped = net.veloclient.velo.client.cosmetics.wings.WingsManager.equippedId();
		if (selectedWings == null) {
			selectedWings = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(selectedWings.isEmpty() ? null : selectedWings);
		int showcaseX = showcaseX();
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, contentBottom() - contentY() - 42).noStage().zoom(1.35f));
		boolean isEquipped = selectedWings.isEmpty() ? equipped == null : selectedWings.equals(equipped);
		boolean owned = selectedWings.isEmpty() || net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(selectedWings);
		VeloButton equip = new VeloButton(showcaseX + 8, contentBottom() - 28, SHOWCASE_WIDTH - 16, 20,
				Text.literal(selectedWings.isEmpty() ? (equipped == null ? "No wings" : "Take wings off") : isEquipped ? "Unequip"
						: owned ? "Equip" : "Get in the Store"), b -> {
					if (!owned) {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.type(selectedWings).ifPresent(type ->
								this.client.setScreen(new net.veloclient.velo.client.gui.store.WingDetailScreen(this, type)));
						return;
					}
					if (selectedWings.isEmpty() || isEquipped) {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.equip(null);
						selectedWings = "";
						status = Text.literal("Wings off");
					} else {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.equip(selectedWings);
						status = Text.literal("Equipped " + net.veloclient.velo.client.cosmetics.wings.WingsManager.type(selectedWings)
								.map(net.veloclient.velo.client.cosmetics.wings.WingsManager.WingType::name).orElse(selectedWings));
					}
					layoutContent();
				});
		if (!isEquipped && !selectedWings.isEmpty()) {
			equip.primary();
		}
		addDrawableChild(equip);

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		int y = contentY() + 20;
		int columns = Math.max(3, (listWidth + 8) / 128);
		int tileW = (listWidth - 6 - (columns - 1) * 8) / columns;
		java.util.List<String[]> tiles = new java.util.ArrayList<>();
		tiles.add(new String[] {"", "None"});
		// What you own first, then what the Store sells (to try on).
		var wingTypes = net.veloclient.velo.client.cosmetics.wings.WingsManager.catalog();
		for (var type : wingTypes) {
			if (net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(type.id())) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		for (var type : wingTypes) {
			if (type.inStore() && !net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(type.id())) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		// The pair's settings stay pinned under the grid (the grid scrolls), so nothing runs out of the window.
		int settingsTop = contentBottom() - SETTINGS_H;
		startTileRegion(gridX, y, listWidth, settingsTop - 10 - y, columns, tileW);
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i)[0];
			boolean tileOwned = id.isEmpty() || net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(id);
			addTile(new net.veloclient.velo.client.gui.widget.VeloCosmeticTile(0, 0, tileW, TILE_H, net.veloclient.velo.client.gui.widget.VeloCosmeticTile.Kind.WINGS, id,
					tiles.get(i)[1], id.equals(selectedWings), id.equals(equipped == null ? "" : equipped), tileOwned, () -> {
						selectedWings = id;
						layoutContent();
					}));
		}
		finishTileRegion();
		y = settingsTop + 16;
		int half = (listWidth - 12) / 2;
		java.util.List<Object[]> sliders = java.util.List.of(
				new Object[] {"Size", 0.4, 2.0, 0, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"},
				new Object[] {"Height", -0.5, 0.5, 1, (java.util.function.DoubleFunction<String>) v -> String.format(java.util.Locale.ROOT, "%+d px", Math.round(v * 16))},
				new Object[] {"Distance from back", -0.2, 0.5, 2, (java.util.function.DoubleFunction<String>) v -> String.format(java.util.Locale.ROOT, "%+d px", Math.round(v * 16))},
				new Object[] {"Spread", -30.0, 60.0, 3, (java.util.function.DoubleFunction<String>) v -> Math.round(v) + "°"},
				new Object[] {"Flap speed", 0.25, 3.0, 4, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"},
				new Object[] {"Flap strength", 0.0, 2.0, 5, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"});
		for (int i = 0; i < sliders.size(); i++) {
			Object[] row = sliders.get(i);
			int field = (int) row[3];
			@SuppressWarnings("unchecked")
			java.util.function.DoubleFunction<String> format = (java.util.function.DoubleFunction<String>) row[4];
			addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloSlider(gridX + (i % 2) * (half + 12), y + (i / 2) * 24, half, 20,
					(String) row[0], (double) row[1], (double) row[2], () -> wingSetting(field),
					v -> setWingSetting(field, (float) v), v -> Math.round(v * 100) / 100.0, format));
		}
		Text reset = Text.literal("Reset");
		int resetW = this.textRenderer.getWidth(reset) + 20;
		addDrawableChild(new VeloButton(gridX + listWidth - resetW, settingsTop - 2, resetW, 14, reset, b -> {
			net.veloclient.velo.client.cosmetics.wings.WingsManager.setSettings(selectedWings, net.veloclient.velo.client.cosmetics.wings.WingsManager.Settings.DEFAULT);
			layoutContent();
		}));
	}

	private double wingSetting(int field) {
		var s = net.veloclient.velo.client.cosmetics.wings.WingsManager.settings(selectedWings);
		return switch (field) {
			case 0 -> s.scale();
			case 1 -> -s.offsetY();
			case 2 -> s.offsetZ();
			case 3 -> s.spread();
			case 4 -> s.flapSpeed();
			default -> s.flapStrength();
		};
	}

	/** Height is shown "up = +", the body's y axis points down. */
	private void setWingSetting(int field, float v) {
		if (selectedWings == null || selectedWings.isEmpty()) {
			return;
		}
		var s = net.veloclient.velo.client.cosmetics.wings.WingsManager.settings(selectedWings);
		net.veloclient.velo.client.cosmetics.wings.WingsManager.setSettings(selectedWings, new net.veloclient.velo.client.cosmetics.wings.WingsManager.Settings(
				field == 0 ? v : s.scale(), field == 1 ? -v : s.offsetY(), field == 2 ? v : s.offsetZ(), field == 3 ? v : s.spread(),
				field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength()));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		scrollRegion = null;
		if (aurasTab) {
			CapeManager.clearPreviewOverride();
			layoutAuras();
			return;
		}
		if (wingsTab) {
			CapeManager.clearPreviewOverride();
			layoutWings();
			return;
		}
		CapeManager.loadLibrary();
		String equippedId = CapeManager.equipped().map(CapeDefinition::id).orElse(null);
		if (selectedId == null || !CapeManager.library().containsKey(selectedId)) {
			selectedId = equippedId;
		}
		CapeDefinition selected = selectedId == null ? null : CapeManager.library().get(selectedId);
		// Preview the selected cape on your own player (cleared when the screen closes).
		if (selected != null && !selected.id().equals(equippedId)) {
			CapeManager.setPreviewOverride(selected);
		} else {
			CapeManager.clearPreviewOverride();
		}

		// Left: live preview + actions.
		int showcaseX = showcaseX();
		int actionsHeight = selected == null ? 0 : 20 + 6 + 20;
		int previewHeight = contentBottom() - contentY() - actionsHeight - (selected == null ? 0 : 34);
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, previewHeight - 6).noStage().zoom(1.35f));
		if (selected != null) {
			boolean isEquipped = selected.id().equals(equippedId);
			int buttonsY = contentBottom() - 54;
			VeloButton equip = new VeloButton(showcaseX + 8, buttonsY, SHOWCASE_WIDTH - 16, 20,
					Text.literal(isEquipped ? "Unequip" : "Equip"), b -> {
						if (isEquipped) {
							CapeManager.unequip();
							status = Text.literal("Unequipped \"" + selected.name() + "\"");
						} else {
							CapeManager.equip(selected.id());
							status = Text.literal("Equipped \"" + selected.name() + "\"");
						}
						layoutContent();
					});
			if (!isEquipped) {
				equip.primary();
			}
			addDrawableChild(equip);
			int half = (SHOWCASE_WIDTH - 16 - 6) / 2;
			addDrawableChild(new VeloButton(showcaseX + 8, buttonsY + 26, half, 20, Text.literal("Edit"),
					b -> this.client.setScreen(new CapeEditScreen(this, selected))));
			addDrawableChild(new VeloButton(showcaseX + 8 + half + 6, buttonsY + 26, half, 20, Text.literal("Delete"),
					b -> confirmDelete(selected)));
		}

		// Right: the collection.
		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		int gridTop = contentY() + 20;
		int gridHeight = contentBottom() - gridTop - 14;
		gridColumns = Math.max(3, (listWidth + TILE_GAP) / (104 + TILE_GAP));
		int capeTileW = (listWidth - 6 - (gridColumns - 1) * TILE_GAP) / gridColumns;
		int capeIconH = capeTileW * 5 / 4;
		scrollRegion = new VeloScrollRegion(gridX, gridTop, listWidth, gridHeight);
		regionTileW = capeTileW;
		regionTileH = capeIconH + VeloCapeTile.NAME_H;
		addTabs(gridX);

		for (CapeDefinition definition : CapeManager.library().values()) {
			boolean equipped = definition.id().equals(equippedId);
			VeloCapeTile tile = new VeloCapeTile(0, 0, capeTileW, capeIconH, definition, equipped,
					() -> this.client.setScreen(new CapeEditScreen(this, definition)),
					() -> {
						selectedId = definition.id();
						layoutContent();
					},
					this::confirmDelete).selected(definition.id().equals(selectedId));
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}

		VeloButton importTile = new VeloButton(0, 0, capeTileW, regionTileH, Text.literal("+ Import"), b -> pickFile());
		addSelectableChild(importTile);
		scrollRegion.addRow(importTile);

		scrollRegion.layoutGrid(gridColumns, regionTileW, regionTileH, TILE_GAP);
	}

	private void confirmDelete(CapeDefinition definition) {
		this.client.setScreen(new VeloConfirmScreen(this, "Delete cape",
				"Delete \"" + definition.name() + "\" from your cape library?", "Delete", () -> {
					CapeManager.delete(definition.id());
					status = Text.literal("Deleted \"" + definition.name() + "\"");
				}));
	}

	private void pickFile() {
		status = Text.literal("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path file = NativeFileDialog.pickPngFile("Choose a cape PNG");
				if (file == null) {
					MinecraftClient.getInstance().execute(() -> status = Text.literal(""));
					return;
				}
				String fileName = file.getFileName().toString();
				int dot = fileName.lastIndexOf('.');
				String name = dot > 0 ? fileName.substring(0, dot) : fileName;
				MinecraftClient.getInstance().execute(() -> {
					try {
						CapeManager.importCape(name, file, CapePhysicsPreset.defaults());
						status = Text.literal("Imported \"" + name + "\"");
						layoutContent();
					} catch (IOException e) {
						status = Text.literal("Import failed: " + e.getMessage());
					}
				});
			} catch (Throwable t) {
				net.veloclient.velo.VeloClient.LOGGER.error("Cape file picker failed to open", t);
				String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
				MinecraftClient.getInstance().execute(() -> status = Text.literal("File picker failed: " + message));
			}
		});
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layoutGrid(gridColumns, regionTileW, regionTileH, TILE_GAP);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbarGrid(context, gridColumns, regionTileH, TILE_GAP);
		}
		int gridX = gridX();
		// Heading over the collection: "Capes 12" + a quiet hint.
		String name = aurasTab ? "Auras" : wingsTab ? "Wings" : "Capes";
		int count = aurasTab ? (int) net.veloclient.velo.client.cosmetics.aura.AuraManager.catalog().stream()
				.filter(t -> net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(t.id())).count()
				: wingsTab ? net.veloclient.velo.client.cosmetics.wings.WingsManager.owned().size() : CapeManager.library().size();
		Text heading = MenuKit.display(name);
		MenuKit.text(context, this.textRenderer, heading, gridX, contentY() + 1, MenuKit.TEXT);
		Text countText = MenuKit.small(count + " OWNED");
		int hx = gridX + this.textRenderer.getWidth(heading) + 8;
		MenuKit.text(context, this.textRenderer, countText, hx, contentY() + 4, MenuKit.TEXT_3);
		if (wingsTab && selectedWings != null && !selectedWings.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.small("HOW YOU WEAR THIS PAIR"), gridX, contentBottom() - SETTINGS_H + 1, MenuKit.TEXT_3);
		}
		String hint = aurasTab ? "click one to try it on" : wingsTab ? "click a pair to try it on" : "click one to preview it on you";
		Text hintText = MenuKit.ui(hint);
		int right = contentX() + contentWidth();
		if (hx + this.textRenderer.getWidth(countText) + 16 + this.textRenderer.getWidth(hintText) <= right) {
			MenuKit.text(context, this.textRenderer, hintText, right - this.textRenderer.getWidth(hintText), contentY() + 2, MenuKit.TEXT_3);
		}
		if (!aurasTab && !wingsTab) {
			CapeDefinition selected = selectedId == null ? null : CapeManager.library().get(selectedId);
			if (selected != null) {
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.displaySm(selected.name()), SHOWCASE_WIDTH - 16),
						showcaseX() + 8, contentBottom() - 70, MenuKit.TEXT);
			}
		}
		if (!status.getString().isEmpty()) {
			// Feedback ("Equipped ...") at the top of the stage.
			Text line = MenuKit.fit(this.textRenderer, MenuKit.ui(status.getString()), SHOWCASE_WIDTH - 20);
			int lw = this.textRenderer.getWidth(line) + 14;
			int lx = showcaseX() + (SHOWCASE_WIDTH - lw) / 2;
			VeloDraw.fillRounded(context, lx, contentY() + 8, lw, 14, 7, 0x263DDC97);
			MenuKit.text(context, this.textRenderer, line, lx + 7, contentY() + 11, MenuKit.GREEN);
		}
	}
}
