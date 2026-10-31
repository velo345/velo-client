package net.veloclient.velo.dev.storage;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.dev.storage.StorageIndex.Hit;
import net.veloclient.velo.dev.storage.StorageIndex.TradeHit;
import net.veloclient.velo.dev.storage.StorageRecipes.Ingredient;
import net.veloclient.velo.dev.storage.StorageRecipes.Recipe;
import net.veloclient.velo.dev.storage.StorageStore.Container;
import net.veloclient.velo.dev.storage.StorageStore.Item;
import net.veloclient.velo.dev.storage.StorageStore.Offer;
import net.veloclient.velo.dev.storage.StorageStore.Station;
import net.veloclient.velo.dev.storage.StorageStore.Trader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Storage menu (Y): your remembered storage for the world you're in (or any other world / server you pick).
 * Items: search, every container an item is in (through shulker boxes and bundles), who sells it and its recipe with
 * where the ingredients are. Containers, Traders, Enchants and Stations list the rest. Clicking a place points you
 * there in the world.
 */
public final class StorageScreen extends VeloWindow {

	private static final String[] TABS = {"Items", "Containers", "Traders", "Enchants", "Stations"};
	private static final int CELL = 20;
	private static int tab;

	private String serverKey;
	private String worldKey;
	private TextFieldWidget searchField;
	private String search = "";
	private String selected;
	private final Deque<String> history = new ArrayDeque<>();
	private int recipeIndex;
	/** How many you want of the selected item / how often you want to craft its recipe (for the route). */
	private int wantIndex;
	private int craftIndex;
	private static final int[] AMOUNTS = {1, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 1728};
	private StorageIndex.Route routeCache;
	private String routeFor = "";
	private double gridScroll;
	private double detailScroll;
	private double listScroll;
	private int gridHeight;
	private int detailHeight;
	private int listHeight;
	private int gridSplit;
	private StorageIndex index;
	private int indexVersion = -1;
	private int partyVersion = -1;
	private String indexFor = "";
	private long indexTime;
	private List<String[]> enchantments;
	private List<String> allItems;
	private String confirmKey;
	private long confirmUntil;
	private String feedback = "";
	private long feedbackUntil;
	private ItemStack hoverStack;
	private int hoverX, hoverY;
	private final List<VeloUi.Hit> hits = new ArrayList<>();

	public StorageScreen(Screen parent) {
		super(Text.literal("Storage"), 680, 440);
		returnTo(parent);
		serverKey = StorageStore.currentServerKey();
		StorageStore.current();
		worldKey = StorageStore.currentWorldKey();
		if (serverKey == null) {
			List<String> servers = StorageStore.knownServers();
			serverKey = servers.isEmpty() ? null : servers.get(0);
			worldKey = firstWorld();
		}
		StorageRecipes.loadAsync();
	}

	// ---- Dev-only (screenshot tour) ----

	public void tourSearch(String query) {
		searchField.setText(query);
		selected = null;
	}

	public void tourSelect(String key) {
		select(key, false);
	}

	public void tourTab(int index) {
		selectHeaderTab(index);
	}

	public static void tourSetTab(int index) {
		tab = index;
	}

	// ---- Header tabs ----

	@Override
	protected String[] headerTabs() {
		return TABS;
	}

	@Override
	protected int headerTab() {
		return tab;
	}

	@Override
	protected void selectHeaderTab(int index) {
		tab = index;
		listScroll = 0;
	}

	@Override
	protected void layoutContent() {
		if (searchField != null) {
			search = searchField.getText();
		}
		this.clearChildren();
		searchField = new TextFieldWidget(this.textRenderer, contentX() + 20, contentY() + 32, 200, 14, Text.literal("Search"));
		searchField.setDrawsBackground(false);
		searchField.setMaxLength(64);
		searchField.setPlaceholder(VeloUi.placeholder("Search items, enchantments, traders..."));
		searchField.setText(search);
		searchField.setChangedListener(value -> {
			gridScroll = 0;
			listScroll = 0;
		});
		addDrawableChild(searchField);
		setInitialFocus(searchField);
	}

	// ---- Data ----

	private String firstWorld() {
		StorageStore.Server server = StorageStore.server(serverKey);
		return server == null || server.worlds.isEmpty() ? null : server.worlds.keySet().iterator().next();
	}

	private boolean viewingHere() {
		return serverKey != null && serverKey.equals(StorageStore.currentServerKey()) && worldKey != null
				&& worldKey.equals(StorageStore.currentWorldKey());
	}

	private StorageStore.World world() {
		StorageStore.Server server = StorageStore.server(serverKey);
		return server == null || worldKey == null ? null : server.worlds.get(worldKey);
	}

	private StorageIndex index() {
		String key = serverKey + "|" + worldKey;
		boolean here = viewingHere();
		// Your own inventory is live, so refresh it once a second while looking at the world you're in.
		if (index == null || indexVersion != StorageStore.version() || partyVersion != StoragePartySync.version() || !key.equals(indexFor)
				|| here && System.currentTimeMillis() - indexTime > 1000) {
			index = new StorageIndex(world(), here, StoragePartySync.sharesFor(serverKey, worldKey));
			indexVersion = StorageStore.version();
			partyVersion = StoragePartySync.version();
			indexFor = key;
			indexTime = System.currentTimeMillis();
		}
		return index;
	}

