package net.veloclient.velo.client.gui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.title.QuickToggles;
import net.veloclient.velo.client.gui.widget.ModuleIcons;
import net.veloclient.velo.client.gui.widget.ModuleTags;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloModuleCard;
import net.veloclient.velo.client.gui.widget.VeloNavIcons;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.ModuleConfigScreen;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The Velo menu (R-Shift), 0.9.8 layout:
 * <ul>
 * <li>an icon rail on the left: Modules, Addons (only when installed), then everything else Velo has (Locker, Skins, Friends, Clips,
 * Fight Recap, World Map, Waypoints, Background Queue, Schematics) and Settings, Redstone Mode and Profiles at the
 * bottom - names show on hover;</li>
 * <li>on top: the profile you're on (click to switch), search, and Edit HUD as the page's one big button;</li>
 * <li>category tabs (Addons: their groups) and four columns of slim module rows - icon, name, tag, switch. One click
 * opens the module's settings page with its live preview (2026-11 redesign: the side panel nobody used is gone).</li>
 * </ul>
 * Opens with a spring and cascades its cards in (see {@link VeloWindow#springAnimation()}).
 */
public final class ModMenuScreen extends VeloWindow implements ModuleConfigScreen.Reopenable, net.veloclient.velo.client.gui.window.MenuPage {

	private static final int RAIL = 30;
	private static final int GAP = 8;
	/** 2026-11 redesign: slim row cards, four columns on the default window (no side panel any more). */
	private static final int MIN_CARD = 205; // 2026-11 owner: the default cards were far too small
	private static final int EDIT_H = 26;

	private enum View { MODULES, ADDONS }

	private static final ModuleCategory[] CATEGORIES = {ModuleCategory.HUD, ModuleCategory.PVP, ModuleCategory.QOL, ModuleCategory.PERFORMANCE,
			ModuleCategory.RENDERING, ModuleCategory.SERVER_TOOLS, ModuleCategory.DEBUG, ModuleCategory.COSMETICS};

	private static View view = View.MODULES;
	/** Selected chip: 0 = All, then a category (or, in Addons, a group). */
	private static int chip = 0; // "All" first (owner 2026-11-10; was HUD)
	private static boolean onlyOn;
	/** Card layout: {@link VeloModuleCard#GRID} (default), COMPACT or LIST - switched next to "On only". */
	private static int cardStyle = VeloModuleCard.GRID;
	private static String selectedId;

	private TextFieldWidget search;
	private VeloScrollRegion region;
	private int[][] spots = new int[0][];
	private int pageHeight;
	private final List<Heading> headings = new ArrayList<>();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final List<RailItem> rail = new ArrayList<>();
	private final List<int[]> railBoxes = new ArrayList<>();
	private final java.util.Map<Integer, Float> railHover = new java.util.HashMap<>();
	private long railNanos;
	private int searchX;
	private int searchW;
	private int editX;
	private int editW;
	private final net.veloclient.velo.client.nowplaying.MediaControls media = new net.veloclient.velo.client.nowplaying.MediaControls();

	private record Heading(int y, String title, String note, int color) {
	}

	private record RailItem(String icon, String label, Runnable action, BooleanSupplier active, boolean bottom) {
	}

	public ModMenuScreen() {
		super(Text.literal("Velo Client"), 840, 520);
	}

	/** The menu on Modules or Addons (the one-window menu's rail opens it like this). */
	public static ModMenuScreen forView(boolean addons) {
		View next = addons && hasAddons() ? View.ADDONS : View.MODULES;
		if (view != next) {
			view = next;
			chip = 0;
			selectedId = null;
		}
		return new ModMenuScreen();
	}

	public static boolean addonsAvailable() {
		return hasAddons();
	}

	@Override
	public String railId() {
		return view == View.ADDONS ? "addons" : "modules";
	}

	@Override
	protected boolean springAnimation() {
		return true;
	}

	@Override
	public void reopen() {
		this.layoutContent();
	}

	/** Dev-only (screenshot tour): shows the Addons view. */
	public void openMenuForTour() {
		view = View.ADDONS;
		chip = 0;
		layoutContent();
	}

	private static boolean hasAddons() {
		return !ModuleRegistry.byCategory(ModuleCategory.ADDONS).isEmpty();
	}

	// ---- Geometry ----

	private int mainX() {
		// the one-window menu draws the shared rail and already moved the content past it
		return contentX() + (shell() ? 0 : RAIL + 12);
	}

	private int mainW() {
		return contentX() + contentWidth() - mainX();
	}

	private int chipsY() {
		return contentY() + 34;
	}

	private int gridTop() {
		return chipsY() + 26;
	}

	@Override
	protected int[] pageArea() {
		return new int[] {mainX() - 4, gridTop() - 2, mainW() + 8, contentBottom() - gridTop() + 2};
	}

	// ---- Layout ----

	@Override
	protected void layoutContent() {
		String query = search == null ? "" : search.getText();
		this.clearChildren();
		buildRail();
		editW = editWidth();
		editX = mainX() + mainW() - editW;
		searchW = Math.min(150, mainW() / 4);
		searchX = editX - 8 - searchW;
		search = new TextFieldWidget(this.textRenderer, searchX + 20, contentY() + 6, searchW - 26, 12, Text.literal("Search"));
		search.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder(view == View.ADDONS ? "Search addons" : "Search modules"));
		search.setDrawsBackground(false);
		search.setText(query);
		search.setChangedListener(q -> rebuildPage());
		addDrawableChild(search);
		rebuildPage();
	}

	private void buildRail() {
		rail.clear();
		rail.add(new RailItem("grid", "Modules", () -> switchView(View.MODULES), () -> view == View.MODULES, false));
		// Only there when the Addons jar is installed and unlocked for this account - nobody else sees it.
		if (hasAddons()) {
			rail.add(new RailItem("extensions", "Addons", () -> switchView(View.ADDONS), () -> view == View.ADDONS, false));
		}
		rail.add(new RailItem("skins", "Locker", () -> this.client.setScreen(CapeEquipScreen.open(this, 1)), () -> false, false));
		rail.add(new RailItem("friends", "Friends", () -> this.client.setScreen(new FriendsScreen(this)), () -> false, false));
		rail.add(new RailItem("clips", "Clips & Screenshots", () -> this.client.setScreen(new net.veloclient.velo.client.gui.media.MediaScreen(this)),
				() -> false, false));
		rail.add(new RailItem("fight_recap", "Fight Recap (K)",
				() -> this.client.setScreen(new net.veloclient.velo.client.gui.media.FightRecapScreen(this)), () -> false, false));
		rail.add(new RailItem("map", "World Map (M)", () -> this.client.setScreen(new net.veloclient.velo.client.worldmap.WorldMapScreen(this)),
				() -> false, false));
		rail.add(new RailItem("waypoints", "Waypoints", () -> this.client.setScreen(new WaypointsScreen(this)), () -> false, false));
		rail.add(new RailItem("queue", "Background Queue", () -> this.client.setScreen(new BackgroundQueueSessionsScreen(this, null)),
				() -> false, false));
		rail.add(new RailItem("schematics", "Schematics", () -> this.client.setScreen(new SchematicsScreen(this)), () -> false, false));
		rail.add(new RailItem("settings", "Settings", () -> this.client.setScreen(new SettingsTabScreen(this)), () -> false, true));
		rail.add(new RailItem("redstone", "Redstone Mode (R)", () -> {
			this.client.setScreen(null);
			net.veloclient.velo.client.redstone.RedstoneMode.enter();
		}, () -> false, true));
		rail.add(new RailItem("profiles", "Profiles", () -> this.client.setScreen(new ProfileScreen(this)), () -> false, true));
		rail.add(new RailItem("globe", "Cloud Sync (Velo+)", () -> this.client.setScreen(new CloudSyncScreen(this)), () -> false, true));
		rail.add(new RailItem("party", "World Hosting", () -> this.client.setScreen(new WorldHostScreen(this)), () -> false, true));
	}

	private void switchView(View next) {
		if (view != next) {
			net.veloclient.velo.client.sound.VeloUiSounds.tab();
			view = next;
			chip = 0;
			selectedId = null;
			if (search != null) {
				search.setText("");
			}
			restartPageIn();
		}
		layoutContent();
	}

	private record Section(String title, String note, int color, List<Module> modules) {
	}

	/** What the grid shows right now, in sections. */
	private List<Section> sections() {
		String query = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
		int accent = MenuKit.accent();
		List<Section> out = new ArrayList<>();
		if (!query.isEmpty()) {
			List<Module> found = new ArrayList<>();
			for (Module module : ModuleRegistry.all()) {
				boolean addon = module.category() == ModuleCategory.ADDONS;
				if ((view == View.ADDONS) == addon && (module.displayName().toLowerCase(Locale.ROOT).contains(query)
						|| ModuleTags.tag(module).toLowerCase(Locale.ROOT).contains(query))) {
					found.add(module);
				}
			}
			out.add(new Section(null, null, accent, filter(found)));
			return out;
		}
		if (view == View.ADDONS) {
			List<Module> addons = ModuleRegistry.byCategory(ModuleCategory.ADDONS);
			for (int i = 0; i < ModuleTags.addonGroups().size(); i++) {
				ModuleTags.AddonGroup group = ModuleTags.addonGroups().get(i);
				if (chip != 0 && chip != i + 1) {
					continue;
				}
				List<Module> in = addons.stream().filter(m -> ModuleTags.addonGroup(m.id()) == group).toList();
				if (!in.isEmpty()) {
					out.add(new Section(group.name(), group.blurb(), group.color(), filter(in)));
				}
			}
			return out;
		}
		if (chip == 0) {
			for (ModuleCategory category : CATEGORIES) {
				out.add(new Section(category.displayName(), null, accent, filter(ModuleRegistry.byCategory(category))));
			}
			return out;
		}
		out.add(new Section(null, null, accent, filter(ModuleRegistry.byCategory(CATEGORIES[chip - 1]))));
		return out;
	}

	private static List<Module> filter(List<Module> modules) {
		return onlyOn ? modules.stream().filter(Module::isEnabled).toList() : modules;
	}

	private void rebuildPage() {
		if (region != null) {
			for (var row : region.rowsSnapshot()) {
				this.remove(row);
			}
		}
		headings.clear();
		int top = gridTop();
		region = new VeloScrollRegion(mainX(), top, mainW(), contentBottom() - top);
		int usable = region.viewportWidth();
		int minCard = cardStyle == VeloModuleCard.COMPACT ? 140 : MIN_CARD;
		int columns = cardStyle == VeloModuleCard.LIST ? 1 : Math.max(2, (usable + GAP) / (minCard + GAP));
		int cardW = (usable - (columns - 1) * GAP) / columns;
		int cardH = VeloModuleCard.heightFor(cardStyle);
		List<int[]> placed = new ArrayList<>();
		int y = 2;
		int order = 0;
		Module first = null;
		for (Section section : sections()) {
			if (section.modules().isEmpty()) {
				continue;
			}
			if (section.title() != null) {
				headings.add(new Heading(y, section.title(), section.note() != null ? section.note() : String.valueOf(section.modules().size()), section.color()));
				y += 15;
			}
			for (int i = 0; i < section.modules().size(); i++) {
				Module module = section.modules().get(i);
				if (first == null) {
					first = module;
				}
				int color = view == View.ADDONS ? ModuleTags.addonGroup(module.id()).color() : MenuKit.accent();
				VeloModuleCard card = new VeloModuleCard(cardW, module, color, order++, () -> module.id().equals(selectedId),
						() -> selectedId = module.id(), () -> openSettings(module)).style(cardStyle);
				addSelectableChild(card);
				region.addRow(card);
				placed.add(new int[] {mainX() + i % columns * (cardW + GAP), y + i / columns * (cardH + GAP)});
			}
			y += (section.modules().size() + columns - 1) / columns * (cardH + GAP) + 12;
		}
		// Keep the picked module while it's on this page; otherwise pick the first one shown.
		boolean shown = false;
		for (Section section : sections()) {
			shown |= section.modules().stream().anyMatch(m -> m.id().equals(selectedId));
		}
		if (!shown) {
			selectedId = first == null ? null : first.id();
		}
		spots = placed.toArray(new int[0][]);
		pageHeight = y;
		region.layoutPositioned(spots, pageHeight);
	}

	private void openSettings(Module module) {
		this.client.setScreen(module.id().equals("command-keybinds") ? new CommandKeybindsScreen(this) : new ModuleConfigScreen(module, this));
	}

	// ---- Header: Edit HUD ----

	@Override
	protected void renderHeaderExtras(DrawContext context, int mouseX, int mouseY) {
		// Edit HUD moved into the top row as the page's one big button (with what it does under its name).
	}

	private int editWidth() {
		return Math.max(this.textRenderer.getWidth(MenuKit.uiBold("Edit HUD")), this.textRenderer.getWidth(MenuKit.small("Move & resize what's on screen")))
				+ 36;
	}

	/** The one big red button: Edit HUD, with a second line saying what it is for (players didn't know). */
	private void drawEditHud(DrawContext context, int mouseX, int mouseY) {
		int y = contentY() - 1;
		boolean hover = MenuKit.inside(mouseX, mouseY, editX, y, editW, EDIT_H);
		int accent = MenuKit.accent();
		// Calmer than a solid red block (owner: red + white text was too loud): a dark red-tinted card with an accent
		// edge and icon - still the strongest thing in the row, just not shouting.
		VeloDraw.fillRounded(context, editX, y, editW, EDIT_H, 6, VeloAnim.lerpArgb(0xFF1A1314, accent, hover ? 0.30f : 0.20f));
		VeloDraw.strokeRounded(context, editX, y, editW, EDIT_H, 6, VeloUi.withAlpha(accent, hover ? 0xE0 : 0x90));
		MenuKit.icon(context, "hud_layout", editX + 9, y + (EDIT_H - 13) / 2, 13, MenuKit.accentText());
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Edit HUD"), editX + 28, y + 5, 0xFFF2E9E9);
		MenuKit.text(context, this.textRenderer, MenuKit.small("Move & resize what's on screen"), editX + 28, y + 15, 0xFFB3A8A9);
		hits.add(new VeloUi.Hit(editX, y, editW, EDIT_H, () -> this.client.setScreen(new HudEditScreen(this))));
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		if (!shell()) {
			drawRail(context, mouseX, mouseY);
		}
		drawTopRow(context, mouseX, mouseY);
		drawEditHud(context, mouseX, mouseY);
		drawChips(context, mouseX, mouseY);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// Rail labels slide out of the icon you're on (and back in), over everything.
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(railNanos, now);
		railNanos = now;
		for (int i = 0; i < (shell() ? 0 : railBoxes.size()); i++) {
			int[] box = railBoxes.get(i);
			boolean over = MenuKit.inside(mouseX, mouseY, box[0], box[1], box[2], box[2]);
			float t = VeloAnim.step(railHover.getOrDefault(i, 0f), over ? 1f : 0f, dt * 1.4f);
			railHover.put(i, t);
			if (t < 0.02f) {
				continue;
			}
			float eased = 1f - (1f - t) * (1f - t) * (1f - t);
			Text label = MenuKit.uiBold(rail.get(box[3]).label());
			int full = this.textRenderer.getWidth(label) + 16;
			int w = Math.max(8, Math.round(full * eased));
			int tx = box[0] + box[2] + 6 + Math.round((1f - eased) * -6);
			int ty = box[1] + (box[2] - 18) / 2;
			int alpha = Math.round(255 * eased);
			VeloDraw.shadow(context, tx, ty, w, 18, 6, 10, 3, VeloUi.withAlpha(0xFF000000, Math.round(0x80 * eased)));
			VeloDraw.fillRounded(context, tx, ty, w, 18, 6, VeloUi.withAlpha(0xFF141011, alpha));
			VeloDraw.strokeRounded(context, tx, ty, w, 18, 6, VeloUi.withAlpha(MenuKit.accent(), Math.round(0x70 * eased)));
			context.enableScissor(tx, ty, tx + w, ty + 18);
			MenuKit.text(context, this.textRenderer, label, tx + 8, ty + 5, VeloUi.withAlpha(MenuKit.TEXT, alpha));
			context.disableScissor();
		}
		media.renderPanel(context, this.textRenderer, mouseX, mouseY, this.width, this.height);
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (region != null) {
			region.renderRows(context, mouseX, mouseY, delta);
			region.renderScissorStart(context);
			int top = region.pageTop();
			for (Heading heading : headings) {
				int hy = top + heading.y() + 3;
				Text title = MenuKit.small(heading.title().toUpperCase(Locale.ROOT));
				MenuKit.text(context, this.textRenderer, title, mainX() + 1, hy, 0xFFB3A8A9);
				MenuKit.text(context, this.textRenderer, MenuKit.small(heading.note().toUpperCase(Locale.ROOT)),
						mainX() + 9 + this.textRenderer.getWidth(title), hy, 0xFF7D7273);
			}
			region.renderScissorEnd(context);
			region.renderScrollbarPage(context);
			if (spots.length == 0) {
				String empty = onlyOn ? "Nothing switched on here yet." : "No module matches that.";
				MenuKit.text(context, this.textRenderer, MenuKit.ui(empty), mainX(), gridTop() + 6, MenuKit.TEXT_3);
			}
		}
	}

	private void drawRail(DrawContext context, int mouseX, int mouseY) {
		railBoxes.clear();
		int x = contentX();
		int top = contentY();
		int bottom = contentBottom();
		VeloDraw.fillRounded(context, x, top, RAIL, bottom - top, 9, 0xE0070505);
		long upper = rail.stream().filter(r -> !r.bottom()).count();
		long lower = rail.size() - upper;
		int size = Math.max(18, Math.min(24, (bottom - top - 16 - (int) (rail.size() + 1) * 3) / rail.size()));
		int ty = top + 5;
		int by = bottom - 5 - (int) lower * (size + 3) + 3;
		for (int i = 0; i < rail.size(); i++) {
			RailItem item = rail.get(i);
			int iy;
			if (item.bottom()) {
				iy = by;
				by += size + 3;
			} else {
				iy = ty;
				ty += size + 3;
				if (i == 1) {
					// A hairline under Modules and Addons: those switch the view, the rest open their own screens.
					ty += 4;
				}
			}
			if (i == 2) {
				context.fill(x + 7, iy - 5, x + RAIL - 7, iy - 4, 0x18FFFFFF);
			}
			int ix = x + (RAIL - size) / 2;
			boolean active = item.active().getAsBoolean();
			boolean hover = MenuKit.inside(mouseX, mouseY, ix, iy, size, size);
			boolean redstone = item.icon().equals("redstone");
			if (active) {
				VeloDraw.fillRounded(context, ix, iy, size, size, 6, 0xFF1E1718);
			} else if (hover) {
				VeloDraw.fillRounded(context, ix, iy, size, size, 7, 0x14FFFFFF);
			}
			int tint = active ? MenuKit.accentText() : redstone ? 0xFFFF6B73 : hover ? 0xFFE4E4E8 : 0xFF8E8384;
			int icon = Math.round(size * 0.55f);
			MenuKit.icon(context, item.icon(), ix + (size - icon) / 2, iy + (size - icon) / 2, icon, tint);
			if (item.icon().equals("friends")) {
				int unread = friendsBadge();
				if (unread > 0) {
					VeloDraw.fillCircle(context, ix + size - 3f, iy + 3f, 3.5f, MenuKit.accent());
				}
			}
			railBoxes.add(new int[] {ix, iy, size, i});
			hits.add(new VeloUi.Hit(ix, iy, size, size, item.action()));
		}
	}

	private void drawTopRow(DrawContext context, int mouseX, int mouseY) {
		int x = mainX();
		int y = contentY();
		// The view's title on the left, where the eye starts.
		Text title = MenuKit.displayLg(view == View.ADDONS ? "Addons" : "Modules");
		MenuKit.text(context, this.textRenderer, title, x, y + 7, MenuKit.TEXT);

		// Search on the far right, the profile switcher right before it.
		boolean focused = search != null && search.isFocused();
		// Profile, search and Edit HUD: one row, all EDIT_H tall on the same line (y - 1).
		boolean searchHover = MenuKit.inside(mouseX, mouseY, searchX, y - 1, searchW, EDIT_H);
		VeloStyle.drawInputField(context, searchX, y - 1, searchW, EDIT_H, focused, searchHover);
		VeloDraw.searchGlyph(context, searchX + 11f, y + 12f, 9f, focused ? MenuKit.accentText() : VeloStyle.textMuted());

		String name = net.veloclient.velo.profile.VeloProfileManager.activeName();
		Text label = MenuKit.fit(this.textRenderer, MenuKit.uiBold(name == null ? "Default" : name), 90);
		int w = this.textRenderer.getWidth(label) + 40;
		int px = searchX - 8 - w;
		boolean hover = MenuKit.inside(mouseX, mouseY, px, y - 1, w, EDIT_H);
		VeloDraw.fillRounded(context, px, y - 1, w, EDIT_H, 6, hover ? 0x16FFFFFF : 0x0BFFFFFF);
		VeloDraw.strokeRounded(context, px, y - 1, w, EDIT_H, 6, hover ? 0x30FFFFFF : 0x14FFFFFF);
		MenuKit.icon(context, "profiles", px + 8, y + 7, 9, 0xFF9A8E8F);
		MenuKit.text(context, this.textRenderer, label, px + 24, y + 8, MenuKit.TEXT);
		// A small down chevron.
		for (int i = 0; i < 3; i++) {
			context.fill(px + w - 12 + i, y + 10 + i, px + w - 11 + i, y + 11 + i, 0xFF8A8A95);
			context.fill(px + w - 8 - i, y + 10 + i, px + w - 7 - i, y + 11 + i, 0xFF8A8A95);
		}
		hits.add(new VeloUi.Hit(px, y - 1, w, EDIT_H, () -> this.client.setScreen(new ProfileScreen(this))));
		// What's playing, in the gap between the title and the profile switcher.
		int titleRight = x + this.textRenderer.getWidth(title) + 14;
		media.renderPill(context, this.textRenderer, px - 8, y + 1, px - 8 - titleRight, mouseX, mouseY);
	}

	private void drawChips(DrawContext context, int mouseX, int mouseY) {
		List<String> labels = new ArrayList<>();
		labels.add("All");
		if (view == View.ADDONS) {
			ModuleTags.addonGroups().forEach(g -> labels.add(g.name()));
		} else {
			for (ModuleCategory category : CATEGORIES) {
				labels.add(category.displayName());
			}
		}
		int x = mainX();
		int y = chipsY();
		int right = mainX() + mainW();
		Text onLabel = MenuKit.uiBold("On only");
		int onW = this.textRenderer.getWidth(onLabel) + 14;
		for (int i = 0; i < labels.size(); i++) {
			Text label = MenuKit.uiBold(labels.get(i));
			int w = this.textRenderer.getWidth(label) + 14;
			if (x + w > right - onW - 8 - 3 * 18 - 6) {
				break;
			}
			boolean on = i == chip;
			boolean hover = MenuKit.inside(mouseX, mouseY, x, y, w, 16);
			int color = view == View.ADDONS && i > 0 ? ModuleTags.addonGroups().get(i - 1).color() : MenuKit.accent();
			// Launcher-style tabs: plain text, the picked one white with an accent line under it.
			if (on) {
				context.fill(x + 7, y + 16, x + w - 7, y + 18, color);
			}
			MenuKit.text(context, this.textRenderer, label, x + 7, y + 4, on ? 0xFFFFFFFF : hover ? 0xFFE4E4E8 : 0xFF9A8E8F);
			int index = i;
			hits.add(new VeloUi.Hit(x, y, w, 16, () -> {
				if (chip != index) {
					net.veloclient.velo.client.sound.VeloUiSounds.tab();
				}
				chip = index;
				if (search != null) {
					search.setText("");
				}
				restartPageIn();
				rebuildPage();
			}));
			x += w + 2;
		}
		context.fill(mainX(), y + 18, right, y + 19, 0x10FFFFFF);
		// Layout switch (grid / small / list), left of "On only".
		for (int k = 0; k < 3; k++) {
			int bx = right - onW - 8 - (3 - k) * 18;
			boolean picked = cardStyle == k;
			boolean over = MenuKit.inside(mouseX, mouseY, bx, y, 16, 16);
			VeloDraw.fillRounded(context, bx, y, 16, 16, 4, picked ? 0xFF2A2324 : over ? 0x12FFFFFF : 0x00FFFFFF);
			int g = picked ? 0xFFEDE6E6 : over ? 0xFFD4CCCC : 0xFF8E8384;
			if (k == 0) {
				context.fill(bx + 4, y + 4, bx + 7, y + 7, g);
				context.fill(bx + 9, y + 4, bx + 12, y + 7, g);
				context.fill(bx + 4, y + 9, bx + 7, y + 12, g);
				context.fill(bx + 9, y + 9, bx + 12, y + 12, g);
			} else if (k == 1) {
				for (int gx = 0; gx < 3; gx++) {
					for (int gy = 0; gy < 3; gy++) {
						context.fill(bx + 4 + gx * 3, y + 4 + gy * 3, bx + 6 + gx * 3, y + 6 + gy * 3, g);
					}
				}
			} else {
				for (int gy = 0; gy < 3; gy++) {
					context.fill(bx + 4, y + 4 + gy * 3, bx + 12, y + 5 + gy * 3, g);
				}
			}
			int pick = k;
			hits.add(new VeloUi.Hit(bx, y, 16, 16, () -> {
				if (cardStyle != pick) {
					cardStyle = pick;
					restartPageIn();
					rebuildPage();
				}
			}));
		}
		int ox = right - onW;
		boolean hover = MenuKit.inside(mouseX, mouseY, ox, y, onW, 16);
		VeloDraw.fillRounded(context, ox, y, onW, 16, 5, onlyOn ? VeloUi.withAlpha(MenuKit.accent(), 0x26) : hover ? 0x12FFFFFF : 0x00FFFFFF);
		VeloDraw.strokeRounded(context, ox, y, onW, 16, 5, onlyOn ? VeloUi.withAlpha(MenuKit.accent(), 0x90) : 0x16FFFFFF);
		MenuKit.text(context, this.textRenderer, onLabel, ox + 7, y + 4, onlyOn ? MenuKit.accentText() : 0xFF9A8E8F);
		hits.add(new VeloUi.Hit(ox, y, onW, 16, () -> {
			onlyOn = !onlyOn;
			rebuildPage();
		}));
	}

	private static int friendsBadge() {
		int unread = net.veloclient.velo.client.network.SocialClient.totalUnread();
		var state = net.veloclient.velo.client.network.SocialClient.snapshot();
		return unread + (state == null ? 0 : state.incoming.size());
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (media.mouseClicked(click.x(), click.y())) {
			return true;
		}
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				net.veloclient.velo.client.sound.VeloUiSounds.click(1.0f);
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
		return media.mouseDragged(click.x(), click.y()) || super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.gui.Click click) {
		return media.mouseReleased() || super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (media.mouseScrolled(mouseX, mouseY, verticalAmount)) {
			return true;
		}
		if (region != null && region.scroll(mouseX, mouseY, verticalAmount)) {
			region.layoutPositioned(spots, pageHeight);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

}
