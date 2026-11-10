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
public final class CapeEquipScreen extends VeloWindow implements net.veloclient.velo.client.gui.window.MenuPage {

	@Override
	public String railId() {
		return "skins";
	}


	private static final int TILE_WIDTH = 86;
	private static final int TILE_ICON_HEIGHT = 112;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_HEIGHT + 20;
	private static final int TILE_GAP = 8;
	public static final int SHOWCASE_WIDTH = 236;
	/** The slot list on the left (Cape / Wings / Aura, like the launcher's Locker). */
	public static final int SLOTS_W = 150;
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
	private boolean wearTab;
	/** Bodywear tab: the slot being browsed and the item picked (tried on). */
	private String wearSlot = "head";
	private String selectedWear;
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
	public static String[] LOCKER_TABS = {"Skin", "Cloaks", "Wings", "Auras", "Name", "Emotes", "Kill FX", "Wear"};
	/** The built-in tabs; extra ones (dev builds) come after them. */
	public static final int BUILT_IN_TABS = 8;

	/** A Locker tab added from outside the core (the dev build's Pets): its slot row and the screen it opens. */
	public record ExtraTab(String tab, String slotLabel, String icon, java.util.function.Supplier<String> value,
			java.util.function.Function<net.minecraft.client.gui.screen.Screen, net.minecraft.client.gui.screen.Screen> open) {
	}

	private static final java.util.List<ExtraTab> EXTRA_TABS = new java.util.ArrayList<>();

	/** Adds a tab after the built-in ones; returns its index. */
	public static int registerTab(ExtraTab tab) {
		EXTRA_TABS.add(tab);
		String[] tabs = java.util.Arrays.copyOf(LOCKER_TABS, LOCKER_TABS.length + 1);
		tabs[tabs.length - 1] = tab.tab();
		LOCKER_TABS = tabs;
		return tabs.length - 1;
	}
	/** The Emotes tab ({@link EmoteLockerScreen}: your emote wheel). */
	public static final int EMOTES_TAB = 5;
	/** The Kill FX tab ({@link KillFxLockerScreen}). */
	public static final int KILLFX_TAB = 6;
	/** The Bodywear tab (hats, ears, glasses, watches, shoes, backpacks). */
	public static final int WEAR_TAB = 7;

	/** Opens the Locker on a tab: 0 Skin, 1 Capes, 2 Wings, 3 Auras, 4 Name (Velo+ Name Styles), 5 Emotes, 6 Kill FX, 7 Bodywear. */
	public static net.minecraft.client.gui.screen.Screen open(net.minecraft.client.gui.screen.Screen parent, int tab) {
		if (tab >= BUILT_IN_TABS && tab - BUILT_IN_TABS < EXTRA_TABS.size()) {
			return EXTRA_TABS.get(tab - BUILT_IN_TABS).open().apply(parent);
		}
		if (tab == 0) {
			return new VeloSkinsScreen(parent);
		}
		if (tab == 4) {
			return new NameStyleScreen(parent);
		}
		if (tab == EMOTES_TAB) {
			return new EmoteLockerScreen(parent);
		}
		if (tab == KILLFX_TAB) {
			return new KillFxLockerScreen(parent);
		}
		CapeEquipScreen locker = new CapeEquipScreen(parent);
		locker.wingsTab = tab == 2;
		locker.aurasTab = tab == 3;
		locker.wearTab = tab == WEAR_TAB;
		return locker;
	}

	@Override
	protected String[] headerTabs() {
		// the kinds are the cards above the catalog now (launcher layout), not header tabs
		return new String[0];
	}

	@Override
	protected int headerTab() {
		return wearTab ? WEAR_TAB : aurasTab ? 3 : wingsTab ? 2 : 1;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index == 0 || index == 4 || index == EMOTES_TAB || index == KILLFX_TAB || index >= BUILT_IN_TABS) {
			this.client.setScreen(open(returnScreen(), index));
			return;
		}
		wingsTab = index == 2;
		aurasTab = index == 3;
		wearTab = index == WEAR_TAB;
		if (!wearTab) {
			net.veloclient.velo.client.cosmetics.wear.WearManager.clearPreview();
		}
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

	/** The pair settings' height: a fourth row (its colour) for a recolourable pair. */
	private int settingsHeight() {
		return selectedWings != null && net.veloclient.velo.client.cosmetics.wings.WingRigs.recolorable(selectedWings) ? SETTINGS_H + 24 : SETTINGS_H;
	}
	private int regionTileW = TILE_WIDTH;
	private int regionTileH = TILE_TOTAL_HEIGHT;

	private void startTileRegion(int x, int y, int w, int h, int columns, int tileW) {
		scrollRegion = new VeloScrollRegion(x, y, w, Math.max(TILE_H, h));
		gridColumns = columns;
		regionTileW = tileW;
		regionTileH = TILE_H;
	}

	/** Right-click menus of the tiles on the page (rebuilt with it). */
	private final java.util.Map<Object, java.util.function.Supplier<java.util.List<net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry>>> menus =
			new java.util.HashMap<>();

	private void addTile(net.minecraft.client.gui.widget.ClickableWidget tile) {
		addSelectableChild(tile);
		scrollRegion.addRow(tile);
	}

	private void finishTileRegion() {
		scrollRegion.layoutGrid(gridColumns, regionTileW, regionTileH, TILE_GAP);
	}

	private int showcaseX() {
		frame(contentX(), contentY(), contentWidth(), contentBottom());
		return stageX();
	}