	private String query() {
		return (searchField == null ? search : searchField.getText()).toLowerCase(Locale.ROOT).strip();
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		hoverStack = null;
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();

		drawWorldPicker(context, x, y, width, mouseX, mouseY, theme);

		int searchY = y + 26;
		VeloUi.Hit clear = null;
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, searchY, width, 20, searchField.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, searchY, width, 20));
		searchField.setWidth(Math.max(20, width - 60));
		VeloDraw.searchGlyph(context, x + 10f, searchY + 10f, 9f, VeloUi.muted());
		if (!query().isEmpty()) {
			clear = VeloUi.pill(context, x + width - 22, searchY + 3, 18, 14, "x", 0, mouseX, mouseY, () -> searchField.setText(""));
			hits.add(clear);
		}

		int top = searchY + 28;
		int bottom = contentBottom() - 14;
		if (world() == null) {
			String empty = serverKey == null ? "Join a world and open some chests - they'll show up here."
					: "Nothing remembered here yet. Open chests, barrels, shulker boxes or your ender chest and trade with villagers.";
			int lineY = top + 40;
			for (String line : VeloUi.wrap(empty, width - 80)) {
				context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), x + width / 2, lineY, VeloUi.muted());
				lineY += 11;
			}
		} else {
			switch (tab) {
				case 0 -> drawItemsTab(context, x, top, width, bottom, mouseX, mouseY, theme);
				case 1 -> drawContainersTab(context, x, top, width, bottom, mouseX, mouseY, theme);
				case 2 -> drawTradersTab(context, x, top, width, bottom, mouseX, mouseY, theme);
				case 3 -> drawEnchantsTab(context, x, top, width, bottom, mouseX, mouseY, theme);
				default -> drawStationsTab(context, x, top, width, bottom, mouseX, mouseY, theme);
			}
		}

		String footer;
		if (System.currentTimeMillis() < feedbackUntil) {
			footer = feedback;
		} else if (!StorageCapture.lastSkip().isEmpty()) {
			footer = StorageCapture.lastSkip();
		} else {
			footer = "Only containers you open are remembered - server shop menus are left out.";
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(footer, width), x, contentBottom() - 8, VeloUi.muted());
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (hoverStack != null) {
			StorageMc.drawTooltip(context, hoverStack, hoverX, hoverY);
		}
	}

	private void drawWorldPicker(DrawContext context, int x, int y, int width, int mouseX, int mouseY, Theme theme) {
		StorageStore.Server server = StorageStore.server(serverKey);
		List<String> worlds = server == null ? List.of() : new ArrayList<>(server.worlds.keySet());
		int serverWidth = Math.min(170, width / 4);
		int partyWidth = Math.min(120, width / 5);
		int pickerWidth = width - serverWidth - partyWidth - 62;
		hits.add(VeloUi.pill(context, x, y, 20, 20, "<", 0, mouseX, mouseY, () -> cycleWorld(worlds, -1)));
		VeloDraw.fillRounded(context, x + 24, y, pickerWidth, 20, 5, VeloUi.withAlpha(0xFF000000, 0x40));
		String label;
		StorageStore.World world = world();
		if (world == null) {
			label = "No world";
		} else {
			label = world.name + (worlds.size() > 1 ? "  (" + (worlds.indexOf(worldKey) + 1) + "/" + worlds.size() + ")" : "")
					+ (viewingHere() ? "  - you are here" : "  - last here " + VeloUi.ago(world.lastSeen));
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(label, pickerWidth - 14), x + 32, y + 6, theme.text());
		hits.add(VeloUi.pill(context, x + 28 + pickerWidth, y, 20, 20, ">", 0, mouseX, mouseY, () -> cycleWorld(worlds, 1)));
		String serverLabel = serverKey == null ? "No server" : StorageStore.serverLabel(serverKey);
		hits.add(VeloUi.pill(context, x + width - serverWidth, y, serverWidth, 20, serverLabel, 0, mouseX, mouseY, this::cycleServer));
		boolean sync = StorageMemoryModule.partySync();
		String party = !sync ? "Party sync: off" : !net.veloclient.velo.client.network.SocialClient.inParty() ? "Party sync: no party"
				: "Party sync: " + StoragePartySync.sharingCount() + " sharing";
		hits.add(VeloUi.pill(context, x + width - serverWidth - partyWidth - 6, y, partyWidth, 20, party, sync ? 1 : 0, mouseX, mouseY, () -> {
			StorageMemoryModule.setPartySync(!sync);
			flash(sync ? "Party sync off - nothing is shared." : "Party sync on - your Velo party sees this world's storage and you see theirs.");
		}));
	}

	private void cycleWorld(List<String> worlds, int direction) {
		if (worlds.isEmpty()) {
			return;
		}
		int i = Math.floorMod(worlds.indexOf(worldKey) + direction, worlds.size());
		worldKey = worlds.get(i);
		resetScroll();
	}

	private void cycleServer() {
		List<String> servers = StorageStore.knownServers();
		if (servers.isEmpty()) {
			return;
		}
		int i = Math.floorMod(servers.indexOf(serverKey) + 1, servers.size());
		serverKey = servers.get(i);
		worldKey = serverKey.equals(StorageStore.currentServerKey()) ? StorageStore.currentWorldKey() : firstWorld();
		resetScroll();
	}

	private void resetScroll() {
		gridScroll = 0;
		detailScroll = 0;
		listScroll = 0;
		selected = null;
		history.clear();
	}

	// ---- Items tab ----

	private void drawItemsTab(DrawContext context, int x, int top, int width, int bottom, int mouseX, int mouseY, Theme theme) {
		StorageIndex index = index();
		String query = query();
		int gridWidth = Math.max(CELL * 5, (int) (width * 0.46));
		int cols = Math.max(1, (gridWidth - 6) / CELL);
		gridSplit = x + gridWidth;
		Map<String, Integer> totals = index.totals(query);
		List<String> missing = new ArrayList<>();
		if (!query.isEmpty()) {
			if (allItems == null) {
				allItems = StorageMc.allItemIds();
				allItems.remove("minecraft:air");
			}
			for (String id : allItems) {
				if (!totals.containsKey(id) && StorageIndex.name(id).toLowerCase(Locale.ROOT).contains(query)) {
					missing.add(id);
					if (missing.size() >= 120) {
						break;
					}
				}
			}
		}
		if (selected == null && !totals.isEmpty() && !query.isEmpty()) {
			selected = totals.keySet().iterator().next();
		} else if (selected == null && totals.isEmpty() && !missing.isEmpty()) {
			selected = missing.get(0);
		}

		VeloDraw.fillRounded(context, x, top, gridWidth, bottom - top, 6, VeloUi.withAlpha(0xFF000000, 0x30));
		context.enableScissor(x, top, x + gridWidth, bottom);
		int cy = top + 4 - (int) gridScroll;
		int startY = cy;
		String header = query.isEmpty() ? "Everything here (" + totals.size() + ")" : totals.isEmpty() ? "Not in your storage" : "In your storage";
		context.drawTextWithShadow(this.textRenderer, header, x + 6, cy + 2, VeloUi.muted());
		cy += 13;
		cy = drawGrid(context, totals, x + 3, cy, cols, top, bottom, mouseX, mouseY, theme, false);
		if (!missing.isEmpty()) {
			cy += 6;
			if (!totals.isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, "Not stored - see the recipe", x + 6, cy + 2, VeloUi.muted());
				cy += 13;
			}
			Map<String, Integer> zero = new java.util.LinkedHashMap<>();
			for (String id : missing) {
				zero.put(id, 0);
			}
			cy = drawGrid(context, zero, x + 3, cy, cols, top, bottom, mouseX, mouseY, theme, true);
		}
		if (totals.isEmpty() && missing.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, query.isEmpty() ? "Open some containers first." : "No item called that.", x + 6, cy + 4, VeloUi.muted());
			cy += 16;
		}
		context.disableScissor();
		gridHeight = cy - startY + 4;
		scrollbar(context, x + gridWidth - 3, top, bottom, gridScroll, gridHeight, theme);

		int detailX = x + gridWidth + 8;
		int detailWidth = width - gridWidth - 8;
		VeloDraw.fillRounded(context, detailX, top, detailWidth, bottom - top, 6, VeloUi.withAlpha(0xFF000000, 0x30));
		if (selected == null) {
			int lineY = top + 30;
			for (String line : VeloUi.wrap("Pick an item, or search for one - even one you don't have, to see its recipe and where the ingredients are.", detailWidth - 30)) {
				context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), detailX + detailWidth / 2, lineY, VeloUi.muted());
				lineY += 11;
			}
			return;
		}
		context.enableScissor(detailX, top, detailX + detailWidth, bottom);
		int start = top + 6 - (int) detailScroll;
		int end = drawDetail(context, index, selected, detailX + 8, start, detailWidth - 16, top, bottom, mouseX, mouseY, theme);
		context.disableScissor();
		detailHeight = end - start + 8;
		scrollbar(context, detailX + detailWidth - 3, top, bottom, detailScroll, detailHeight, theme);
	}

	private int drawGrid(DrawContext context, Map<String, Integer> items, int x, int y, int cols, int clipTop, int clipBottom,
			int mouseX, int mouseY, Theme theme, boolean ghost) {
		int i = 0;
		for (Map.Entry<String, Integer> entry : items.entrySet()) {
			int cx = x + (i % cols) * CELL;
			int cy = y + (i / cols) * CELL;
			i++;
			if (cy + CELL < clipTop || cy > clipBottom) {
				continue;
			}
			String id = entry.getKey();
			boolean hovered = VeloUi.inside(mouseX, mouseY, cx, cy, CELL - 1, CELL - 1) && mouseY >= clipTop && mouseY < clipBottom;
			int bg = id.equals(selected) ? VeloUi.withAlpha(theme.accentStart(), 0x90) : hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x22)
					: VeloUi.withAlpha(0xFFFFFFFF, ghost ? 0x06 : 0x0E);
			VeloDraw.fillRounded(context, cx, cy, CELL - 1, CELL - 1, 3, bg);
			ItemStack stack = displayStack(id, 1);
			if (!StorageMc.isEmpty(stack)) {
				StorageMc.drawItem(context, stack, cx + 1, cy + 1, ghost ? "" : compact(entry.getValue()));
				if (ghost) {
					context.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0x50000000);
				}
			}
			if (hovered) {
				hoverStack = stack;
				hoverX = mouseX;
				hoverY = mouseY;
				hits.add(new VeloUi.Hit(cx, cy, CELL - 1, CELL - 1, () -> select(id, false)));
			}
		}
		return y + ((items.size() + cols - 1) / cols) * CELL;
	}

	/** The icon for a key: custom name and server model included, so custom items look like themselves. */
	private ItemStack displayStack(String key, int count) {
		ItemStack stack = StorageMc.stack(StorageIndex.idOf(key), count);
		Item sample = index == null ? null : index.sample(key);
		return StorageMc.decorate(stack, StorageIndex.customOf(key), sample == null ? null : sample.m);
	}

	private ItemStack displayStack(Item item) {
		return StorageMc.decorate(StorageMc.stack(item.id, item.c), item.n, item.m);
	}

	private void select(String id, boolean remember) {
		if (remember && selected != null && !selected.equals(id)) {
			history.push(selected);
		}
		if (!remember) {
			history.clear();
		}
		selected = id;
		recipeIndex = 0;
		wantIndex = 0;
		craftIndex = 0;
		detailScroll = 0;
	}

	/** The item's page: header, where it is, who sells it, its recipe. Returns the y after the last line. */
	private int drawDetail(DrawContext context, StorageIndex index, String id, int x, int y, int width, int clipTop, int clipBottom,
			int mouseX, int mouseY, Theme theme) {
		ItemStack stack = displayStack(id, 1);
		int total = index.count(id);
		boolean custom = StorageIndex.customOf(id) != null || index.sample(id) != null && index.sample(id).m != null;
		if (!history.isEmpty()) {
			addClipped(VeloUi.pill(context, x + width - 46, y, 46, 16, "< Back", 0, mouseX, mouseY, () -> {
				selected = history.pop();
				recipeIndex = 0;
				detailScroll = 0;
			}), clipTop, clipBottom);
		}
		VeloDraw.fillRounded(context, x, y, 24, 24, 5, VeloUi.withAlpha(0xFF000000, 0x50));
		if (!StorageMc.isEmpty(stack)) {
			StorageMc.drawItem(context, stack, x + 4, y + 4, "");
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(StorageIndex.keyName(id), width - 84), x + 30, y + 3, theme.text());
		String have = total > 0 ? "You have " + total + (index.here() ? "" : " on that world") : "Not in your storage";
		context.drawTextWithShadow(this.textRenderer, have, x + 30, y + 14, total > 0 ? 0xFF6FDC8C : 0xFFE5A04D);
		y += 32;

		List<Hit> where = index.where(id);
		if (!where.isEmpty()) {
			y = section(context, "Stored in", where.size(), x, y, width);
			int shown = 0;
			for (Hit hit : where) {
				if (shown++ >= 40) {
					context.drawTextWithShadow(this.textRenderer, "+ " + (where.size() - 40) + " more", x + 4, y + 2, VeloUi.muted());
					y += 12;
					break;
				}
				y = drawHitRow(context, index, hit, x, y, width, clipTop, clipBottom, mouseX, mouseY, theme);
			}
			y += 4;
		}

		List<TradeHit> sellers = index.sellers(id);
		if (!sellers.isEmpty()) {
			y = section(context, "Villagers selling it", sellers.size(), x, y, width);
			for (TradeHit trade : sellers) {
				y = drawTradeRow(context, index, trade, x, y, width, clipTop, clipBottom, mouseX, mouseY, theme);
			}
			y += 4;
		}

		if (!custom) {
			y = drawItemRoute(context, index, id, x, y, width, clipTop, clipBottom, mouseX, mouseY, theme);
			y = drawRecipe(context, index, id, x, y, width, clipTop, clipBottom, mouseX, mouseY, theme);
		}

		if (where.isEmpty() && sellers.isEmpty() && (custom || StorageRecipes.ready() && StorageRecipes.forItem(id).isEmpty())) {
			for (String line : VeloUi.wrap(custom ? "A custom item - it's only where you've seen it."
					: "Not stored, no villager you've traded with sells it, and it can't be crafted - it has to be found, mined or dropped.", width - 8)) {
				context.drawTextWithShadow(this.textRenderer, line, x + 2, y, VeloUi.muted());
				y += 11;
			}
		}
		return y;
	}

	private int section(DrawContext context, String title, int count, int x, int y, int width) {
		String label = count > 0 ? title + "  " + count : title;
		context.drawTextWithShadow(this.textRenderer, label, x, y, ThemeManager.active().accentStart());
		context.fill(x + this.textRenderer.getWidth(label) + 6, y + 4, x + width, y + 5, VeloUi.withAlpha(0xFFFFFFFF, 0x14));
		return y + 13;
	}

	private int drawHitRow(DrawContext context, StorageIndex index, Hit hit, int x, int y, int width, int clipTop, int clipBottom,
			int mouseX, int mouseY, Theme theme) {
		int h = 24;
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, h) && mouseY >= clipTop && mouseY < clipBottom;
		VeloDraw.fillRounded(context, x, y, width, h - 2, 5, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x16) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
		String icon;
		String place;
		String where;
		switch (hit.where()) {
			case INVENTORY -> {
				icon = "minecraft:player_head";
				place = "Your inventory";
				where = "";
			}
			case ENDER -> {
				icon = "minecraft:ender_chest";
				place = StorageIndex.name("minecraft:ender_chest");
				where = "any ender chest";
			}
			case PARTY_INVENTORY -> {
				icon = "minecraft:player_head";
				place = hit.owner() + "'s inventory";
				where = "party member - ask them";
			}
			case PARTY_ENDER -> {
				icon = "minecraft:ender_chest";
				place = hit.owner() + "'s ender chest";
				where = "party member - ask them";
			}
			default -> {
				Container c = hit.container();
				icon = c.block == null ? "minecraft:chest" : c.block;
				place = StorageHighlight.describe(c.block);
				where = c.x + ", " + c.y + ", " + c.z + "  -  " + index.distanceLabel(c.dim, c.x + 0.5, c.y + 0.5, c.z + 0.5)
						+ (c.by != null ? "  -  seen by " + c.by : "");
			}
		}
		ItemStack iconStack = StorageMc.stack(icon, 1);
		if (!StorageMc.isEmpty(iconStack)) {
			StorageMc.drawItem(context, iconStack, x + 3, y + 3, "");
		}
		StringBuilder path = new StringBuilder();
		for (String outer : hit.path()) {
			path.append(path.isEmpty() ? "in " : " > ").append(StorageIndex.name(outer));
		}
		String detail = path.isEmpty() ? where : path + (where.isEmpty() ? "" : "  -  " + where);
		String count = "x" + hit.item().c;
		int countWidth = this.textRenderer.getWidth(count);
		String name = place + (hit.item().n != null ? "  \"" + hit.item().n + "\"" : "");
		if (hit.item().e != null) {
			name += "  " + enchantSummary(hit.item().e);
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(name, width - 34 - countWidth), x + 24, y + 2, theme.text());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(detail, width - 30), x + 24, y + 12, VeloUi.muted());
		context.drawTextWithShadow(this.textRenderer, count, x + width - countWidth - 5, y + 2, 0xFFFFFFFF);
		if (hovered && hit.owner() == null) {
			hits.add(new VeloUi.Hit(x, Math.max(y, clipTop), width, h - 2, () -> showHit(hit)));
		}
		return y + h;
	}

	private String enchantSummary(List<String> enchants) {
		StringBuilder out = new StringBuilder();
		for (String enchant : enchants) {
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append(StorageIndex.enchantLabel(enchant));
		}
		return out.toString();
	}

	private int drawTradeRow(DrawContext context, StorageIndex index, TradeHit trade, int x, int y, int width, int clipTop, int clipBottom,
			int mouseX, int mouseY, Theme theme) {
		int h = 24;
		Trader trader = trade.trader();
		Offer offer = trade.offer();
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, h) && mouseY >= clipTop && mouseY < clipBottom;
		VeloDraw.fillRounded(context, x, y, width, h - 2, 5, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x16) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
		int ix = x + 3;
		ix = drawOfferItem(context, offer.a, ix, y + 3, mouseX, mouseY, clipTop, clipBottom);
		if (offer.b != null) {
			ix = drawOfferItem(context, offer.b, ix, y + 3, mouseX, mouseY, clipTop, clipBottom);
		}
		context.drawTextWithShadow(this.textRenderer, ">", ix + 1, y + 7, VeloUi.muted());
		ix += 9;
		ix = drawOfferItem(context, offer.r, ix, y + 3, mouseX, mouseY, clipTop, clipBottom);
		String who = traderLabel(trader);
		String detail = (int) trader.x + ", " + (int) trader.y + ", " + (int) trader.z + "  -  " + index.distanceLabel(trader.dim, trader.x, trader.y, trader.z)
				+ (offer.max > 0 && offer.uses >= offer.max ? "  -  sold out" : "");
		if (offer.r != null && offer.r.e != null) {
			who += "  " + enchantSummary(offer.r.e);
		}
		if (trader.by != null) {
			detail += "  -  seen by " + trader.by;
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(who, x + width - ix - 6), ix + 4, y + 2, theme.text());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(detail, x + width - ix - 6), ix + 4, y + 12, VeloUi.muted());
		if (hovered && hoverStack == null) {
			hits.add(new VeloUi.Hit(x, Math.max(y, clipTop), width, h - 2, () -> showTrader(trader, offer.r == null ? null : offer.r.id)));
		}
		return y + h;
	}

	private int drawOfferItem(DrawContext context, Item item, int x, int y, int mouseX, int mouseY, int clipTop, int clipBottom) {
		if (item == null) {
			return x;
		}
		ItemStack stack = displayStack(item);
		if (!StorageMc.isEmpty(stack)) {
			StorageMc.drawItem(context, stack, x, y, null);
			if (VeloUi.inside(mouseX, mouseY, x, y, 16, 16) && mouseY >= clipTop && mouseY < clipBottom) {
				hoverStack = stack;
				hoverX = mouseX;
				hoverY = mouseY;
				String id = item.key();
				hits.add(new VeloUi.Hit(x, y, 16, 16, () -> {
					tab = 0;
					select(id, true);
				}));
			}
		}
		return x + 17;
	}

	private String traderLabel(Trader trader) {
		String profession = trader.wandering ? "Wandering Trader" : StorageStore.pretty(trader.profession);
		String level = trader.wandering ? "" : "  " + levelName(trader.level);
		return (trader.name != null ? trader.name + " (" + profession + ")" : profession) + level;
	}

	private static String levelName(int level) {
		return switch (level) {
			case 1 -> "Novice";
			case 2 -> "Apprentice";
			case 3 -> "Journeyman";
			case 4 -> "Expert";
			case 5 -> "Master";
			default -> "";
		};
	}

	private int drawRecipe(DrawContext context, StorageIndex index, String id, int x, int y, int width, int clipTop, int clipBottom,
			int mouseX, int mouseY, Theme theme) {
		if (!StorageRecipes.ready()) {
			context.drawTextWithShadow(this.textRenderer, "Loading recipes...", x, y, VeloUi.muted());
			return y + 14;
		}
		List<Recipe> recipes = StorageRecipes.forItem(id);
		if (recipes.isEmpty()) {
			return y;
		}
		recipeIndex = Math.floorMod(recipeIndex, recipes.size());
		Recipe recipe = recipes.get(recipeIndex);
		y = section(context, "Recipe - " + kindLabel(recipe.kind()), 0, x, y, width - (recipes.size() > 1 ? 70 : 0));
		if (recipes.size() > 1) {
			int px = x + width - 64;
			addClipped(VeloUi.pill(context, px, y - 15, 16, 14, "<", 0, mouseX, mouseY, () -> recipeIndex--), clipTop, clipBottom);
			String pos = (recipeIndex + 1) + "/" + recipes.size();
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(pos), px + 32, y - 12, VeloUi.muted());
			addClipped(VeloUi.pill(context, px + 48, y - 15, 16, 14, ">", 0, mouseX, mouseY, () -> recipeIndex++), clipTop, clipBottom);
		}

		// The grid (or the inputs in a row), an arrow, the result.
		int cols = recipe.shaped() ? recipe.width() : recipe.kind().equals("crafting") ? 3 : recipe.inputs().size();
		int rows = (recipe.inputs().size() + cols - 1) / Math.max(1, cols);
		int gx = x + 4;
		for (int i = 0; i < recipe.inputs().size(); i++) {
			int cx = gx + (i % cols) * 19;
			int cy = y + (i / cols) * 19;
			VeloDraw.fillRounded(context, cx, cy, 18, 18, 3, VeloUi.withAlpha(0xFF000000, 0x55));
			Ingredient ingredient = recipe.inputs().get(i);
			if (ingredient == null || ingredient.options().isEmpty()) {
				continue;
			}
			String option = shownOption(index, ingredient);
			ItemStack stack = StorageMc.stack(option, 1);
			if (!StorageMc.isEmpty(stack)) {
				StorageMc.drawItem(context, stack, cx + 1, cy + 1, "");
				if (VeloUi.inside(mouseX, mouseY, cx, cy, 18, 18) && mouseY >= clipTop && mouseY < clipBottom) {
					hoverStack = stack;
					hoverX = mouseX;
					hoverY = mouseY;
					hits.add(new VeloUi.Hit(cx, cy, 18, 18, () -> select(option, true)));
				}
			}
		}
		int gridW = cols * 19;
		int midY = y + rows * 19 / 2 - 9;
		VeloDraw.chevron(context, gx + gridW + 12, midY + 9, 5f, 2f, 0, VeloUi.muted());
		int rx = gx + gridW + 24;
		VeloDraw.fillRounded(context, rx, midY, 22, 22, 4, VeloUi.withAlpha(theme.accentStart(), 0x50));
		ItemStack result = StorageMc.stack(recipe.result(), recipe.count());
		if (!StorageMc.isEmpty(result)) {
			StorageMc.drawItem(context, result, rx + 3, midY + 3, null);
		}
		y += rows * 19 + 6;

		// Where you make it.
		String stationId = recipe.station();
		if (stationId == null) {
			context.drawTextWithShadow(this.textRenderer, "Fits your inventory crafting grid", x + 2, y, VeloUi.muted());
			y += 13;
		} else {
			Station station = index.nearestStation(stationId);
			String text = StorageIndex.name(stationId) + (station == null ? "  -  none remembered"
					: "  -  nearest " + index.distanceLabel(station.dim, station.x + 0.5, station.y + 0.5, station.z + 0.5)
					+ " (" + station.x + ", " + station.y + ", " + station.z + ")");
			boolean hovered = station != null && VeloUi.inside(mouseX, mouseY, x, y - 2, width, 12) && mouseY >= clipTop && mouseY < clipBottom;
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(text, width - 4), x + 2, y, hovered ? 0xFFFFFFFF : VeloUi.muted());
			if (hovered) {
				hits.add(new VeloUi.Hit(x, y - 2, width, 12, () -> showStation(station)));
			}
			y += 14;
		}

		// What you need and where it is.
		for (Map.Entry<Ingredient, Integer> need : recipe.needs().entrySet()) {
			Ingredient ingredient = need.getKey();
			if (ingredient.options().isEmpty()) {
				continue;
			}
			String option = shownOption(index, ingredient);
			int have = 0;
			for (String alternative : ingredient.options()) {
				have += index.usableCount(alternative);
			}
			int amount = need.getValue() * AMOUNTS[craftIndex];
			boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, 20) && mouseY >= clipTop && mouseY < clipBottom;
			VeloDraw.fillRounded(context, x, y, width, 19, 4, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x16) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
			ItemStack stack = StorageMc.stack(option, 1);
			if (!StorageMc.isEmpty(stack)) {
				StorageMc.drawItem(context, stack, x + 2, y + 1, "");
			}
			String name = (ingredient.label() != null ? ingredient.label() : StorageIndex.name(option)) + "  x" + amount;
			String haveText = have > 0 ? "have " + have : "none";
			String nearest = nearestPlace(index, ingredient);
			int haveWidth = this.textRenderer.getWidth(haveText);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(name + (nearest.isEmpty() ? "" : "  -  " + nearest), width - haveWidth - 30),
					x + 22, y + 6, theme.text());
			context.drawTextWithShadow(this.textRenderer, haveText, x + width - haveWidth - 5, y + 6, have >= amount ? 0xFF6FDC8C : have > 0 ? 0xFFE5C84D : 0xFFE5604D);
			if (hovered) {
				hits.add(new VeloUi.Hit(x, Math.max(y, clipTop), width, 19, () -> select(bestOption(index, ingredient), true)));
			}
			y += 21;
		}

		// How often, and the shortest walk past containers with enough of everything.
		y = stepper(context, "Craft", craftIndex, " x  (makes " + recipe.count() * AMOUNTS[craftIndex] + ")", x, y + 2, width, clipTop, clipBottom,
				mouseX, mouseY, delta -> craftIndex = Math.max(0, Math.min(AMOUNTS.length - 1, craftIndex + delta)));
		List<List<String>> options = new ArrayList<>();
		List<Integer> amounts = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (Map.Entry<Ingredient, Integer> need : recipe.needs().entrySet()) {
			if (need.getKey().options().isEmpty()) {
				continue;
			}
			options.add(need.getKey().options());
			amounts.add(need.getValue() * AMOUNTS[craftIndex]);
			labels.add(need.getKey().label() != null ? need.getKey().label() : StorageIndex.name(need.getKey().options().get(0)));
		}
		StorageIndex.Route route = route(index, "r:" + recipe.id() + ":" + craftIndex, options, amounts, labels);
		java.util.Set<String> frame = new java.util.HashSet<>();
		options.forEach(frame::addAll);
		y = drawRouteBox(context, index, route, frame, x, y, width, clipTop, clipBottom, mouseX, mouseY, theme);
		return y + 4;
	}

	/** A "label  [-] 64 [+]" row. */
	private int stepper(DrawContext context, String label, int index, String suffix, int x, int y, int width, int clipTop, int clipBottom,
			int mouseX, int mouseY, java.util.function.IntConsumer change) {
		context.drawTextWithShadow(this.textRenderer, label, x + 2, y + 4, VeloUi.muted());
		int bx = x + 6 + this.textRenderer.getWidth(label);
		addClipped(VeloUi.pill(context, bx, y, 16, 14, "-", 0, mouseX, mouseY, () -> change.accept(-1)), clipTop, clipBottom);
		String value = String.valueOf(AMOUNTS[index]);
		int valueWidth = Math.max(24, this.textRenderer.getWidth(value) + 8);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value), bx + 18 + valueWidth / 2, y + 4, ThemeManager.active().text());
		addClipped(VeloUi.pill(context, bx + 20 + valueWidth, y, 16, 14, "+", 0, mouseX, mouseY, () -> change.accept(1)), clipTop, clipBottom);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(suffix, width - (bx + 40 + valueWidth - x)), bx + 40 + valueWidth, y + 4, VeloUi.muted());
		return y + 18;
	}

	/** Route for N of the selected item. */
	private int drawItemRoute(DrawContext context, StorageIndex index, String id, int x, int y, int width, int clipTop, int clipBottom,
			int mouseX, int mouseY, Theme theme) {
		if (!index.here() || index.usableCount(id) == 0) {
			return y;
		}
		y = section(context, "Shortest way to get it", 0, x, y, width);
		y = stepper(context, "Need", wantIndex, "", x, y, width, clipTop, clipBottom, mouseX, mouseY,
				delta -> wantIndex = Math.max(0, Math.min(AMOUNTS.length - 1, wantIndex + delta)));
		StorageIndex.Route route = route(index, "i:" + id + ":" + wantIndex, List.of(List.of(id)), List.of(AMOUNTS[wantIndex]), List.of(StorageIndex.name(id)));
		return drawRouteBox(context, index, route, java.util.Set.of(id), x, y, width, clipTop, clipBottom, mouseX, mouseY, theme) + 4;
	}

	/** Routes are cached per request while you stand on the same block (the index refreshes every second here). */
	private StorageIndex.Route route(StorageIndex index, String key, List<List<String>> options, List<Integer> amounts, List<String> labels) {
		double[] me = StorageMc.playerPos();
		String full = key + "@" + indexTime + "@" + (me == null ? "" : (int) me[0] + "," + (int) me[1] + "," + (int) me[2]);
		if (!full.equals(routeFor)) {
			routeFor = full;
			routeCache = index.route(options, amounts, labels);
		}
		return routeCache;
	}

	private int drawRouteBox(DrawContext context, StorageIndex index, StorageIndex.Route route, java.util.Set<String> frame, int x, int y, int width,
			int clipTop, int clipBottom, int mouseX, int mouseY, Theme theme) {
		if (route == null) {
			if (!index.here()) {
				context.drawTextWithShadow(this.textRenderer, "Routes work in the world you're in.", x + 2, y, VeloUi.muted());
				return y + 13;
			}
			return y;
		}
		int top = y;
		int lines = route.stops().size() + (route.fromInventory().isEmpty() ? 0 : 1) + route.missing().size() + route.party().size() + 1;
		int h = lines * 11 + 8;
		VeloDraw.fillRounded(context, x, y, width, h, 5, VeloUi.withAlpha(theme.accentStart(), 0x22));
		y += 5;
		String head = route.stops().isEmpty() ? (route.missing().isEmpty() ? "You already carry enough." : "Not enough stored anywhere you can walk to.")
				: route.stops().size() + (route.stops().size() == 1 ? " stop" : " stops") + ", about " + Math.round(route.length()) + "m";
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(head, width - 60), x + 6, y, theme.text());
		if (!route.stops().isEmpty()) {
			List<StorageIndex.Stop> stops = route.stops();
			addClipped(VeloUi.pill(context, x + width - 50, top + 3, 46, 14, "Go", 1, mouseX, mouseY, () -> {
				StorageHighlight.route(worldKey, stops, frame);
				closeToGame();
			}), clipTop, clipBottom);
		}
		y += 11;
		if (!route.fromInventory().isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Already with you: " + takeText(route.fromInventory()), width - 12), x + 6, y, 0xFF6FDC8C);
			y += 11;
		}
		int n = 1;
		double[] at = StorageMc.playerPos();
		for (StorageIndex.Stop stop : route.stops()) {
			double d = at == null ? 0 : Math.sqrt(Math.pow(stop.x() - at[0], 2) + Math.pow(stop.y() - at[1], 2) + Math.pow(stop.z() - at[2], 2));
			at = new double[] {stop.x(), stop.y(), stop.z()};
			String line = n++ + ". " + stop.label() + (n == 2 ? " (" + Math.round(d) + "m)" : " (+" + Math.round(d) + "m)") + ": " + takeText(stop.take());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(line, width - 12), x + 6, y, 0xFFD6DCE6);
			y += 11;
		}
		for (Map.Entry<String, Integer> missing : route.missing().entrySet()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Still missing " + missing.getValue() + " " + missing.getKey(), width - 12), x + 6, y, 0xFFE5A04D);
			y += 11;
		}
		for (String mate : route.party()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(mate, width - 12), x + 6, y, 0xFF9FC6FF);
			y += 11;
		}
		return top + h + 4;
	}

	private static String takeText(Map<String, Integer> take) {
		StringBuilder out = new StringBuilder();
		take.forEach((id, count) -> out.append(out.isEmpty() ? "" : ", ").append(count).append(" ").append(StorageIndex.name(id)));
		return out.toString();
	}

	private static String kindLabel(String kind) {
		return switch (kind) {
			case "crafting" -> "Crafting";
			case "smelting" -> "Furnace";
			case "blasting" -> "Blast Furnace";
			case "smoking" -> "Smoker";
			case "campfire" -> "Campfire";
			case "stonecutting" -> "Stonecutter";
			case "smithing" -> "Smithing";
			default -> StorageStore.pretty(kind);
		};
	}

	/** The option you have most of, else the alternatives cycle every second. */
	private String shownOption(StorageIndex index, Ingredient ingredient) {
		String best = bestOption(index, ingredient);
		if (index.usableCount(best) > 0) {
			return best;
		}
		List<String> options = ingredient.options();
		return options.get((int) ((System.currentTimeMillis() / 1000) % options.size()));
	}

	private String bestOption(StorageIndex index, Ingredient ingredient) {
		String best = ingredient.options().get(0);
		int bestCount = 0;
		for (String option : ingredient.options()) {
			int count = index.usableCount(option);
			if (count > bestCount) {
				bestCount = count;
				best = option;
			}
		}
		return best;
	}

	private String nearestPlace(StorageIndex index, Ingredient ingredient) {
		Hit best = null;
		for (String option : ingredient.options()) {
			Hit hit = index.nearestUsable(option);
			if (hit != null && (best == null || index.rank(hit) < index.rank(best))) {
				best = hit;
			}
		}
		return best == null ? "" : nearestLabel(index, best);
	}


	// ---- Containers tab ----

	private void drawContainersTab(DrawContext context, int x, int top, int width, int bottom, int mouseX, int mouseY, Theme theme) {
		StorageIndex index = index();
		String query = query();
		List<Container> containers = new ArrayList<>(index.world().containers.values());
		if (!query.isEmpty()) {
			containers.removeIf(c -> c.items.stream().noneMatch(item -> deepMatch(item, query)));
		}
		containers.sort(Comparator.comparingDouble(c -> index.distance(c.dim, c.x, c.y, c.z)));
		List<Item> ender = index.world().ender;
		int y = top - (int) listScroll;
		int start = y;
		context.enableScissor(x, top, x + width, bottom);
		if (ender != null && (query.isEmpty() || ender.stream().anyMatch(item -> deepMatch(item, query)))) {
			y = drawContainerRow(context, index, null, ender, x, y, width - 6, top, bottom, mouseX, mouseY, theme);
		}
		for (Container container : containers) {
			if (y > bottom) {
				y += 44;
				continue;
			}
			y = drawContainerRow(context, index, container, container.items, x, y, width - 6, top, bottom, mouseX, mouseY, theme);
		}
		if (containers.isEmpty() && ender == null) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No containers remembered here yet."), x + width / 2, top + 30, VeloUi.muted());
		}
		context.disableScissor();
		listHeight = y - start;
		scrollbar(context, x + width - 3, top, bottom, listScroll, listHeight, theme);
	}

	private static boolean deepMatch(Item item, String query) {
		if (StorageIndex.matches(item, query)) {
			return true;
		}
		if (item.in != null) {
			for (Item nested : item.in) {
				if (deepMatch(nested, query)) {
					return true;
				}
			}
		}
		return false;
	}

	private int drawContainerRow(DrawContext context, StorageIndex index, Container container, List<Item> items, int x, int y, int width,
			int clipTop, int clipBottom, int mouseX, int mouseY, Theme theme) {
		int h = 44;
		if (y + h < clipTop) {
			return y + h;
		}
		boolean rowVisible = mouseY >= clipTop && mouseY < clipBottom;
		boolean hovered = rowVisible && VeloUi.inside(mouseX, mouseY, x, y, width, h - 3);
		VeloDraw.fillRounded(context, x, y, width, h - 3, 6, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
		String block = container == null ? "minecraft:ender_chest" : container.block == null ? "minecraft:chest" : container.block;
		ItemStack icon = StorageMc.stack(block, 1);
		if (!StorageMc.isEmpty(icon)) {
			StorageMc.drawItem(context, icon, x + 5, y + 4, "");
		}
		int stacks = items.size();
		String title = StorageHighlight.describe(block) + (container != null && container.other != null ? " (double)" : "");
		String detail = container == null ? "Shared by every ender chest  -  " + stacks + " stacks  -  seen " + VeloUi.ago(index.world().enderTime)
				: container.x + ", " + container.y + ", " + container.z + "  -  " + index.distanceLabel(container.dim, container.x + 0.5, container.y + 0.5, container.z + 0.5)
				+ "  -  " + stacks + " stacks  -  seen " + VeloUi.ago(container.time) + (container.by != null ? " by " + container.by : "");
		int actions = container == null ? 0 : 108;
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title, width - actions - 34), x + 26, y + 4, theme.text());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(detail, width - actions - 34), x + 26, y + 14, VeloUi.muted());
		int ix = x + 26;
		int max = Math.max(1, (width - 34 - actions) / 17);
		for (int i = 0; i < items.size() && i < max; i++) {
			Item item = items.get(i);
			ItemStack stack = displayStack(item);
			if (StorageMc.isEmpty(stack)) {
				continue;
			}
			int sy = y + 24;
			StorageMc.drawItem(context, stack, ix, sy, item.c > 99 ? compact(item.c) : null);
			if (rowVisible && VeloUi.inside(mouseX, mouseY, ix, sy, 16, 16)) {
				hoverStack = stack;
				hoverX = mouseX;
				hoverY = mouseY;
				String id = item.key();
				hits.add(new VeloUi.Hit(ix, sy, 16, 16, () -> {
					tab = 0;
					select(id, false);
				}));
			}
			ix += 17;
		}
		if (container != null && rowVisible) {
			hits.add(VeloUi.pill(context, x + width - 104, y + 12, 46, 16, "Show", 1, mouseX, mouseY, () -> showContainer(container, null)));
			hits.add(confirmPill(context, x + width - 54, y + 12, 50, "Forget", "c:" + container.x + "," + container.y + "," + container.z + container.dim,
					mouseX, mouseY, () -> {
						index.world().containers.entrySet().stream().filter(e -> e.getValue() == container).map(Map.Entry::getKey)
								.findFirst().ifPresent(key -> StorageStore.removeContainer(index.world(), key));
						StorageStore.markDirty(serverKey);
					}));
		}
		return y + h;
	}

	// ---- Traders tab ----

	private void drawTradersTab(DrawContext context, int x, int top, int width, int bottom, int mouseX, int mouseY, Theme theme) {
		StorageIndex index = index();
		String query = query();
		List<Trader> traders = new ArrayList<>(index.world().traders.values());
		if (!query.isEmpty()) {
			traders.removeIf(t -> t.offers.stream().noneMatch(o -> StorageIndex.matches(o, query))
					&& !StorageStore.pretty(t.profession).toLowerCase(Locale.ROOT).contains(query));
		}
		traders.sort(Comparator.comparingDouble(t -> index.distance(t.dim, t.x, t.y, t.z)));
		int y = top - (int) listScroll;
		int start = y;
		context.enableScissor(x, top, x + width, bottom);
		int chip = 84;
		for (Trader trader : traders) {
			int perRow = Math.max(1, (width - 40) / chip);
			int rows = (trader.offers.size() + perRow - 1) / perRow;
			int h = 26 + rows * 19 + 4;
			if (y + h >= top && y <= bottom) {
				boolean rowVisible = mouseY >= top && mouseY < bottom;
				VeloDraw.fillRounded(context, x, y, width - 6, h - 3, 6, VeloUi.withAlpha(0xFFFFFFFF, 0x08));
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(traderLabel(trader), width - 130), x + 8, y + 4, theme.text());
				String detail = (int) trader.x + ", " + (int) trader.y + ", " + (int) trader.z + "  -  " + index.distanceLabel(trader.dim, trader.x, trader.y, trader.z)
						+ "  -  traded " + VeloUi.ago(trader.time);
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(detail, width - 130), x + 8, y + 14, VeloUi.muted());
				if (rowVisible) {
					hits.add(VeloUi.pill(context, x + width - 110, y + 5, 46, 16, "Show", 1, mouseX, mouseY, () -> showTrader(trader, null)));
					hits.add(confirmPill(context, x + width - 60, y + 5, 50, "Forget", "t:" + trader.uuid, mouseX, mouseY, () -> {
						index.world().traders.remove(trader.uuid);
						StorageStore.markDirty(serverKey);
					}));
				}
				for (int i = 0; i < trader.offers.size(); i++) {
					Offer offer = trader.offers.get(i);
					int cx = x + 8 + (i % perRow) * chip;
					int cy = y + 26 + (i / perRow) * 19;
					boolean match = !query.isEmpty() && StorageIndex.matches(offer, query);
					VeloDraw.fillRounded(context, cx - 2, cy - 1, chip - 4, 18, 3, match ? VeloUi.withAlpha(theme.accentStart(), 0x60)
							: VeloUi.withAlpha(0xFF000000, offer.max > 0 && offer.uses >= offer.max ? 0x70 : 0x35));
					int ix = drawOfferItem(context, offer.a, cx, cy, mouseX, mouseY, top, bottom);
					if (offer.b != null) {
						ix = drawOfferItem(context, offer.b, ix + 1, cy, mouseX, mouseY, top, bottom);
					}
					int rx = cx + chip - 24;
					VeloDraw.chevron(context, (ix + rx) / 2f, cy + 8, 5f, 1.5f, 0, VeloUi.muted());
					drawOfferItem(context, offer.r, rx, cy, mouseX, mouseY, top, bottom);
				}
			}
			y += h;
		}
		if (traders.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(query.isEmpty() ? "Trade with a villager and its offers show up here."
					: "No trader you've met offers that."), x + width / 2, top + 30, VeloUi.muted());
		}
		context.disableScissor();
		listHeight = y - start;
		scrollbar(context, x + width - 3, top, bottom, listScroll, listHeight, theme);
	}

	// ---- Enchants tab ----

	private void drawEnchantsTab(DrawContext context, int x, int top, int width, int bottom, int mouseX, int mouseY, Theme theme) {
		StorageIndex index = index();
		String query = query();
		if (enchantments == null || enchantments.isEmpty()) {
			enchantments = StorageMc.enchantments();
			enchantments.sort(Comparator.comparing(e -> e[1]));
		}
		// Where you enchant / combine.
		int px = x;
		for (String block : List.of("minecraft:enchanting_table", "minecraft:anvil", "minecraft:grindstone")) {
			Station station = index.nearestStation(block);
			String label = StorageIndex.name(block) + ": " + (station == null ? "none"
					: index.distanceLabel(station.dim, station.x + 0.5, station.y + 0.5, station.z + 0.5));
			int w = Math.min((width - 12) / 3, this.textRenderer.getWidth(label) + 20);
			VeloUi.Hit pill = VeloUi.pill(context, px, top, w, 16, label, 0, mouseX, mouseY, () -> {
				if (station != null) {
					showStation(station);
				}
			});
			if (station != null) {
				hits.add(pill);
			}
			px += w + 6;
		}
		int listTop = top + 22;
		int y = listTop - (int) listScroll;
		int start = y;
		context.enableScissor(x, listTop, x + width, bottom);
		for (String[] enchantment : enchantments) {
			String id = enchantment[0];
			String name = enchantment[1];
			if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query) && !id.contains(query)) {
				continue;
			}
			int h = 34;
			if (y + h < listTop || y > bottom) {
				y += h;
				continue;
			}
			// Books / items carrying it.
			int books = 0;
			int bestLevel = 0;
			Hit nearest = null;
			for (Hit hit : index.hits()) {
				if (hit.item().e == null) {
					continue;
				}
				for (String entry : hit.item().e) {
					if (StorageIndex.enchantId(entry).equals(id) && hit.owner() == null) {
						books += hit.item().c;
						bestLevel = Math.max(bestLevel, StorageIndex.enchantLevel(entry));
						if (nearest == null || index.rank(hit) < index.rank(nearest)) {
							nearest = hit;
						}
					}
				}
			}
			// Villagers selling it.
			TradeHit seller = null;
			int sellerLevel = 0;
			for (TradeHit trade : index.trades()) {
				if (trade.offer().r == null || trade.offer().r.e == null) {
					continue;
				}
				for (String entry : trade.offer().r.e) {
					if (StorageIndex.enchantId(entry).equals(id)) {
						int level = StorageIndex.enchantLevel(entry);
						if (seller == null || level > sellerLevel || level == sellerLevel && price(trade.offer()) < price(seller.offer())) {
							seller = trade;
							sellerLevel = level;
						}
					}
				}
			}
			boolean rowVisible = mouseY >= listTop && mouseY < bottom;
			boolean hovered = rowVisible && VeloUi.inside(mouseX, mouseY, x, y, width - 6, h - 3);
			VeloDraw.fillRounded(context, x, y, width - 6, h - 3, 6, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
			int maxLevel = Integer.parseInt(enchantment[2]);
			String title = name + (maxLevel > 1 ? "  (max " + roman(maxLevel) + ")" : "");
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title, width / 2), x + 8, y + 4, theme.text());
			StringBuilder line = new StringBuilder();
			if (books > 0) {
				line.append(books).append(books == 1 ? " stored" : " stored").append(" (best ").append(roman(bestLevel)).append(")");
				if (nearest != null) {
					line.append(" - ").append(nearestLabel(index, nearest));
				}
			}
			if (seller != null) {
				if (!line.isEmpty()) {
					line.append("   |   ");
				}
				Offer offer = seller.offer();
				line.append(StorageStore.pretty(seller.trader().profession)).append(" sells ").append(roman(sellerLevel)).append(" for ")
						.append(offer.a.c).append(" ").append(StorageIndex.name(offer.a.id))
						.append(offer.b != null ? " + " + StorageIndex.name(offer.b.id) : "").append(" - ")
						.append(index.distanceLabel(seller.trader().dim, seller.trader().x, seller.trader().y, seller.trader().z));
			}
			if (line.isEmpty()) {
				line.append("Not stored and no villager you've met sells it.");
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(line.toString(), width - 24), x + 8, y + 16,
					books > 0 || seller != null ? 0xFFB8C4D6 : VeloUi.muted());
			if (hovered) {
				Hit target = nearest;
				TradeHit trade = seller;
				hits.add(new VeloUi.Hit(x, Math.max(y, listTop), width - 6, h - 3, () -> {
					if (target != null) {
						showHit(target);
					} else if (trade != null) {
						showTrader(trade.trader(), trade.offer().r.id);
					}
				}));
			}
			y += h;
		}
		context.disableScissor();
		listHeight = y - start;
		scrollbar(context, x + width - 3, listTop, bottom, listScroll, listHeight, theme);
	}

	private static int price(Offer offer) {
		return (offer.a == null ? 0 : offer.a.c) + (offer.b == null ? 0 : offer.b.c);
	}

	private String nearestLabel(StorageIndex index, Hit hit) {
		return switch (hit.where()) {
			case INVENTORY -> "in your inventory";
			case ENDER -> "in your ender chest";
			case PARTY_INVENTORY -> "with " + hit.owner();
			case PARTY_ENDER -> "in " + hit.owner() + "'s ender chest";
			case CONTAINER -> StorageHighlight.describe(hit.container().block) + " "
					+ index.distanceLabel(hit.container().dim, hit.container().x + 0.5, hit.container().y + 0.5, hit.container().z + 0.5);
		};
	}

	private static String roman(int n) {
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			case 6 -> "VI";
			case 7 -> "VII";
			case 8 -> "VIII";
			case 9 -> "IX";
			case 10 -> "X";
			default -> String.valueOf(n);
		};
	}

	// ---- Stations tab ----

	private void drawStationsTab(DrawContext context, int x, int top, int width, int bottom, int mouseX, int mouseY, Theme theme) {
		StorageIndex index = index();
		String query = query();
		List<Station> stations = new ArrayList<>(index.world().stations.values());
		if (!query.isEmpty()) {
			stations.removeIf(s -> !StorageIndex.name(s.block).toLowerCase(Locale.ROOT).contains(query));
		}
		stations.sort(Comparator.comparing((Station s) -> StorageIndex.name(s.block)).thenComparingDouble(s -> index.distance(s.dim, s.x, s.y, s.z)));
		int y = top - (int) listScroll;
		int start = y;
		context.enableScissor(x, top, x + width, bottom);
		String group = null;
		for (Station station : stations) {
			if (!station.block.equals(group)) {
				group = station.block;
				String g = group;
				long count = stations.stream().filter(s -> s.block.equals(g)).count();
				if (y + 14 >= top && y <= bottom) {
					section(context, StorageIndex.name(group), (int) count, x, y + 2, width - 10);
				}
				y += 16;
			}
			int h = 22;
			if (y + h >= top && y <= bottom) {
				boolean rowVisible = mouseY >= top && mouseY < bottom;
				boolean hovered = rowVisible && VeloUi.inside(mouseX, mouseY, x, y, width - 6, h - 2);
				VeloDraw.fillRounded(context, x, y, width - 6, h - 2, 5, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
				ItemStack icon = StorageMc.stack(station.block, 1);
				if (!StorageMc.isEmpty(icon)) {
					StorageMc.drawItem(context, icon, x + 3, y + 2, "");
				}
				String line = station.x + ", " + station.y + ", " + station.z + "  -  " + index.distanceLabel(station.dim, station.x + 0.5, station.y + 0.5, station.z + 0.5)
						+ "  -  " + StorageStore.dimensionLabel(station.dim);
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(line, width - 90), x + 24, y + 6, theme.text());
				if (rowVisible) {
					hits.add(confirmPill(context, x + width - 60, y + 2, 50, "Forget", "s:" + station.x + "," + station.y + "," + station.z + station.dim,
							mouseX, mouseY, () -> {
								index.world().stations.values().remove(station);
								StorageStore.markDirty(serverKey);
							}));
					if (hovered) {
						hits.add(new VeloUi.Hit(x, y, width - 70, h - 2, () -> showStation(station)));
					}
				}
			}
			y += h;
		}
		if (stations.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Work stations near you are picked up automatically as you walk around."),
					x + width / 2, top + 30, VeloUi.muted());
		}
		context.disableScissor();
		listHeight = y - start;
		scrollbar(context, x + width - 3, top, bottom, listScroll, listHeight, theme);
	}

	// ---- Pointing at things ----

	private void showHit(Hit hit) {
		switch (hit.where()) {
			case INVENTORY -> flash("It's in your inventory.");
			case ENDER -> flash("It's in your ender chest - open any ender chest.");
			default -> showContainer(hit.container(), hit.item().id);
		}
		if (hit.where() != StorageIndex.Where.CONTAINER) {
			StorageHighlight.block(worldKey, null, 0, 0, 0, null, "", hit.item().id);
		}
	}

	private void showContainer(Container container, String itemId) {
		StorageHighlight.block(worldKey, container.dim, container.x, container.y, container.z, container.other,
				StorageHighlight.describe(container.block), itemId != null ? itemId : selected);
		leaveOrExplain(container.dim);
	}

	private void showTrader(Trader trader, String itemId) {
		StorageHighlight.entity(worldKey, trader.dim, trader.x, trader.y, trader.z, traderLabel(trader), itemId);
		leaveOrExplain(trader.dim);
	}

	private void showStation(Station station) {
		StorageHighlight.block(worldKey, station.dim, station.x, station.y, station.z, null, StorageIndex.name(station.block), null);
		leaveOrExplain(station.dim);
	}

	/** Back to the game to follow the marker - or say why it can't be seen from here. */
	private void leaveOrExplain(String dim) {
		if (!viewingHere()) {
			flash("That's on another world - the marker shows up once you're there.");
		} else if (dim != null && !dim.equals(StorageStore.here())) {
			flash("That's in the " + StorageStore.dimensionLabel(dim) + " - the marker shows up once you're there.");
		} else {
			closeToGame();
		}
	}

	// ---- Helpers ----

	private void addClipped(VeloUi.Hit hit, int clipTop, int clipBottom) {
		if (hit.y() + hit.height() > clipTop && hit.y() < clipBottom) {
			hits.add(hit);
		}
	}

	private void scrollbar(DrawContext context, int x, int top, int bottom, double scroll, int contentHeight, Theme theme) {
		int visible = bottom - top;
		if (contentHeight <= visible) {
			return;
		}
		int thumb = Math.max(14, visible * visible / contentHeight);
		int thumbY = top + (int) ((visible - thumb) * (scroll / Math.max(1, contentHeight - visible)));
		context.fill(x, thumbY, x + 2, thumbY + thumb, VeloUi.withAlpha(theme.accentStart(), 0xAA));
	}

	private static String compact(int count) {
		if (count >= 10_000) {
			return (count / 1000) + "k";
		}
		if (count >= 1000) {
			return String.format(Locale.ROOT, "%.1fk", count / 1000.0);
		}
		return String.valueOf(count);
	}

	private VeloUi.Hit confirmPill(DrawContext context, int x, int y, int width, String label, String key, int mouseX, int mouseY, Runnable action) {
		boolean armed = key.equals(confirmKey) && System.currentTimeMillis() < confirmUntil;
		return VeloUi.pill(context, x, y, width, 16, armed ? "Sure?" : label, armed ? 3 : 0, mouseX, mouseY, () -> {
			if (armed) {
				confirmKey = null;
				action.run();
			} else {
				confirmKey = key;
				confirmUntil = System.currentTimeMillis() + 3000;
			}
		});
	}

	private void flash(String message) {
		feedback = message;
		feedbackUntil = System.currentTimeMillis() + 5000;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		double step = verticalAmount * 20;
		int visible = contentBottom() - 14 - (contentY() + 54);
		if (tab == 0) {
			if (mouseX < gridSplit) {
				gridScroll = clamp(gridScroll - step, gridHeight, visible);
			} else {
				detailScroll = clamp(detailScroll - step, detailHeight, visible);
			}
		} else {
			listScroll = clamp(listScroll - step, listHeight, visible);
		}
		return true;
	}

	private static double clamp(double scroll, int content, int visible) {
		return Math.max(0, Math.min(scroll, Math.max(0, content - visible)));
	}
}
