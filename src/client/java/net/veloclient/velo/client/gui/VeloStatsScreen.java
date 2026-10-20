package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.stats.StatsData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Velo's Statistics screen, shown instead of vanilla's (pause menu > Statistics). Same numbers,
 * all of them - every general stat, every item and every mob - but laid out to be readable:
 * an overview of the headline numbers, tabs, one search box that filters every tab, and sortable
 * columns. "Vanilla view" opens Minecraft's own screen once.
 */
public final class VeloStatsScreen extends VeloWindow {

	private enum Tab { OVERVIEW("Overview"), GENERAL("General"), ITEMS("Items"), MOBS("Mobs");
		final String label;
		Tab(String label) {
			this.label = label;
		}
	}

	private static final String[] ITEM_COLUMNS = {"Mined", "Crafted", "Used", "Broken", "Picked up", "Dropped"};
	private static final List<ToIntFunction<StatsData.ItemRow>> ITEM_VALUES = List.of(
			StatsData.ItemRow::mined, StatsData.ItemRow::crafted, StatsData.ItemRow::used, StatsData.ItemRow::broken,
			StatsData.ItemRow::pickedUp, StatsData.ItemRow::dropped);
	private static boolean openVanillaOnce;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private Tab tab = Tab.OVERVIEW;
	private TextFieldWidget search;
	private StatsData.Snapshot data;
	private int refreshIn;
	private float scroll;
	/** Where scrolling is heading; {@link #scroll} eases toward it each frame. Always within [0, maxScroll]. */
	private float scrollTarget;
	private float maxScroll;
	private long lastFrameNanos;
	private int itemSort = -1;
	private int mobSort = 0;
	private boolean sortDescending = true;

	public VeloStatsScreen(Screen parent) {
		super(Text.literal("Statistics"), 720, 450);
		returnTo(parent);
	}

	/** Swaps vanilla's Statistics screen for this one (registered on screen init). */
	public static void maybeReplace(MinecraftClient client, Screen screen) {
		//? if <26.1 {
		boolean vanillaStats = screen instanceof net.minecraft.client.gui.screen.StatsScreen;
		//?} else {
		/*boolean vanillaStats = screen instanceof net.minecraft.client.gui.screens.achievement.StatsScreen;
		*///?}
		if (!vanillaStats) {
			return;
		}
		if (openVanillaOnce) {
			openVanillaOnce = false;
			return;
		}
		if (!VeloScreens.statistics()) {
			return;
		}
		Screen parent = VeloKeybindsScreen.lastOther() != null ? VeloKeybindsScreen.lastOther()
				: new net.minecraft.client.gui.screen.GameMenuScreen(true);
		client.execute(() -> client.setScreen(new VeloStatsScreen(parent)));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String previous = search != null ? search.getText() : "";
		search = new TextFieldWidget(this.textRenderer, contentX() + contentWidth() - 250, contentY(), 170, 16, Text.literal("Search"));
		search.setPlaceholder(Text.literal("Search stats, items, mobs..."));
		search.setText(previous);
		search.setChangedListener(v -> {
			scroll = 0;
			scrollTarget = 0;
		});
		addDrawableChild(search);
		if (data == null) {
			StatsData.request(MinecraftClient.getInstance());
			refreshIn = 10;
		}
	}

	/** Dev-only (screenshot tour). */
	public void showTabForTour(int index) {
		tab = Tab.values()[index];
		scroll = 0;
				scrollTarget = 0;
	}