	private int gridX() {
		frame(contentX(), contentY(), contentWidth(), contentBottom());
		return rightX();
	}

	/** The slot list: what you're wearing per slot; click one to open its tab. */
	private void renderSlots(DrawContext context, int mouseX, int mouseY) {
		drawSlots(context, this.textRenderer, contentX(), contentY(), headerTab(), mouseX, mouseY);
		drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);
	}

	// ---- 2026-11: the launcher's Locker layout, shared by every Locker tab (Skin, Emotes, Kill FX, Name style...) ----
	// Left: the 3D stage with the loadouts under it. Right: the kinds as a grid of slim cards on top, the catalog below.

	private static int frameX;
	private static int frameY;
	private static int frameW = 600;
	private static int frameBottom = 400;
	static final int KIND_COLS = 5;
	static final int KIND_H = 22;
	static final int KIND_GAP = 5;
	static final int LOADOUT_PANEL_H = 66;

	/** Every Locker screen calls this with its content box before laying out or drawing. */
	public static void frame(int contentX, int contentY, int contentWidth, int contentBottom) {
		frameX = contentX;
		frameY = contentY;
		frameW = contentWidth;
		frameBottom = contentBottom;
	}

	/** The stage: left column, full height above the loadouts panel. */
	public static int stageX() {
		return frameX;
	}

	public static int stageBottom() {
		return frameBottom - LOADOUT_PANEL_H - 8;
	}

	/** The catalog column right of the stage. */
	public static int rightX() {
		return frameX + SHOWCASE_WIDTH + 14;
	}

	public static int rightW() {
		return frameX + frameW - rightX();
	}

	private static int kindCount() {
		return LOCKER_TABS.length;
	}

	/** Where the catalog starts: under the kind cards. */
	public static int rightTop() {
		int rows = (kindCount() + KIND_COLS - 1) / KIND_COLS;
		return frameY + rows * (KIND_H + KIND_GAP) + 8;
	}

	private static int[] kindBox(int i) {
		int w = (rightW() - (KIND_COLS - 1) * KIND_GAP) / KIND_COLS;
		return new int[] {rightX() + i % KIND_COLS * (w + KIND_GAP), frameY + i / KIND_COLS * (KIND_H + KIND_GAP), w, KIND_H};
	}

	/** The kinds (Skin, Cloak, Wings...) as the launcher's slim cards above the catalog. {@code x, y} are ignored (see {@link #frame}). */
	public static void drawSlots(DrawContext context, net.minecraft.client.font.TextRenderer font, int x, int y, int activeTab, int mouseX, int mouseY) {
		armorShown = false;
		net.veloclient.velo.client.gui.window.VeloWindow.unshifted(context, () -> drawSlotsInPlace(context, font, activeTab, mouseX, mouseY));
	}

	private static String[][] kindRows() {
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
		var nameStyle = net.veloclient.velo.client.namestyle.NameStyles.own();
		String name = net.veloclient.velo.client.namestyle.NameStyles.ownEnabled() ? nameStyle.effect().label : "None";
		int onWheel = 0;
		for (int i = 0; i < net.veloclient.velo.client.emotes.EmoteCatalog.SLOTS; i++) {
			if (net.veloclient.velo.client.emotes.EmoteCatalog.slot(i) != null) {
				onWheel++;
			}
		}
		String emotes = onWheel == 0 ? "None" : onWheel + " on your wheel";
		String killFx = net.veloclient.velo.client.cosmetics.killfx.KillFxManager.name(net.veloclient.velo.client.cosmetics.killfx.KillFxManager.equippedId());
		int wornCount = 0;
		for (String s : net.veloclient.velo.client.cosmetics.wear.WearManager.SLOTS) {
			if (net.veloclient.velo.client.cosmetics.wear.WearManager.equipped(s) != null) {
				wornCount++;
			}
		}
		String wear = wornCount == 0 ? "None" : wornCount + (wornCount == 1 ? " item" : " items");
		String[][] rows = {{"Skin", skin == null || skin.isEmpty() ? "Your skin" : skin, "skins"}, {"Cloak", cape, "capes"},
				{"Wings", wings, "wings"}, {"Aura", aura, "bolt"}, {"Name style", name, "theme_editor"}, {"Emotes", emotes, "emotes"},
				{"Kill effect", killFx, "skull"}, {"Bodywear", wear, "hat"}};
		if (!EXTRA_TABS.isEmpty()) {
			String[][] all = java.util.Arrays.copyOf(rows, rows.length + EXTRA_TABS.size());
			for (int i = 0; i < EXTRA_TABS.size(); i++) {
				ExtraTab extra = EXTRA_TABS.get(i);
				String label = extra.slotLabel();
				label = label.isEmpty() ? label : label.charAt(0) + label.substring(1).toLowerCase(java.util.Locale.ROOT);
				all[rows.length + i] = new String[] {label, extra.value().get(), extra.icon()};
			}
			rows = all;
		}
		return rows;
	}

	private static void drawSlotsInPlace(DrawContext context, net.minecraft.client.font.TextRenderer font, int activeTab, int mouseX, int mouseY) {
		String[][] rows = kindRows();
		String tip = null;
		for (int i = 0; i < rows.length && i < kindCount(); i++) {
			int[] r = kindBox(i);
			boolean active = activeTab == i;
			boolean hovered = MenuKit.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
			VeloDraw.fillRounded(context, r[0], r[1], r[2], r[3], 5, active ? 0xFF1C1617 : hovered ? 0xFF1A1516 : 0xFF141011);
			VeloDraw.strokeRounded(context, r[0], r[1], r[2], r[3], 5, active ? VeloUi.withAlpha(MenuKit.accent(), 0xB0) : hovered ? 0x22FFFFFF : 0x10FFFFFF);
			boolean none = rows[i][1].equals("None");
			MenuKit.icon(context, rows[i][2], r[0] + 7, r[1] + 5, 12, active ? MenuKit.accentText() : none ? MenuKit.TEXT_3 : 0xFFB3A8A9);
			MenuKit.text(context, font, MenuKit.fit(font, MenuKit.uiBold(rows[i][0]), r[2] - 28), r[0] + 24, r[1] + 7,
					active ? 0xFFFFFFFF : hovered ? MenuKit.TEXT : 0xFFB3A8A9);
			if (hovered) {
				tip = rows[i][1];
			}
		}
		if (tip != null) {
			// what's worn in that slot, as the launcher's tooltip
			Text t = MenuKit.ui(tip);
			int tw = MenuKit.width(font, t) + 12;
			int tx = Math.min(mouseX + 8, rightX() + rightW() - tw);
			int ty = mouseY + 12;
			VeloDraw.fillRounded(context, tx, ty, tw, 16, 4, 0xF0141011);
			VeloDraw.strokeRounded(context, tx, ty, tw, 16, 4, 0x22FFFFFF);
			MenuKit.text(context, font, t, tx + 6, ty + 4, MenuKit.TEXT);
		}
	}

	// ---- Loadouts (whole looks, shared with the launcher's Locker): a panel of chips under the stage ----

	static final int LOADOUT_H = 16;

	/** Chip boxes {x, y, w, h, index} for the looks that fit, then the "+ Save current" chip (index -1). */
	private static java.util.List<int[]> loadoutChips(net.minecraft.client.font.TextRenderer font) {
		var looks = net.veloclient.velo.client.cosmetics.Loadouts.all();
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int left = stageX() + 8;
		int right = stageX() + SHOWCASE_WIDTH - 8;
		int x = left;
		int y = stageBottom() + 8 + 26;
		int maxY = frameBottom - 4;
		for (int i = 0; i <= looks.size(); i++) {
			String label = i < looks.size() ? looks.get(i).name() : "+ Save current";
			int w = Math.min(right - left, MenuKit.width(font, MenuKit.uiBold(label)) + 14);
			if (x + w > right) {
				x = left;
				y += LOADOUT_H + 4;
			}
			if (y + LOADOUT_H > maxY) {
				break;
			}
			out.add(new int[] {x, y, w, LOADOUT_H, i < looks.size() ? i : -1});
			x += w + 4;
		}
		return out;
	}

	/** The stage's top-left switch: "Armor" on/off for the preview (launcher's Full look / Cloak only spot). */
	private static int[] armorBox(net.minecraft.client.font.TextRenderer font) {
		int w = MenuKit.width(font, MenuKit.uiBold("Armor")) + 30;
		return new int[] {stageX() + 8, frameY + 8, w, 16};
	}

	private static boolean armorShown;

	/** Drawn by the Locker tabs that show your player (after their stage), so it sits on top. */
	public static void drawArmorToggle(DrawContext context, net.minecraft.client.font.TextRenderer font, int mouseX, int mouseY) {
		armorShown = true;
		int[] r = armorBox(font);
		boolean on = !net.veloclient.velo.client.gui.widget.EntityPreviewWidget.hideArmor;
		boolean hovered = MenuKit.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
		VeloDraw.fillRounded(context, r[0], r[1], r[2], r[3], 5, hovered ? 0xFF241D1E : 0xFF1A1516);
		VeloDraw.strokeRounded(context, r[0], r[1], r[2], r[3], 5, 0x18FFFFFF);
		MenuKit.text(context, font, MenuKit.uiBold("Armor"), r[0] + 7, r[1] + 4, on ? MenuKit.TEXT : 0xFF8E8384);
		net.veloclient.velo.client.gui.widget.VeloToggle.drawSwitch(context, r[0] + r[2] - 19, r[1] + 4, 14, 8, on ? 1f : 0f, hovered ? 1f : 0f);
	}

	private static boolean armorToggleClick(double mouseX, double mouseY) {
		if (!armorShown) {
			return false;
		}
		int[] r = armorBox(net.minecraft.client.MinecraftClient.getInstance().textRenderer);
		if (MenuKit.inside((int) mouseX, (int) mouseY, r[0], r[1], r[2], r[3])) {
			net.veloclient.velo.client.gui.widget.EntityPreviewWidget.hideArmor = !net.veloclient.velo.client.gui.widget.EntityPreviewWidget.hideArmor;
			net.veloclient.velo.client.sound.VeloUiSounds.toggle(!net.veloclient.velo.client.gui.widget.EntityPreviewWidget.hideArmor);
			return true;
		}
		return false;
	}

	public static void drawLoadouts(DrawContext context, net.minecraft.client.font.TextRenderer font, int x, int y, int bottom, int mouseX, int mouseY) {
		var looks = net.veloclient.velo.client.cosmetics.Loadouts.all();
		int px = stageX();
		int py = stageBottom() + 8;
		VeloDraw.fillRounded(context, px, py, SHOWCASE_WIDTH, frameBottom - py, 8, 0xFF100D0D);
		VeloDraw.strokeRounded(context, px, py, SHOWCASE_WIDTH, frameBottom - py, 8, 0x10FFFFFF);
		MenuKit.text(context, font, MenuKit.uiBold("Loadouts"), px + 10, py + 9, MenuKit.TEXT);
		for (int[] c : loadoutChips(font)) {
			boolean hovered = MenuKit.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
			boolean worn = c[4] >= 0 && looks.get(c[4]).isWorn();
			String label = c[4] >= 0 ? looks.get(c[4]).name() : "+ Save current";
			VeloDraw.fillRounded(context, c[0], c[1], c[2], c[3], 8, worn ? 0xFF2A2324 : hovered ? 0xFF1E1819 : 0x00000000);
			VeloDraw.strokeRounded(context, c[0], c[1], c[2], c[3], 8, worn ? VeloUi.withAlpha(MenuKit.accent(), 0x90) : hovered ? 0x30FFFFFF : 0x1CFFFFFF);
			MenuKit.text(context, font, MenuKit.fit(font, MenuKit.uiBold(label), c[2] - 12), c[0] + 7, c[1] + 4, worn || hovered ? MenuKit.TEXT : 0xFFB3A8A9);
		}
	}

	/** What the last loadout click did ("Wearing Purple", "Couldn't put on: ..."), for the screen's status line. */
	static String loadoutMessage = "";

	/** A click on the loadouts: wear (left), update (right), delete (middle click) or save. True if it was one. */
	public static boolean loadoutClick(double mouseX, double mouseY, int button, int x, int y, int bottom) {
		if (armorToggleClick(mouseX, mouseY)) {
			return true;
		}
		var looks = net.veloclient.velo.client.cosmetics.Loadouts.all();
		for (int[] c : loadoutChips(net.minecraft.client.MinecraftClient.getInstance().textRenderer)) {
			if (!MenuKit.inside((int) mouseX, (int) mouseY, c[0], c[1], c[2], c[3])) {
				continue;
			}
			if (c[4] < 0) {
				loadoutMessage = "Saved \"" + net.veloclient.velo.client.cosmetics.Loadouts.saveCurrent() + "\"";
				return true;
			}
			var look = looks.get(c[4]);
			if (button == 1) {
				net.veloclient.velo.client.cosmetics.Loadouts.update(look.name());
				loadoutMessage = "Updated \"" + look.name() + "\"";
			} else if (button == 2) {
				net.veloclient.velo.client.cosmetics.Loadouts.delete(look.name());
				loadoutMessage = "Deleted \"" + look.name() + "\"";
			} else {
				var missing = net.veloclient.velo.client.cosmetics.Loadouts.wear(look);
				loadoutMessage = missing.isEmpty() ? "Wearing \"" + look.name() + "\"" : "Couldn't put on: " + String.join(", ", missing);
			}
			return true;
		}
		return false;
	}

	/** Which kind card is under the mouse (-1 = none). {@code x, y} are ignored (see {@link #frame}). */
	public static int slotAt(int mouseX, int mouseY, int x, int y) {
		for (int i = 0; i < kindCount(); i++) {
			int[] r = kindBox(i);
			if (MenuKit.inside(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
				net.veloclient.velo.client.gui.window.VeloWindow.noteSlotClick();
				return i;
			}
		}
		return -1;
	}

	/** The stage behind the live preview. */
	private void renderStage(DrawContext context) {
		int x = showcaseX();
		int y = contentY();
		int h = stageBottom() - y;
		// launcher stage: the same dark panel with a soft light on the floor under the player
		VeloDraw.fillRounded(context, x, y, SHOWCASE_WIDTH, h, 8, 0xFF100D0D);
		VeloDraw.strokeRounded(context, x, y, SHOWCASE_WIDTH, h, 8, 0x10FFFFFF);
		int fy = y + h - 46;
		VeloDraw.fillRoundedGradient(context, x + SHOWCASE_WIDTH / 6, fy, SHOWCASE_WIDTH * 2 / 3, 18, 9, 0x00000000, VeloUi.withAlpha(MenuKit.accent(), 0x18));
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		frame(contentX(), contentY(), contentWidth(), contentBottom());
		renderStage(context);
		renderSlots(context, mouseX, mouseY);
		drawArmorToggle(context, this.textRenderer, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (net.veloclient.velo.client.gui.widget.VeloContextMenu.click(this, click.x(), click.y())) {
			return true;
		}
		if (click.button() == 1 && scrollRegion != null) {
			for (var row : scrollRegion.rowsSnapshot()) {
				var menu = menus.get(row);
				if (menu != null && row.isMouseOver(click.x(), click.y())) {
					net.veloclient.velo.client.gui.widget.VeloContextMenu.open(this, click.x(), click.y(), menu.get());
					return true;
				}
			}
		}
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

	/**
	 * The Bodywear tab: pick a slot, then an item (tried on at once); put it on, take it off or buy it; a colour slider
	 * for recolourable items you own. Every slot is worn at the same time.
	 */
	private void layoutWear() {
		String equipped = net.veloclient.velo.client.cosmetics.wear.WearManager.equipped(wearSlot);
		if (selectedWear == null) {
			selectedWear = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.wear.WearManager.setPreview(wearSlot, selectedWear.isEmpty() ? null : selectedWear);
		int showcaseX = showcaseX();
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, stageBottom() - contentY() - 12).noStage().zoom(1.35f));
		boolean isEquipped = selectedWear.isEmpty() ? equipped == null : selectedWear.equals(equipped);
		boolean owned = selectedWear.isEmpty() || net.veloclient.velo.client.cosmetics.wear.WearManager.owns(selectedWear);
		var selectedType = net.veloclient.velo.client.cosmetics.wear.WearManager.type(selectedWear);
		VeloButton equip = new VeloButton(showcaseX + 8, stageBottom() - 28, SHOWCASE_WIDTH - 16, 20,
				Text.literal(selectedWear.isEmpty() ? (equipped == null ? "Nothing here" : "Take it off") : isEquipped ? "Take it off"
						: owned ? "Wear it" : "Get in the Store"), b -> {
					if (!owned) {
						selectedType.ifPresent(t -> this.client.setScreen(new net.veloclient.velo.client.gui.store.WearDetailScreen(this, t)));
						return;
					}
					if (selectedWear.isEmpty() || isEquipped) {
						net.veloclient.velo.client.cosmetics.wear.WearManager.equip(wearSlot, null);
						selectedWear = "";
						status = Text.literal("Taken off");
					} else {
						net.veloclient.velo.client.cosmetics.wear.WearManager.equip(wearSlot, selectedWear);
						status = Text.literal("Wearing " + selectedType.map(net.veloclient.velo.client.cosmetics.wear.WearManager.WearType::name).orElse(selectedWear));
					}
					layoutContent();
				});
		if (!isEquipped && !selectedWear.isEmpty()) {
			equip.primary();
		}
		// launcher-style: no Equip button - one click on a tile wears it, right click has the rest

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		// The slots as chips across the top.
		int chipX = gridX;
		int y = rightTop() + 20;
		String[] labels = {"Head", "Ears", "Face", "Masks", "Neck", "Wrist", "Feet", "Back"};
		for (int i = 0; i < labels.length; i++) {
			String slot = net.veloclient.velo.client.cosmetics.wear.WearManager.SLOTS.get(i);
			Text label = Text.literal(labels[i]);
			int w = this.textRenderer.getWidth(label) + 12;
			if (chipX > gridX && chipX + w > gridX + listWidth) {
				// out of room: the next row
				chipX = gridX;
				y += 20;
			}
			VeloButton chip = new VeloButton(chipX, y, w, 16, label, b -> {
				wearSlot = slot;
				selectedWear = null;
				layoutContent();
			});
			if (slot.equals(wearSlot)) {
				chip.primary();
			}
			addDrawableChild(chip);
			chipX += w + 4;
		}
		y += 24;
		int columns = Math.max(3, (listWidth + 8) / 128);
		int tileW = (listWidth - (columns - 1) * 8) / columns;
		java.util.List<String[]> tiles = new java.util.ArrayList<>();
		tiles.add(new String[] {"", "None"});
		java.util.List<net.veloclient.velo.client.cosmetics.wear.WearManager.WearType> items = new java.util.ArrayList<>();
		for (var type : net.veloclient.velo.client.cosmetics.wear.WearManager.all()) {
			if (type.slot().equals(wearSlot) && (net.veloclient.velo.client.cosmetics.wear.WearManager.owns(type.id()) || type.inStore())) {
				items.add(type);
			}
		}
		// Owned first, then the Store's.
		items.sort(java.util.Comparator.comparing((net.veloclient.velo.client.cosmetics.wear.WearManager.WearType type) ->
				!net.veloclient.velo.client.cosmetics.wear.WearManager.owns(type.id())).thenComparing(net.veloclient.velo.client.cosmetics.wear.WearManager.WearType::name));
		for (var type : items) {
			tiles.add(new String[] {type.id(), type.name()});
		}
		boolean tintable = !selectedWear.isEmpty() && owned && selectedType.map(net.veloclient.velo.client.cosmetics.wear.WearManager.WearType::tintable).orElse(false);
		boolean height = !selectedWear.isEmpty() && owned && net.veloclient.velo.client.cosmetics.wear.WearManager.adjustable(wearSlot);
		boolean depth = height && net.veloclient.velo.client.cosmetics.wear.WearManager.depthAdjustable(wearSlot);
		int settingsTop = contentBottom() - (tintable ? 8 + net.veloclient.velo.client.gui.widget.VeloColorSwatches.heightFor(listWidth) : 0) - (height ? 26 : 0);
		startTileRegion(gridX, y, listWidth, settingsTop - 10 - y, columns, tileW);
		for (String[] tile : tiles) {
			String id = tile[0];
			boolean tileOwned = id.isEmpty() || net.veloclient.velo.client.cosmetics.wear.WearManager.owns(id);
			var wearTile = new net.veloclient.velo.client.gui.widget.VeloCosmeticTile(0, 0, tileW, TILE_H, net.veloclient.velo.client.gui.widget.VeloCosmeticTile.Kind.WEAR, id,
					tile[1], id.equals(selectedWear), id.equals(equipped == null ? "" : equipped), tileOwned, () -> {
						// one click: wear it (None takes the slot off); not yours yet = just try it on
						selectedWear = id;
						if (tileOwned) {
							net.veloclient.velo.client.cosmetics.wear.WearManager.equip(wearSlot, id.isEmpty() ? null : id);
							status = Text.literal(id.isEmpty() ? "Taken off" : "Wearing " + tile[1]);
						}
						layoutContent();
					});
			addTile(wearTile);
			String slotNow = wearSlot;
			menus.put(wearTile, () -> {
				java.util.List<net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry> menuItems = new java.util.ArrayList<>();
				boolean on = id.equals(equipped == null ? "" : equipped);
				if (id.isEmpty()) {
					return menuItems;
				}
				if (tileOwned) {
					menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry(on ? "Take it off" : "Wear it", false, () -> {
						net.veloclient.velo.client.cosmetics.wear.WearManager.equip(slotNow, on ? null : id);
						selectedWear = on ? "" : id;
						layoutContent();
					}));
				} else {
					net.veloclient.velo.client.cosmetics.wear.WearManager.type(id).ifPresent(t -> menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry("Get it in the Store", false,
							() -> this.client.setScreen(new net.veloclient.velo.client.gui.store.WearDetailScreen(this, t)))));
				}
				return menuItems;
			});
		}
		finishTileRegion();
		if (tintable) {
			String id = selectedWear;
			// Colour: its own, a quick pick or any colour from the picker.
			addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloColorSwatches(gridX, settingsTop + 4, listWidth,
					() -> net.veloclient.velo.client.cosmetics.wear.WearManager.color(id),
					code -> net.veloclient.velo.client.cosmetics.wear.WearManager.setColor(id, code)));
		}
		if (height) {
			if (depth) {
				int half = (listWidth - 8) / 2;
				addDrawableChild(heightSlider(gridX, contentBottom() - 20, half, selectedWear));
				addDrawableChild(depthSlider(gridX + half + 8, contentBottom() - 20, listWidth - half - 8, selectedWear));
			} else {
				addDrawableChild(heightSlider(gridX, contentBottom() - 20, listWidth, selectedWear));
			}
		}
	}

	/** Glasses and masks: how far out from the face, in quarter pixels. */
	public static net.veloclient.velo.client.gui.widget.VeloSlider depthSlider(int x, int y, int width, String id) {
		int max = net.veloclient.velo.client.cosmetics.wear.WearManager.MAX_OFFSET;
		return new net.veloclient.velo.client.gui.widget.VeloSlider(x, y, width, 20, "Distance", -max / 4.0, max / 4.0,
				() -> net.veloclient.velo.client.cosmetics.wear.WearManager.depth(id) / 4.0,
				v -> net.veloclient.velo.client.cosmetics.wear.WearManager.setDepth(id, (int) Math.round(v * 4)),
				v -> Math.round(v * 4) / 4.0, v -> v == 0 ? "As made" : (v > 0 ? "+" : "") + v + " px");
	}

	/** Glasses, masks and back items: how high they sit, in quarter pixels. */
	public static net.veloclient.velo.client.gui.widget.VeloSlider heightSlider(int x, int y, int width, String id) {
		int max = net.veloclient.velo.client.cosmetics.wear.WearManager.MAX_OFFSET;
		return new net.veloclient.velo.client.gui.widget.VeloSlider(x, y, width, 20, "Height", -max / 4.0, max / 4.0,
				() -> -net.veloclient.velo.client.cosmetics.wear.WearManager.offset(id) / 4.0,
				v -> net.veloclient.velo.client.cosmetics.wear.WearManager.setOffset(id, (int) -Math.round(v * 4)),
				v -> Math.round(v * 4) / 4.0, v -> v == 0 ? "As made" : (v > 0 ? "+" : "") + v + " px");
	}

	/** The Auras tab: pick one (shown on you right away), equip it; Customize opens its settings. */
	private void layoutAuras() {
		String equipped = net.veloclient.velo.client.cosmetics.aura.AuraManager.equippedId();
		if (selectedAura == null) {
			selectedAura = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(selectedAura.isEmpty() ? null : selectedAura);
		int showcaseX = showcaseX();
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, stageBottom() - contentY() - 12).noStage().zoom(1.35f));
		boolean isEquipped = selectedAura.isEmpty() ? equipped == null : selectedAura.equals(equipped);
		boolean owned = selectedAura.isEmpty() || net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(selectedAura);
		VeloButton equip = new VeloButton(showcaseX + 8, stageBottom() - 28, SHOWCASE_WIDTH - 16, 20,
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
		// launcher-style: no Equip button - one click on a tile wears it, right click has the rest

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		int y = rightTop() + 20;
		int columns = Math.max(3, (listWidth + 8) / 128);
		int tileW = (listWidth - 6 - (columns - 1) * 8) / columns;
		java.util.List<String[]> tiles = new java.util.ArrayList<>();
		tiles.add(new String[] {"", "None"});
		for (var type : net.veloclient.velo.client.cosmetics.aura.AuraManager.catalog()) {
			if (net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(type.id()) || type.inStore()) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		startTileRegion(gridX, y, listWidth, contentBottom() - y, columns, tileW);
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i)[0];
			boolean tileOwned = id.isEmpty() || net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(id);
			String auraName = tiles.get(i)[1];
			var auraTile = new net.veloclient.velo.client.gui.widget.VeloCosmeticTile(0, 0, tileW, TILE_H, net.veloclient.velo.client.gui.widget.VeloCosmeticTile.Kind.AURA, id,
					auraName, id.equals(selectedAura), id.equals(equipped == null ? "" : equipped), tileOwned, () -> {
						selectedAura = id;
						if (tileOwned) {
							net.veloclient.velo.client.cosmetics.aura.AuraManager.equip(id.isEmpty() ? null : id);
							status = Text.literal(id.isEmpty() ? "Aura off" : "Equipped " + auraName);
						}
						layoutContent();
					});
			addTile(auraTile);
			menus.put(auraTile, () -> {
				java.util.List<net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry> menuItems = new java.util.ArrayList<>();
				if (id.isEmpty()) {
					return menuItems;
				}
				var type = net.veloclient.velo.client.cosmetics.aura.AuraManager.type(id);
				boolean on = id.equals(equipped == null ? "" : equipped);
				if (tileOwned) {
					menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry(on ? "Take it off" : "Equip", false, () -> {
						net.veloclient.velo.client.cosmetics.aura.AuraManager.equip(on ? null : id);
						selectedAura = on ? "" : id;
						layoutContent();
					}));
					type.ifPresent(t -> menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry("Customize (size, speed, colour)", false,
							() -> this.client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(this, t)))));
				} else {
					type.ifPresent(t -> menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry("Get it in the Store", false,
							() -> this.client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(this, t)))));
				}
				return menuItems;
			});
		}
		finishTileRegion();
		// Customize lives in the right-click menu now (no extra button under the grid).
	}

	/** The Wings tab: pick a pair (shown on you right away), equip it, and tune how you wear it. */
	private void layoutWings() {
		String equipped = net.veloclient.velo.client.cosmetics.wings.WingsManager.equippedId();
		if (selectedWings == null) {
			selectedWings = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(selectedWings.isEmpty() ? null : selectedWings);
		int showcaseX = showcaseX();
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, stageBottom() - contentY() - 12).noStage().zoom(1.35f));
		boolean isEquipped = selectedWings.isEmpty() ? equipped == null : selectedWings.equals(equipped);
		boolean owned = selectedWings.isEmpty() || net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(selectedWings);
		VeloButton equip = new VeloButton(showcaseX + 8, stageBottom() - 28, SHOWCASE_WIDTH - 16, 20,
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
		// launcher-style: no Equip button - one click on a tile wears it, right click has the rest

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		int y = rightTop() + 20;
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
		int settingsTop = contentBottom() - settingsHeight();
		startTileRegion(gridX, y, listWidth, settingsTop - 10 - y, columns, tileW);
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i)[0];
			boolean tileOwned = id.isEmpty() || net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(id);
			String wingName = tiles.get(i)[1];
			var wingTile = new net.veloclient.velo.client.gui.widget.VeloCosmeticTile(0, 0, tileW, TILE_H, net.veloclient.velo.client.gui.widget.VeloCosmeticTile.Kind.WINGS, id,
					wingName, id.equals(selectedWings), id.equals(equipped == null ? "" : equipped), tileOwned, () -> {
						selectedWings = id;
						if (tileOwned) {
							net.veloclient.velo.client.cosmetics.wings.WingsManager.equip(id.isEmpty() ? null : id);
							status = Text.literal(id.isEmpty() ? "Wings off" : "Equipped " + wingName);
						}
						layoutContent();
					});
			addTile(wingTile);
			menus.put(wingTile, () -> {
				java.util.List<net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry> menuItems = new java.util.ArrayList<>();
				if (id.isEmpty()) {
					return menuItems;
				}
				boolean on = id.equals(equipped == null ? "" : equipped);
				if (tileOwned) {
					menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry(on ? "Take them off" : "Equip", false, () -> {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.equip(on ? null : id);
						selectedWings = on ? "" : id;
						layoutContent();
					}));
				} else {
					net.veloclient.velo.client.cosmetics.wings.WingsManager.type(id).ifPresent(t -> menuItems.add(new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry("Get them in the Store", false,
							() -> this.client.setScreen(new net.veloclient.velo.client.gui.store.WingDetailScreen(this, t)))));
				}
				return menuItems;
			});
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
		if (net.veloclient.velo.client.cosmetics.wings.WingRigs.recolorable(selectedWings)) {
			// Colour: the hues round the wheel, then white, then a cycling rainbow.
			// Pairs with their own colour (grey art, e.g. Chain Wings) start the slider at "Own".
			boolean own = net.veloclient.velo.client.cosmetics.wings.WingRigs.ownTint(selectedWings) >= 0;
			addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloSlider(gridX, y + 3 * 24, listWidth, 20, "Colour", own ? 0 : 1,
					net.veloclient.velo.client.cosmetics.wings.WingRigs.TINT_RAINBOW, () -> {
						int code = net.veloclient.velo.client.cosmetics.wings.WingsManager.settings(selectedWings).color();
						return code <= 0 ? own ? 0 : net.veloclient.velo.client.cosmetics.wings.WingRigs.TINT_WHITE : code;
					}, v -> net.veloclient.velo.client.cosmetics.wings.WingsManager.setSettings(selectedWings,
							net.veloclient.velo.client.cosmetics.wings.WingsManager.settings(selectedWings).withColor((int) Math.round(v))),
					v -> (double) Math.round(v), v -> v >= net.veloclient.velo.client.cosmetics.wings.WingRigs.TINT_RAINBOW ? "Rainbow"
							: v >= net.veloclient.velo.client.cosmetics.wings.WingRigs.TINT_WHITE ? "White" : v < 0.5 ? "Own" : Math.round(v - 1) + "°"));
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
				field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength(), s.color()));
	}

	@Override
	protected void layoutContent() {
		frame(contentX(), contentY(), contentWidth(), contentBottom());
		menus.clear();
		this.clearChildren();
		scrollRegion = null;
		if (aurasTab) {
			CapeManager.clearPreviewOverride();
			layoutAuras();
			return;
		}
		if (wearTab) {
			CapeManager.clearPreviewOverride();
			layoutWear();
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

		// Left: the live preview (one click on a cloak wears it; right click: edit, delete, take off).
		int showcaseX = showcaseX();
		addDrawableChild(new EntityPreviewWidget(showcaseX + 6, contentY() + 6, SHOWCASE_WIDTH - 12, stageBottom() - contentY() - 12).noStage().zoom(1.35f));

		// Right: the collection.
		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		int gridTop = rightTop() + 20;
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
						if (!equipped) {
							CapeManager.equip(definition.id());
							status = Text.literal("Equipped \"" + definition.name() + "\"");
						}
						layoutContent();
					},
					this::confirmDelete).selected(definition.id().equals(selectedId));
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
			menus.put(tile, () -> java.util.List.of(
					new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry(equipped ? "Take it off" : "Equip", false, () -> {
						if (equipped) {
							CapeManager.unequip();
							status = Text.literal("Unequipped \"" + definition.name() + "\"");
						} else {
							CapeManager.equip(definition.id());
							status = Text.literal("Equipped \"" + definition.name() + "\"");
						}
						selectedId = definition.id();
						layoutContent();
					}),
					new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry("Edit name & physics", false, () -> this.client.setScreen(new CapeEditScreen(this, definition))),
					new net.veloclient.velo.client.gui.widget.VeloContextMenu.Entry("Delete", true, () -> confirmDelete(definition))));
		}

		VeloButton importTile = new VeloButton(0, 0, capeTileW, regionTileH, Text.literal("+ Your own cloak"), b -> pickFile());
		addSelectableChild(importTile);
		scrollRegion.addRow(importTile);

		scrollRegion.layoutGrid(gridColumns, regionTileW, regionTileH, TILE_GAP);
	}

	private void confirmDelete(CapeDefinition definition) {
		this.client.setScreen(new VeloConfirmScreen(this, "Delete cloak",
				"Delete \"" + definition.name() + "\" from your cloak library?", "Delete", () -> {
					CapeManager.delete(definition.id());
					status = Text.literal("Deleted \"" + definition.name() + "\"");
				}));
	}

	private void pickFile() {
		status = Text.literal("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				// Any picture works: a cloak texture is used as it is, anything else is turned into a cloak.
				// Velo+ members keep GIFs animated.
				boolean plus = net.veloclient.velo.client.namestyle.NameStyles.plus();
				Path file = NativeFileDialog.pickPictureFile("Upload your own cloak - a cloak texture or any picture");
				if (file == null) {
					MinecraftClient.getInstance().execute(() -> status = Text.literal(""));
					return;
				}
				MinecraftClient.getInstance().execute(() -> status = Text.literal("Making your cloak..."));
				String fileName = file.getFileName().toString();
				int dot = fileName.lastIndexOf('.');
				String name = dot > 0 ? fileName.substring(0, dot) : fileName;
				net.veloclient.velo.client.cosmetics.CapeUpload.Prepared prepared;
				try {
					prepared = net.veloclient.velo.client.cosmetics.CapeUpload.prepare(file, plus);
				} catch (IOException e) {
					MinecraftClient.getInstance().execute(() -> status = Text.literal("Upload failed: " + e.getMessage()));
					return;
				}
				MinecraftClient.getInstance().execute(() -> {
					try {
						if (prepared.gif() != null) {
							CapeManager.importAnimatedGif(name, new java.io.ByteArrayInputStream(prepared.gif()), CapePhysicsPreset.defaults());
						} else {
							CapeManager.importCape(name, prepared.png(), CapePhysicsPreset.defaults());
							java.nio.file.Files.deleteIfExists(prepared.png());
						}
						status = Text.literal(prepared.message());
						layoutContent();
					} catch (IOException e) {
						status = Text.literal("Upload failed: " + e.getMessage());
					}
				});
			} catch (Throwable t) {
				net.veloclient.velo.VeloClient.LOGGER.error("Cloak file picker failed to open", t);
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
		// Heading over the collection: "Cloaks 12" + a quiet hint.
		String name = aurasTab ? "Auras" : wingsTab ? "Wings" : "Cloaks";
		int count = aurasTab ? (int) net.veloclient.velo.client.cosmetics.aura.AuraManager.catalog().stream()
				.filter(t -> net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(t.id())).count()
				: wingsTab ? net.veloclient.velo.client.cosmetics.wings.WingsManager.owned().size() : CapeManager.library().size();
		Text heading = MenuKit.display(name);
		MenuKit.text(context, this.textRenderer, heading, gridX, rightTop() + 1, MenuKit.TEXT);
		Text countText = MenuKit.small(count + " OWNED");
		int hx = gridX + this.textRenderer.getWidth(heading) + 8;
		MenuKit.text(context, this.textRenderer, countText, hx, rightTop() + 4, MenuKit.TEXT_3);
		if (wingsTab && selectedWings != null && !selectedWings.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.small("HOW YOU WEAR THIS PAIR"), gridX, contentBottom() - settingsHeight() + 1, MenuKit.TEXT_3);
		}
		String hint = aurasTab || wingsTab || wearTab ? "click to wear  ·  right-click for more" : "click to wear  ·  right-click: edit, delete";
		Text hintText = MenuKit.ui(hint);
		int right = contentX() + contentWidth();
		if (hx + this.textRenderer.getWidth(countText) + 16 + this.textRenderer.getWidth(hintText) <= right) {
			MenuKit.text(context, this.textRenderer, hintText, right - this.textRenderer.getWidth(hintText), rightTop() + 2, MenuKit.TEXT_3);
		}
		if (!aurasTab && !wingsTab) {
			CapeDefinition selected = selectedId == null ? null : CapeManager.library().get(selectedId);
			if (selected != null) {
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.displaySm(selected.name()), SHOWCASE_WIDTH - 16),
						showcaseX() + 8, stageBottom() - 70, MenuKit.TEXT);
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
