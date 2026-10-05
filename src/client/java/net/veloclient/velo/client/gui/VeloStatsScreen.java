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
		int tabX = x;
		for (Tab t : Tab.values()) {
			int w = this.textRenderer.getWidth(t.label) + 22;
			hits.add(VeloUi.pill(context, tabX, y, w, 16, t.label, tab == t ? 1 : 0, mouseX, mouseY, () -> {
				tab = t;
				scroll = 0;
				scrollTarget = 0;
			}));
			tabX += w + 4;
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
		long cm = 0;
		for (StatsData.General g : data.general()) {
			if (g.id().endsWith("_one_cm")) {
				cm += g.value();
			}
		}
		long mined = data.items().stream().mapToLong(StatsData.ItemRow::mined).sum();
		long crafted = data.items().stream().mapToLong(StatsData.ItemRow::crafted).sum();
		String[][] cards = {
				{"Time played", formatted(byId, "play_time")},
				{"Distance traveled", distance(cm)},
				{"Mob kills", formatted(byId, "mob_kills")},
				{"Deaths", formatted(byId, "deaths")},
				{"Blocks mined", String.format(Locale.ROOT, "%,d", mined)},
				{"Items crafted", String.format(Locale.ROOT, "%,d", crafted)},
				{"Jumps", formatted(byId, "jump")},
				{"Damage dealt", formatted(byId, "damage_dealt")},
				{"Player kills", formatted(byId, "player_kills")},
		};
		String q = query();
		int cardW = (width - 2 * 10) / 3;
		int shown = 0;
		for (String[] card : cards) {
			if (!q.isEmpty() && !card[0].toLowerCase(Locale.ROOT).contains(q)) {
				continue;
			}
			int cx = x + (shown % 3) * (cardW + 10);
			int cy = y + (shown / 3) * 58;
			VeloDraw.fillRounded(context, cx, cy, cardW, 50, 8, VeloStyle.card());
			context.drawTextWithShadow(this.textRenderer, card[0], cx + 10, cy + 9, VeloStyle.textMuted());
			context.drawTextWithShadow(this.textRenderer, net.veloclient.velo.client.gui.title.TitleScreenTheme.bigFont(card[1]), cx + 10, cy + 30,
					VeloStyle.text());
			shown++;
		}
		int listY = y + ((shown + 2) / 3) * 58 + 8;
		int colW = (width - 10) / 2;
		List<StatsData.ItemRow> topMined = data.items().stream().filter(r -> r.mined() > 0 && matches(r.name(), q))
				.sorted(Comparator.comparingInt(StatsData.ItemRow::mined).reversed()).limit(6).toList();
		List<StatsData.MobRow> topMobs = data.mobs().stream().filter(r -> r.killed() > 0 && matches(r.name(), q))
				.sorted(Comparator.comparingInt(StatsData.MobRow::killed).reversed()).limit(6).toList();
		int h1 = topList(context, x, listY, colW, "Most mined", topMined.stream().map(r -> new Object[] {r.icon(), r.name(), r.mined()}).toList());
		int h2 = topList(context, x + colW + 10, listY, colW, "Most defeated mobs",
				topMobs.stream().map(r -> new Object[] {r.icon(), r.name(), r.killed()}).toList());
		return listY - y + Math.max(h1, h2);
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