	private String query() {
		return search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (--refreshIn <= 0 && MinecraftClient.getInstance().player != null) {
			data = StatsData.read(MinecraftClient.getInstance());
			refreshIn = 30;
		}
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		stepScroll();
		int x = contentX();
		int y = contentY();
		// Underline tabs (0.9.7): the active one white with an accent line.
		int tabX = x;
		for (Tab t : Tab.values()) {
			net.minecraft.text.Text label = net.veloclient.velo.client.gui.title.MenuKit.uiBold(t.label);
			int w = this.textRenderer.getWidth(label);
			boolean on = tab == t;
			boolean hover = VeloUi.inside(mouseX, mouseY, tabX - 4, y, w + 8, 16);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, label, tabX, y + 4,
					on ? 0xFFF4F4F6 : hover ? 0xFFD4D4DA : 0xFF9D9DA8);
			if (on) {
				VeloDraw.fillRounded(context, tabX, y + 15, w, 2, 1, net.veloclient.velo.client.gui.title.MenuKit.accent());
			}
			hits.add(new VeloUi.Hit(tabX - 4, y, w + 8, 18, () -> {
				tab = t;
				scroll = 0;
				scrollTarget = 0;
			}));
			tabX += w + 16;
		}
		hits.add(VeloUi.pill(context, x + contentWidth() - 72, y, 72, 16, "Vanilla view", 0, mouseX, mouseY, () -> {
			openVanillaOnce = true;
			//? if <26.1 {
			MinecraftClient.getInstance().setScreen(new net.minecraft.client.gui.screen.StatsScreen(this,
					MinecraftClient.getInstance().player.getStatHandler()));
			//?} else {
			/*this.client.setScreen(new net.minecraft.client.gui.screens.achievement.StatsScreen(this, this.client.player.getStats()));
			*///?}
		}));
		if (data == null) {
			context.drawTextWithShadow(this.textRenderer, "Loading statistics...", x, y + 30, VeloStyle.textMuted());
			return;
		}
		int top = y + 26;
		int bottom = contentBottom();
		context.enableScissor(x, top, x + contentWidth(), bottom);
		int contentHeight = switch (tab) {
			case OVERVIEW -> drawOverview(context, x, top - Math.round(scroll), contentWidth());
			case GENERAL -> drawGeneral(context, x, top - Math.round(scroll), contentWidth());
			case ITEMS -> drawItems(context, x, top - Math.round(scroll), contentWidth(), mouseX, mouseY, top);
			case MOBS -> drawMobs(context, x, top - Math.round(scroll), contentWidth(), mouseX, mouseY, top);
		};
		context.disableScissor();
		float max = Math.max(0, contentHeight - (bottom - top));
		maxScroll = max;
		if (max > 0) {
			int trackH = bottom - top;
			int thumbH = Math.max(20, Math.round(trackH * trackH / (float) contentHeight));
			int thumbY = top + Math.round((trackH - thumbH) * (scroll / max));
			VeloDraw.fillRounded(context, x + contentWidth() + 4, thumbY, 3, thumbH, 1, VeloStyle.borderStrong());
		}
	}

	// ---- Overview ----

	private int drawOverview(DrawContext context, int x, int y, int width) {
		Map<String, StatsData.General> byId = data.general().stream().collect(Collectors.toMap(StatsData.General::id, g -> g, (a, b) -> a));
		long walkedCm = 0;
		for (StatsData.General g : data.general()) {
			if (g.id().endsWith("_one_cm")) {
				walkedCm += g.value();
			}
		}
		long mined = data.items().stream().mapToLong(StatsData.ItemRow::mined).sum();
		Object[][] cards = {
				{"Play time", formatted(byId, "play_time"), "clock", 0xFF5AA9FF},
				{"Distance", distance(walkedCm), "walk", 0xFF3DDC97},
				{"Blocks mined", compact(mined), "pickaxe", 0xFFF7C04A},
				{"Mobs killed", formatted(byId, "mob_kills"), "sword", 0xFFFF7A7E},
				{"Deaths", formatted(byId, "deaths"), "skull", 0xFFA9A9B3},
				{"Jumps", formatted(byId, "jump"), "jump", 0xFFBCA6FF},
		};
		String q = query();
		int perRow = width >= 520 ? 6 : 3;
		int gap = 8;
		int cardW = (width - (perRow - 1) * gap) / perRow;
		int shown = 0;
		for (Object[] card : cards) {
			if (!q.isEmpty() && !((String) card[0]).toLowerCase(Locale.ROOT).contains(q)) {
				continue;
			}
			int cx = x + (shown % perRow) * (cardW + gap);
			int cy = y + (shown / perRow) * 50;
			VeloDraw.fillRounded(context, cx, cy, cardW, 44, 8, 0x0AFFFFFF);
			VeloDraw.strokeRounded(context, cx, cy, cardW, 44, 8, 0x0FFFFFFF);
			net.veloclient.velo.client.gui.title.MenuKit.icon(context, (String) card[2], cx + 8, cy + 8, 9, (int) card[3]);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
					net.veloclient.velo.client.gui.title.MenuKit.fit(this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.ui((String) card[0]), cardW - 26),
					cx + 21, cy + 9, 0xFFA9A9B3);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
					net.veloclient.velo.client.gui.title.MenuKit.fit(this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.display((String) card[1]), cardW - 14),
					cx + 8, cy + 24, 0xFFF4F4F6);
			shown++;
		}
		int rowY = y + ((shown + perRow - 1) / perRow) * 50 + 6;
		int colW = (width - 10) / 2;
		int h1 = playTimeChart(context, x, rowY, colW);
		List<StatsData.ItemRow> topMined = data.items().stream().filter(r -> r.mined() > 0 && matches(r.name(), q))
				.sorted(Comparator.comparingInt(StatsData.ItemRow::mined).reversed()).limit(6).toList();
		int h2 = minedTable(context, x + colW + 10, rowY, colW, topMined);
		int listY = rowY + Math.max(h1, h2) + 10;
		List<StatsData.MobRow> topMobs = data.mobs().stream().filter(r -> r.killed() > 0 && matches(r.name(), q))
				.sorted(Comparator.comparingInt(StatsData.MobRow::killed).reversed()).limit(6).toList();
		int h3 = topList(context, x, listY, width, "Most defeated mobs", topMobs.stream().map(r -> new Object[] {r.icon(), r.name(), r.killed()}).toList());
		return listY - y + h3;
	}

	private static String compact(long n) {
		return n >= 10_000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.format(Locale.ROOT, "%,d", n);
	}

	/** "Play time · last 7 days": a bar per day, today in the accent colour (counted by Velo, see PlayTimeDays). */
	private int playTimeChart(DrawContext context, int x, int y, int width) {
		int h = 130;
		VeloDraw.fillRounded(context, x, y, width, h, 8, 0x0AFFFFFF);
		VeloDraw.strokeRounded(context, x, y, width, h, 8, 0x0FFFFFFF);
		List<Integer> days = net.veloclient.velo.client.stats.PlayTimeDays.lastDays(7);
		int total = days.stream().mapToInt(Integer::intValue).sum();
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.uiBold("Play time · last 7 days"),
				x + 10, y + 9, 0xFFF4F4F6);
		net.minecraft.text.Text sum = net.veloclient.velo.client.gui.title.MenuKit.mono(hours(total));
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, sum, x + width - 10 - this.textRenderer.getWidth(sum), y + 10, 0xFFA9A9B3);
		int max = Math.max(60, days.stream().mapToInt(Integer::intValue).max().orElse(60));
		int chartTop = y + 30;
		int chartBottom = y + h - 18;
		int slot = (width - 20) / 7;
		java.time.LocalDate today = java.time.LocalDate.now();
		for (int i = 0; i < 7; i++) {
			int value = days.get(i);
			int barH = Math.max(value > 0 ? 3 : 1, Math.round((chartBottom - chartTop - 10) * value / (float) max));
			int bx = x + 10 + i * slot + slot / 6;
			int bw = slot - slot / 3;
			int color = i == 6 ? net.veloclient.velo.client.gui.title.MenuKit.accent()
					: (net.veloclient.velo.client.gui.title.MenuKit.accent() & 0xFFFFFF) | 0x73000000;
			VeloDraw.fillRoundedTop(context, bx, chartBottom - barH, bw, barH, Math.min(3, bw / 3), color);
			String day = i == 6 ? "Today" : WEEKDAYS[today.minusDays(6 - i).getDayOfWeek().getValue() - 1];
			net.minecraft.text.Text dayText = net.veloclient.velo.client.gui.title.MenuKit.ui(day);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, dayText, bx + (bw - this.textRenderer.getWidth(dayText)) / 2, chartBottom + 5, 0xFF8A8A95);
		}
		context.fill(x + 10, chartBottom, x + width - 10, chartBottom + 1, 0x1AFFFFFF);
		if (!net.veloclient.velo.client.stats.PlayTimeDays.hasData()) {
			net.minecraft.text.Text note = net.veloclient.velo.client.gui.title.MenuKit.ui("Velo counts this from today on");
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, note, x + (width - this.textRenderer.getWidth(note)) / 2,
					(chartTop + chartBottom) / 2 - 4, 0xFF8A8A95);
		}
		return h;
	}

	private static final String[] WEEKDAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

	private static String hours(int seconds) {
		int minutes = seconds / 60;
		return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " m" : minutes + " m";
	}

	/** Most mined blocks with what else you did with them. */
	private int minedTable(DrawContext context, int x, int y, int width, List<StatsData.ItemRow> rows) {
		int h = Math.max(130, 26 + Math.max(1, rows.size()) * 17 + 6);
		VeloDraw.fillRounded(context, x, y, width, h, 8, 0x0AFFFFFF);
		VeloDraw.strokeRounded(context, x, y, width, h, 8, 0x0FFFFFFF);
		int colW = Math.min(44, (width - 120) / 3);
		String[] heads = {"MINED", "USED", "CRAFTED"};
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.mono("MOST MINED"), x + 10, y + 10, 0xFF8A8A95);
		for (int c = 0; c < 3; c++) {
			net.minecraft.text.Text head = net.veloclient.velo.client.gui.title.MenuKit.mono(heads[c]);
			int right = x + width - 10 - (2 - c) * colW;
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, head, right - this.textRenderer.getWidth(head), y + 10, 0xFF8A8A95);
		}
		if (rows.isEmpty()) {
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.ui("Nothing mined yet"), x + 10, y + 30, 0xFF6E6E78);
		}
		int ry = y + 24;
		for (StatsData.ItemRow row : rows) {
			drawIcon(context, row.icon(), x + 8, ry - 2);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
					net.veloclient.velo.client.gui.title.MenuKit.fit(this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.ui(row.name()), width - 30 - colW * 3 - 10),
					x + 28, ry + 3, 0xFFF4F4F6);
			int[] values = {row.mined(), row.used(), row.crafted()};
			for (int c = 0; c < 3; c++) {
				net.minecraft.text.Text value = net.veloclient.velo.client.gui.title.MenuKit.mono(values[c] == 0 ? "-" : String.format(Locale.ROOT, "%,d", values[c]));
				int right = x + width - 10 - (2 - c) * colW;
				net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, value, right - this.textRenderer.getWidth(value), ry + 3,
						c == 0 ? 0xFFF4F4F6 : 0xFFA9A9B3);
			}
			ry += 17;
		}
		return h;
	}

	private int topList(DrawContext context, int x, int y, int width, String title, List<Object[]> rows) {
		int h = 22 + Math.max(1, rows.size()) * 20 + 6;
		VeloDraw.fillRounded(context, x, y, width, h, 8, VeloStyle.card());
		context.drawTextWithShadow(this.textRenderer, title, x + 10, y + 8, VeloStyle.textMuted());
		if (rows.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "Nothing yet", x + 10, y + 26, VeloStyle.textFaint());
		}
		int ry = y + 22;
		for (Object[] row : rows) {
			drawIcon(context, (ItemStack) row[0], x + 8, ry);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim((String) row[1], width - 90), x + 30, ry + 5, VeloStyle.text());
			String value = String.format(Locale.ROOT, "%,d", (Integer) row[2]);
			context.drawTextWithShadow(this.textRenderer, value, x + width - 10 - this.textRenderer.getWidth(value), ry + 5, VeloStyle.text());
			ry += 20;
		}
		return h;
	}

	private static String formatted(Map<String, StatsData.General> byId, String id) {
		StatsData.General g = byId.get(id);
		return g == null ? "0" : g.formatted();
	}

	private static String distance(long cm) {
		double m = cm / 100.0;
		return m >= 1000 ? String.format(Locale.ROOT, "%.2f km", m / 1000) : String.format(Locale.ROOT, "%.0f m", m);
	}

	// ---- General ----

	private int drawGeneral(DrawContext context, int x, int y, int width) {
		String q = query();
		List<StatsData.General> rows = data.general().stream().filter(g -> matches(g.name(), q))
				.sorted(Comparator.comparing(StatsData.General::name)).toList();
		int colW = (width - 10) / 2;
		int perColumn = (rows.size() + 1) / 2;
		for (int i = 0; i < rows.size(); i++) {
			StatsData.General g = rows.get(i);
			int column = i / Math.max(1, perColumn);
			int row = i % Math.max(1, perColumn);
			int rx = x + column * (colW + 10);
			int ry = y + row * 18;
			if (row % 2 == 0) {
				VeloDraw.fillRounded(context, rx, ry, colW, 18, 5, VeloStyle.card());
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(g.name(), colW - 90), rx + 8, ry + 5,
					g.value() == 0 ? VeloStyle.textFaint() : VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, g.formatted(), rx + colW - 8 - this.textRenderer.getWidth(g.formatted()), ry + 5,
					g.value() == 0 ? VeloStyle.textFaint() : VeloStyle.text());
		}
		if (rows.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "No stat matches \"" + q + "\"", x, y + 4, VeloStyle.textMuted());
		}
		return perColumn * 18;
	}

	// ---- Items & mobs ----

	private int drawItems(DrawContext context, int x, int y, int width, int mouseX, int mouseY, int clipTop) {
		String q = query();
		List<StatsData.ItemRow> rows = new ArrayList<>(data.items().stream().filter(r -> matches(r.name(), q)).toList());
		Comparator<StatsData.ItemRow> order = itemSort < 0 ? Comparator.comparingInt(StatsData.ItemRow::total)
				: Comparator.comparingInt(ITEM_VALUES.get(itemSort));
		rows.sort(sortDescending ? order.reversed() : order);
		int colW = 62;
		int numbersX = x + width - colW * ITEM_COLUMNS.length;
		// Header (clickable to sort).
		VeloDraw.fillRounded(context, x, y, width, 18, 5, VeloStyle.sunken());
		context.drawTextWithShadow(this.textRenderer, rows.size() + " items", x + 8, y + 5, VeloStyle.textMuted());
		for (int c = 0; c < ITEM_COLUMNS.length; c++) {
			int cx = numbersX + c * colW;
			String label = ITEM_COLUMNS[c] + (itemSort == c ? (sortDescending ? " v" : " ^") : "");
			boolean hovered = VeloUi.inside(mouseX, mouseY, cx, y, colW, 18) && mouseY >= clipTop;
			context.drawTextWithShadow(this.textRenderer, label, cx + colW - 6 - this.textRenderer.getWidth(label), y + 5,
					itemSort == c || hovered ? VeloStyle.accent() : VeloStyle.textMuted());
			int column = c;
			if (y >= clipTop - 2) {
				hits.add(new VeloUi.Hit(cx, y, colW, 18, () -> sortItems(column)));
			}
		}
		int ry = y + 22;
		for (int i = 0; i < rows.size(); i++) {
			StatsData.ItemRow r = rows.get(i);
			if (i % 2 == 0) {
				VeloDraw.fillRounded(context, x, ry, width, 20, 5, VeloStyle.card());
			}
			drawIcon(context, r.icon(), x + 4, ry + 2);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(r.name(), numbersX - x - 30), x + 26, ry + 6, VeloStyle.text());
			for (int c = 0; c < ITEM_COLUMNS.length; c++) {
				int value = ITEM_VALUES.get(c).applyAsInt(r);
				String text = value == 0 ? "-" : String.format(Locale.ROOT, "%,d", value);
				context.drawTextWithShadow(this.textRenderer, text, numbersX + c * colW + colW - 6 - this.textRenderer.getWidth(text), ry + 6,
						value == 0 ? VeloStyle.textFaint() : VeloStyle.text());
			}
			ry += 20;
		}
		if (rows.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, q.isEmpty() ? "No item stats yet" : "No item matches \"" + q + "\"", x, ry + 2,
					VeloStyle.textMuted());
		}
		return ry - y + 4;
	}

	private int drawMobs(DrawContext context, int x, int y, int width, int mouseX, int mouseY, int clipTop) {
		String q = query();
		List<StatsData.MobRow> rows = new ArrayList<>(data.mobs().stream().filter(r -> matches(r.name(), q)).toList());
		Comparator<StatsData.MobRow> order = mobSort == 0 ? Comparator.comparingInt(StatsData.MobRow::killed)
				: Comparator.comparingInt(StatsData.MobRow::killedBy);
		rows.sort(sortDescending ? order.reversed() : order);
		int colW = 110;
		int numbersX = x + width - colW * 2;
		VeloDraw.fillRounded(context, x, y, width, 18, 5, VeloStyle.sunken());
		context.drawTextWithShadow(this.textRenderer, rows.size() + " mobs", x + 8, y + 5, VeloStyle.textMuted());
		String[] labels = {"You defeated", "Defeated you"};
		for (int c = 0; c < 2; c++) {
			int cx = numbersX + c * colW;
			String label = labels[c] + (mobSort == c ? (sortDescending ? " v" : " ^") : "");
			context.drawTextWithShadow(this.textRenderer, label, cx + colW - 6 - this.textRenderer.getWidth(label), y + 5,
					mobSort == c ? VeloStyle.accent() : VeloStyle.textMuted());
			int column = c;
			if (y >= clipTop - 2) {
				hits.add(new VeloUi.Hit(cx, y, colW, 18, () -> {
					sortDescending = mobSort != column || !sortDescending;
					mobSort = column;
				}));
			}
		}
		int ry = y + 22;
		for (int i = 0; i < rows.size(); i++) {
			StatsData.MobRow r = rows.get(i);
			if (i % 2 == 0) {
				VeloDraw.fillRounded(context, x, ry, width, 20, 5, VeloStyle.card());
			}
			drawIcon(context, r.icon(), x + 4, ry + 2);
			context.drawTextWithShadow(this.textRenderer, r.name(), x + 26, ry + 6, VeloStyle.text());
			int[] values = {r.killed(), r.killedBy()};
			for (int c = 0; c < 2; c++) {
				String text = values[c] == 0 ? "-" : String.format(Locale.ROOT, "%,d", values[c]);
				context.drawTextWithShadow(this.textRenderer, text, numbersX + c * colW + colW - 6 - this.textRenderer.getWidth(text), ry + 6,
						values[c] == 0 ? VeloStyle.textFaint() : (c == 1 ? 0xFFFF7A7A : VeloStyle.text()));
			}
			ry += 20;
		}
		if (rows.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, q.isEmpty() ? "No mob stats yet" : "No mob matches \"" + q + "\"", x, ry + 2,
					VeloStyle.textMuted());
		}
		return ry - y + 4;
	}

	private void sortItems(int column) {
		sortDescending = itemSort != column || !sortDescending;
		itemSort = column;
	}

	private void drawIcon(DrawContext context, ItemStack stack, int x, int y) {
		if (stack != null && !stack.isEmpty()) {
			context.drawItemWithoutEntity(stack, x, y);
		} else {
			VeloDraw.fillRounded(context, x + 1, y + 1, 14, 14, 4, VeloStyle.sunken());
		}
	}

	private static boolean matches(String name, String q) {
		return q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q);
	}

	// ---- Input ----

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
		scrollTarget = Math.clamp(scrollTarget - (float) verticalAmount * 36, 0, maxScroll);
		return true;
	}

	/** Eases {@link #scroll} toward the target - smooth, and it can never run past either end. */
	private void stepScroll() {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;
		scrollTarget = Math.clamp(scrollTarget, 0, maxScroll);
		scroll += (scrollTarget - scroll) * Math.min(1f, dt * 16f);
		if (Math.abs(scrollTarget - scroll) < 0.3f) {
			scroll = scrollTarget;
		}
	}
}
