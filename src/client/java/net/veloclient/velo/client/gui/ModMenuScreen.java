package net.veloclient.velo.client.gui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
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
 * <li>on top: the profile you're on (click to switch), search, and the glowing Edit HUD in the header;</li>
 * <li>category chips (Addons: their groups) and a grid of module cards - a big icon that lights up when the module is
 * on, its switch, the name and what it's good for;</li>
 * <li>a side panel for the picked module: what it does, its styles as one-click choices, quick switches, Favourite,
 * "All settings" and, for HUD modules, "Place on screen".</li>
 * </ul>
 * Opens with a spring and cascades its cards in (see {@link VeloWindow#springAnimation()}).
 */
public final class ModMenuScreen extends VeloWindow implements ModuleConfigScreen.Reopenable {

	private static final int RAIL = 30;
	private static final int DRAWER = 180;
	private static final int GAP = 8;
	private static final int MIN_CARD = 124;

	private enum View { MODULES, ADDONS }

	private static final ModuleCategory[] CATEGORIES = {ModuleCategory.HUD, ModuleCategory.PVP, ModuleCategory.QOL, ModuleCategory.PERFORMANCE,
			ModuleCategory.RENDERING, ModuleCategory.SERVER_TOOLS, ModuleCategory.DEBUG, ModuleCategory.COSMETICS};

	private static View view = View.MODULES;
	/** Selected chip: 0 = All, then a category (or, in Addons, a group). */
	private static int chip = 1;
	private static boolean onlyOn;
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
		return contentX() + RAIL + 12;
	}

	private int drawerX() {
		return contentX() + contentWidth() - DRAWER;
	}

	private int mainW() {
		return drawerX() - 12 - mainX();
	}

	private int chipsY() {
		return contentY() + 30;
	}

	private int gridTop() {
		return chipsY() + 24;
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
		searchW = Math.min(160, mainW() / 4);
		searchX = mainX() + mainW() - searchW;
		search = new TextFieldWidget(this.textRenderer, searchX + 20, contentY() + 7, searchW - 26, 12, Text.literal("Search"));
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
			view = next;
			chip = next == View.ADDONS ? 0 : 1;
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
		int columns = Math.max(2, (usable + GAP) / (MIN_CARD + GAP));
		int cardW = (usable - (columns - 1) * GAP) / columns;
		List<int[]> placed = new ArrayList<>();
		int y = 2;
		int order = 0;
		Module first = null;
		for (Section section : sections()) {
			if (section.modules().isEmpty()) {
				continue;
			}
			if (section.title() != null) {
				headings.add(new Heading(y, section.title(), section.note() != null ? section.note() : section.modules().size() + " modules", section.color()));
				y += 18;
			}
			for (int i = 0; i < section.modules().size(); i++) {
				Module module = section.modules().get(i);
				if (first == null) {
					first = module;
				}
				int color = view == View.ADDONS ? ModuleTags.addonGroup(module.id()).color() : MenuKit.accent();
				VeloModuleCard card = new VeloModuleCard(cardW, module, color, order++, () -> module.id().equals(selectedId),
						() -> selectedId = module.id(), () -> openSettings(module));
				addSelectableChild(card);
				region.addRow(card);
				placed.add(new int[] {mainX() + i % columns * (cardW + GAP), y + i / columns * (VeloModuleCard.HEIGHT + GAP)});
			}
			y += (section.modules().size() + columns - 1) / columns * (VeloModuleCard.HEIGHT + GAP) + 10;
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
		Text label = MenuKit.uiBold("Edit HUD");
		editW = this.textRenderer.getWidth(label) + 34;
		editX = headerRight() - editW;
		int h = 20;
		int y = windowY + (HEADER_HEIGHT - h) / 2;
		boolean hover = MenuKit.inside(mouseX, mouseY, editX, y, editW, h);
		int accent = MenuKit.accent();
		VeloDraw.shadow(context, editX, y, editW, h, 7, hover ? 14 : 10, 3, VeloUi.withAlpha(accent, hover ? 0x90 : 0x60));
		VeloDraw.fillRoundedGradient(context, editX, y, editW, h, 7, VeloAnim.lerpArgb(accent, 0xFFFFFFFF, hover ? 0.2f : 0.1f), accent);
		MenuKit.icon(context, "hud_layout", editX + 9, y + 5, 10, MenuKit.onAccent());
		MenuKit.text(context, this.textRenderer, label, editX + 24, y + (h - 8) / 2, MenuKit.onAccent());
	}

	@Override
	protected boolean headerClick(int mouseX, int mouseY) {
		int y = windowY + (HEADER_HEIGHT - 20) / 2;
		if (MenuKit.inside(mouseX, mouseY, editX, y, editW, 20)) {
			this.client.setScreen(new HudEditScreen(this));
			return true;
		}
		return false;
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		drawRail(context, mouseX, mouseY);
		drawTopRow(context, mouseX, mouseY);
		drawChips(context, mouseX, mouseY);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// Rail labels slide out of the icon you're on (and back in), over everything.
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(railNanos, now);
		railNanos = now;
		for (int i = 0; i < railBoxes.size(); i++) {
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
			VeloDraw.fillRounded(context, tx, ty, w, 18, 6, VeloUi.withAlpha(0xFF1C1C23, alpha));
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
				int hy = top + heading.y() + 2;
				VeloDraw.fillCircle(context, mainX() + 3f, hy + 4f, 2.5f, heading.color());
				Text title = MenuKit.display(heading.title());
				MenuKit.text(context, this.textRenderer, title, mainX() + 10, hy, MenuKit.TEXT);
				MenuKit.text(context, this.textRenderer, MenuKit.ui(heading.note()), mainX() + 16 + this.textRenderer.getWidth(title), hy + 1,
						MenuKit.TEXT_3);
			}
			region.renderScissorEnd(context);
			region.renderScrollbarPage(context);
			if (spots.length == 0) {
				String empty = onlyOn ? "Nothing switched on here yet." : "No module matches that.";
				MenuKit.text(context, this.textRenderer, MenuKit.ui(empty), mainX(), gridTop() + 6, MenuKit.TEXT_3);
			}
		}
		drawDrawer(context, mouseX, mouseY);
	}

	private void drawRail(DrawContext context, int mouseX, int mouseY) {
		railBoxes.clear();
		int x = contentX();
		int top = contentY();
		int bottom = contentBottom();
		VeloDraw.fillRounded(context, x, top, RAIL, bottom - top, 9, 0x08FFFFFF);
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
				VeloDraw.shadow(context, ix, iy, size, size, 7, 8, 2, VeloUi.withAlpha(MenuKit.accent(), 0x50));
				VeloDraw.fillRounded(context, ix, iy, size, size, 7, VeloUi.withAlpha(MenuKit.accent(), 0x30));
				VeloDraw.strokeRounded(context, ix, iy, size, size, 7, VeloUi.withAlpha(MenuKit.accent(), 0x90));
			} else if (hover) {
				VeloDraw.fillRounded(context, ix, iy, size, size, 7, 0x14FFFFFF);
			}
			int tint = active ? 0xFFFFFFFF : redstone ? 0xFFFF6B73 : hover ? 0xFFE4E4E8 : 0xFF9A9AA6;
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
		MenuKit.text(context, this.textRenderer, title, x, y + 5, MenuKit.TEXT);

		// Search on the far right, the profile switcher right before it.
		boolean focused = search != null && search.isFocused();
		boolean searchHover = MenuKit.inside(mouseX, mouseY, searchX, y, searchW, 24);
		VeloStyle.drawInputField(context, searchX, y + 1, searchW, 22, focused, searchHover);
		VeloDraw.searchGlyph(context, searchX + 11f, y + 12f, 9f, focused ? MenuKit.accentText() : VeloStyle.textMuted());

		String name = net.veloclient.velo.profile.VeloProfileManager.activeName();
		Text label = MenuKit.fit(this.textRenderer, MenuKit.uiBold(name == null ? "Default" : name), 90);
		int w = this.textRenderer.getWidth(label) + 40;
		int px = searchX - 8 - w;
		boolean hover = MenuKit.inside(mouseX, mouseY, px, y + 1, w, 22);
		VeloDraw.fillRounded(context, px, y + 1, w, 22, 7, hover ? 0x16FFFFFF : 0x0BFFFFFF);
		VeloDraw.strokeRounded(context, px, y + 1, w, 22, 7, hover ? 0x30FFFFFF : 0x18FFFFFF);
		VeloDraw.fillRounded(context, px + 5, y + 5, 14, 14, 4, VeloUi.withAlpha(MenuKit.accent(), 0x38));
		MenuKit.icon(context, "profiles", px + 8, y + 8, 8, MenuKit.accentText());
		MenuKit.text(context, this.textRenderer, label, px + 24, y + 8, MenuKit.TEXT);
		// A small down chevron.
		for (int i = 0; i < 3; i++) {
			context.fill(px + w - 12 + i, y + 10 + i, px + w - 11 + i, y + 11 + i, 0xFF8A8A95);
			context.fill(px + w - 8 - i, y + 10 + i, px + w - 7 - i, y + 11 + i, 0xFF8A8A95);
		}
		hits.add(new VeloUi.Hit(px, y + 1, w, 22, () -> this.client.setScreen(new ProfileScreen(this))));
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
			if (x + w > right - onW - 8) {
				break;
			}
			boolean on = i == chip;
			boolean hover = MenuKit.inside(mouseX, mouseY, x, y, w, 16);
			int color = view == View.ADDONS && i > 0 ? ModuleTags.addonGroups().get(i - 1).color() : MenuKit.accent();
			if (on) {
				VeloDraw.fillRounded(context, x, y, w, 16, 8, VeloUi.withAlpha(color, 0x30));
				VeloDraw.strokeRounded(context, x, y, w, 16, 8, VeloUi.withAlpha(color, 0xB0));
			} else if (hover) {
				VeloDraw.fillRounded(context, x, y, w, 16, 8, 0x12FFFFFF);
			}
			MenuKit.text(context, this.textRenderer, label, x + 7, y + 4, on ? 0xFFFFFFFF : hover ? 0xFFE4E4E8 : 0xFF9A9AA6);
			int index = i;
			hits.add(new VeloUi.Hit(x, y, w, 16, () -> {
				chip = index;
				if (search != null) {
					search.setText("");
				}
				restartPageIn();
				rebuildPage();
			}));
			x += w + 4;
		}
		int ox = right - onW;
		boolean hover = MenuKit.inside(mouseX, mouseY, ox, y, onW, 16);
		VeloDraw.fillRounded(context, ox, y, onW, 16, 8, onlyOn ? VeloUi.withAlpha(MenuKit.GREEN, 0x30) : hover ? 0x12FFFFFF : 0x08FFFFFF);
		VeloDraw.strokeRounded(context, ox, y, onW, 16, 8, onlyOn ? VeloUi.withAlpha(MenuKit.GREEN, 0xA0) : 0x18FFFFFF);
		MenuKit.text(context, this.textRenderer, onLabel, ox + 7, y + 4, onlyOn ? MenuKit.GREEN : 0xFF9A9AA6);
		hits.add(new VeloUi.Hit(ox, y, onW, 16, () -> {
			onlyOn = !onlyOn;
			rebuildPage();
		}));
	}

	/** The side panel for the picked module. */
	private void drawDrawer(DrawContext context, int mouseX, int mouseY) {
		int x = drawerX();
		int y = contentY();
		int w = DRAWER;
		int h = contentBottom() - y;
		Module module = selectedId == null ? null : ModuleRegistry.get(selectedId).orElse(null);
		int color = module != null && module.category() == ModuleCategory.ADDONS ? ModuleTags.addonGroup(module.id()).color() : MenuKit.accent();
		VeloDraw.fillRounded(context, x, y, w, h, 10, 0x0AFFFFFF);
		VeloDraw.fillRoundedGradient(context, x, y, w, Math.min(90, h), 10, VeloUi.withAlpha(color, 0x1C), 0x00000000);
		VeloDraw.strokeRounded(context, x, y, w, h, 10, 0x16FFFFFF);
		if (module == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Pick a module."), x + 12, y + 14, MenuKit.TEXT_3);
			return;
		}
		int px = x + 12;
		int pw = w - 24;
		// Icon, name, switch.
		boolean enabled = module.isEnabled();
		int tile = 32;
		if (enabled) {
			VeloDraw.shadow(context, px, y + 12, tile, tile, 9, 12, 3, VeloUi.withAlpha(color, 0x70));
		}
		VeloDraw.fillRoundedGradient(context, px, y + 12, tile, tile, 9, enabled ? VeloAnim.lerpArgb(color, 0xFFFFFFFF, 0.08f) : 0xFF26262D,
				enabled ? VeloAnim.lerpArgb(color, 0xFF000000, 0.35f) : 0xFF1A1A20);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, ModuleIcons.textureFor(module.id()), px + 7, y + 19, 0f, 0f, 18, 18, 18, 18, 18, 18,
				enabled ? 0xFFFFFFFF : 0xFFC9C9D1);
		Text beta = MenuKit.small("BETA");
		int betaW = module.beta() ? this.textRenderer.getWidth(beta) + 10 : 0;
		Text name = MenuKit.fit(this.textRenderer, MenuKit.display(module.displayName()), pw - tile - 8 - (betaW > 0 ? betaW + 6 : 0));
		MenuKit.text(context, this.textRenderer, name, px + tile + 8, y + 15, MenuKit.TEXT);
		if (betaW > 0) {
			int bx = px + tile + 8 + MenuKit.width(this.textRenderer, name) + 6;
			VeloDraw.fillRounded(context, bx, y + 15, betaW, 11, 4, 0xFFE0A030);
			MenuKit.text(context, this.textRenderer, beta, bx + 5, y + 17, 0xFF1A1206);
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui("Good for " + ModuleTags.tag(module)), pw - tile - 8),
				px + tile + 8, y + 30, MenuKit.TEXT_3);
		int sy = y + 54;
		Text state = MenuKit.uiBold(enabled ? "On" : "Off");
		MenuKit.text(context, this.textRenderer, state, px, sy + 2, enabled ? VeloAnim.lerpArgb(color, 0xFFFFFFFF, 0.3f) : MenuKit.TEXT_2);
		drawSwitch(context, px + pw - 22, sy, 22, 12, enabled, color);
		hits.add(new VeloUi.Hit(px, sy - 2, pw, 16, () -> {
			module.setEnabled(!module.isEnabled());
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
			QuickToggles.touch(module.id());
		}));
		int cy = sy + 22;
		// What it does.
		List<Text> lines = MenuKit.wrap(this.textRenderer, module.description(), MenuKit::ui, pw);
		for (int i = 0; i < Math.min(4, lines.size()); i++) {
			Text line = lines.get(i);
			if (i == 3 && lines.size() > 4) {
				line = MenuKit.fit(this.textRenderer, MenuKit.ui(line.getString() + " " + lines.get(4).getString()), pw);
			}
			MenuKit.text(context, this.textRenderer, line, px, cy, MenuKit.TEXT_2);
			cy += 11;
		}
		cy += 8;
		// Its styles, one click each.
		List<ConfigField> fields = module instanceof Configurable configurable ? configurable.configFields() : List.of();
		ConfigField.ChoiceField style = null;
		List<ConfigField.ToggleField> toggles = new ArrayList<>();
		for (ConfigField field : fields) {
			if (field instanceof ConfigField.ChoiceField choice && style == null && choice.label().equalsIgnoreCase("Style")) {
				style = choice;
			} else if (field instanceof ConfigField.ToggleField toggle && toggles.size() < 4) {
				toggles.add(toggle);
			}
		}
		int buttonsTop = contentBottom() - 12 - 20 - (module instanceof HudModule ? 24 : 0);
		int shownFields = 0;
		if (style != null && cy + 30 < buttonsTop) {
			shownFields++;
			MenuKit.text(context, this.textRenderer, MenuKit.small("STYLE"), px, cy, MenuKit.TEXT_3);
			cy += 11;
			int bx = px;
			String current = style.get().get();
			for (String option : style.options()) {
				Text label = MenuKit.uiBold(option);
				int bw = Math.min(pw, this.textRenderer.getWidth(label) + 14);
				if (bx + bw > px + pw) {
					bx = px;
					cy += 20;
				}
				if (cy + 16 > buttonsTop - 8) {
					break;
				}
				boolean on = option.equals(current);
				boolean hover = MenuKit.inside(mouseX, mouseY, bx, cy, bw, 16);
				VeloDraw.fillRounded(context, bx, cy, bw, 16, 6, on ? VeloUi.withAlpha(color, 0x34) : hover ? 0x16FFFFFF : 0x0AFFFFFF);
				VeloDraw.strokeRounded(context, bx, cy, bw, 16, 6, on ? VeloUi.withAlpha(color, 0xC0) : 0x18FFFFFF);
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, label, bw - 12), bx + 7, cy + 4, on ? 0xFFFFFFFF : 0xFFB4B4BE);
				ConfigField.ChoiceField field = style;
				hits.add(new VeloUi.Hit(bx, cy, bw, 16, () -> {
					field.set().accept(option);
					net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
				}));
				bx += bw + 4;
			}
			cy += 26;
		}
		// A few of its switches.
		if (!toggles.isEmpty() && cy + 30 < buttonsTop) {
			MenuKit.text(context, this.textRenderer, MenuKit.small("QUICK SETTINGS"), px, cy, MenuKit.TEXT_3);
			cy += 12;
			for (ConfigField.ToggleField toggle : toggles) {
				if (cy + 14 > buttonsTop - 6) {
					break;
				}
				boolean on = toggle.get().getAsBoolean();
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(toggle.label()), pw - 30), px, cy + 2, MenuKit.TEXT);
				drawSwitch(context, px + pw - 18, cy + 1, 18, 10, on, color);
				hits.add(new VeloUi.Hit(px, cy - 1, pw, 14, () -> {
					toggle.set().accept(!toggle.get().getAsBoolean());
					net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
				}));
				shownFields++;
				cy += 16;
			}
			cy += 6;
		}
		// The drawer only shows a few: say so, and lead to the rest.
		int moreFields = (int) fields.stream().filter(f -> !(f instanceof ConfigField.HeaderField)).count() - shownFields;
		if (moreFields > 0 && cy + 14 < buttonsTop) {
			boolean hover = MenuKit.inside(mouseX, mouseY, px, cy - 1, pw, 14);
			for (int d = 0; d < 3; d++) {
				VeloDraw.fillCircle(context, px + 2.5f + d * 4f, cy + 6f, 1.3f, hover ? color : MenuKit.TEXT_3);
			}
			MenuKit.text(context, this.textRenderer, MenuKit.ui(moreFields + " more setting" + (moreFields == 1 ? "" : "s") + " in All settings"),
					px + 16, cy + 2, hover ? MenuKit.TEXT : MenuKit.TEXT_3);
			hits.add(new VeloUi.Hit(px, cy - 1, pw, 14, () -> openSettings(module)));
			cy += 20;
		}
		// Favourite (shows in the pause menu's quick toggles).
		boolean favorite = QuickToggles.isFavorite(module.id());
		if (cy + 14 < buttonsTop) {
			boolean hover = MenuKit.inside(mouseX, mouseY, px, cy, pw, 14);
			VeloDraw.fillCircle(context, px + 4f, cy + 6f, 4f, favorite ? MenuKit.GOLD : 0x30FFFFFF);
			if (!favorite) {
				VeloDraw.fillCircle(context, px + 4f, cy + 6f, 2.6f, 0xFF16161B);
			}
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(favorite ? "In your pause menu" : "Add to pause menu"), px + 13, cy + 2,
					favorite ? MenuKit.GOLD : hover ? MenuKit.TEXT : MenuKit.TEXT_2);
			hits.add(new VeloUi.Hit(px, cy, pw, 14, () -> QuickToggles.setFavorite(module.id(), !favorite)));
		}
		// Buttons.
		int by = buttonsTop;
		if (module instanceof HudModule) {
			button(context, px, by, pw, "Place on screen", false, color, mouseX, mouseY, () -> this.client.setScreen(new HudEditScreen(this)));
			by += 24;
		}
		button(context, px, by, pw, moreFields > 0 ? "All settings (+" + moreFields + ")" : "All settings", true, color, mouseX, mouseY, () -> openSettings(module));
	}

	private void button(DrawContext context, int x, int y, int w, String text, boolean primary, int color, int mouseX, int mouseY, Runnable action) {
		boolean hover = MenuKit.inside(mouseX, mouseY, x, y, w, 20);
		if (primary) {
			VeloDraw.shadow(context, x, y, w, 20, 7, hover ? 12 : 8, 3, VeloUi.withAlpha(color, hover ? 0x80 : 0x50));
			VeloDraw.fillRoundedGradient(context, x, y, w, 20, 7, VeloAnim.lerpArgb(color, 0xFFFFFFFF, hover ? 0.18f : 0.08f), color);
		} else {
			VeloDraw.fillRounded(context, x, y, w, 20, 7, hover ? 0x1AFFFFFF : 0x0DFFFFFF);
			VeloDraw.strokeRounded(context, x, y, w, 20, 7, 0x24FFFFFF);
		}
		Text label = MenuKit.uiBold(text);
		MenuKit.text(context, this.textRenderer, label, x + (w - this.textRenderer.getWidth(label)) / 2, y + 6, primary ? MenuKit.onAccent() : MenuKit.TEXT);
		hits.add(new VeloUi.Hit(x, y, w, 20, action));
	}

	private static void drawSwitch(DrawContext context, int x, int y, int w, int h, boolean on, int color) {
		if (on) {
			VeloDraw.shadow(context, x, y, w, h, h / 2, 6, 1, VeloUi.withAlpha(color, 0x60));
		}
		VeloDraw.fillRounded(context, x, y, w, h, h / 2, on ? color : 0xFF3A3A42);
		float knob = h - 4;
		VeloDraw.fillCircle(context, x + 2 + knob / 2 + (on ? w - 4 - knob : 0), y + h / 2f, knob / 2, on ? 0xFFFFFFFF : 0xFFC9C9D1);
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
				this.client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
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
