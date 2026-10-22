package net.veloclient.velo.client.gui.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.StoreCard;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The owner panel in the game - the same panel as the launcher's, tab for tab (the server checks every call):
 * <ul>
 * <li><b>Cosmetics</b> - every wing pair, cape and aura with its status (released / testing / hidden), price, last
 * day in the store and collection; which collection the Store features.</li>
 * <li><b>Bundles</b> - several items for one price, live or off, optionally until a day.</li>
 * <li><b>Collections</b> - name, tag and description of each drop, and new ones.</li>
 * <li><b>Players</b> - look a player up; coins, Velo+ days, give or remove cosmetics.</li>
 * <li><b>Reports</b> - player chat reports: dismiss or ban from friends and parties.</li>
 * </ul>
 * Lists on the left, the picked entry's editor on the right; counts on top.
 */
public final class OwnerScreen extends VeloWindow {

	private enum Tab { COSMETICS, BUNDLES, COLLECTIONS, PLAYERS, REPORTS }

	private static final String[] TABS = {"Cosmetics", "Bundles", "Collections", "Players", "Reports"};
	private static final List<String> STATUSES = List.of("released", "testing", "hidden");
	private static final int ROW = 26;
	private static final int FIELD_H = 16;

	private static Tab tab = Tab.COSMETICS;
	private static String filter = "all";

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	/** {@code /v1/admin/cosmetics}: items, collections, bundles, featured. */
	private JsonObject data;
	private String status = "";
	private int statusColor;
	private boolean busy;

	// Lists: one scroll offset (rows) and the rectangle it applies to.
	private int listScroll;
	private int pickerScroll;
	private final int[] listBox = new int[4];
	private final int[] pickerBox = new int[4];

	// Text the fields hold, kept across re-layouts.
	private final Map<String, String> fieldText = new HashMap<>();
	private final Map<String, TextFieldWidget> fields = new HashMap<>();

	// Cosmetics
	private String selectedItem;
	private String editStatus;
	private String editCollection;
	private String featuredChoice;

	// Bundles: the one being edited (a copy; no "id" = new), its items and live switch.
	private JsonObject editingBundle;
	private final Set<String> bundleItems = new LinkedHashSet<>();
	private boolean bundleActive = true;

	// Collections: picked id ("" = new one).
	private String selectedCollection;

	// Players
	private JsonObject view;
	private int playerScroll;

	// Reports
	private JsonArray reports;
	private boolean allReports;

	public OwnerScreen(Screen parent) {
		super(Text.literal("Owner panel"), 880, 540);
		returnTo(parent);
		reload();
	}

	private void reload() {
		StoreClient.adminCosmetics(result -> {
			data = result;
			if (featuredChoice == null) {
				featuredChoice = str(result, "featured", "");
			}
			layoutContent();
		}, error -> setStatus(error, true));
		if (tab == Tab.REPORTS) {
			loadReports();
		}
	}

	private void loadReports() {
		StoreClient.staffReports(allReports ? "all" : "open", result -> {
			reports = result.has("reports") ? result.getAsJsonArray("reports") : new JsonArray();
			listScroll = 0;
		}, error -> setStatus(error, true));
	}

	// ---- Header ----

	@Override
	protected String[] headerTabs() {
		return TABS;
	}

	@Override
	protected int headerTab() {
		return tab.ordinal();
	}

	@Override
	protected void selectHeaderTab(int index) {
		tab = Tab.values()[index];
		listScroll = 0;
		pickerScroll = 0;
		setStatus("", false);
		if (tab == Tab.REPORTS && reports == null) {
			loadReports();
		}
		layoutContent();
	}

	@Override
	protected String headerTabBadge(int index) {
		if (index == Tab.REPORTS.ordinal() && reports != null && !allReports && !reports.isEmpty()) {
			return String.valueOf(reports.size());
		}
		return null;
	}

	// ---- Layout (text fields; everything else is drawn) ----

	private int kpiBottom() {
		return contentY() + (tab == Tab.PLAYERS || tab == Tab.REPORTS ? 0 : 40);
	}

	private int editorX() {
		return contentX() + contentWidth() * 55 / 100 + 14;
	}

	private int editorWidth() {
		return contentX() + contentWidth() - editorX();
	}

	@Override
	protected void layoutContent() {
		for (Map.Entry<String, TextFieldWidget> entry : fields.entrySet()) {
			fieldText.put(entry.getKey(), entry.getValue().getText());
		}
		fields.clear();
		this.clearChildren();
		int ex = editorX();
		int ew = editorWidth();
		int top = kpiBottom();
		switch (tab) {
			case COSMETICS -> {
				field("search", contentX(), top, contentWidth() * 55 / 100 - filterWidth() - 8, "Search name or id");
				if (selectedItem != null && item(selectedItem) != null) {
					int y = top + 110;
					field("price", ex + 10, y, 80, "Coins");
					field("until", ex + 100, y, ew - 110, "No end  (YYYY-MM-DD)");
				}
			}
			case BUNDLES -> {
				if (editingBundle != null) {
					int y = top + 22;
					if (!editingBundle.has("id")) {
						field("b.id", ex, y, ew * 40 / 100 - 6, "id, e.g. starter_pack");
						field("b.name", ex + ew * 40 / 100, y, ew * 60 / 100, "Name");
					} else {
						field("b.name", ex, y, ew, "Name");
					}
					field("b.price", ex, y + 30, 90, "Coins");
					field("b.until", ex + 100, y + 30, ew - 100, "No end  (YYYY-MM-DD)");
					field("b.description", ex, y + 60, ew, "One line for the store card");
				}
			}
			case COLLECTIONS -> {
				if (selectedCollection != null) {
					int y = top + 22;
					if (selectedCollection.isEmpty()) {
						field("c.id", ex, y, ew * 40 / 100 - 6, "id, e.g. winter");
						field("c.name", ex + ew * 40 / 100, y, ew * 60 / 100, "Name, e.g. Winterfrost");
					} else {
						field("c.name", ex, y, ew, "Name");
					}
					field("c.tag", ex, y + 30, ew, "Tag, e.g. CHRISTMAS COLLECTION");
					field("c.description", ex, y + 60, ew, "Shown on the store banner");
				}
			}
			case PLAYERS -> {
				field("p.name", contentX(), contentY(), contentWidth() - 90, "Player name or UUID");
				if (view != null) {
					int leftWidth = (contentWidth() - 14) * 46 / 100;
					field("p.amount", contentX(), contentY() + 158, leftWidth - 60, "Custom: 750 or -200");
					int rightX = contentX() + leftWidth + 14;
					field("p.give", rightX, contentY() + 116, contentX() + contentWidth() - rightX, "Give a cosmetic (type a name)");
				}
				this.setInitialFocus(fields.get("p.name"));
			}
			case REPORTS -> {
			}
		}
	}

	private TextFieldWidget field(String key, int x, int y, int width, String placeholder) {
		TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, y, Math.max(20, width), FIELD_H, Text.literal(placeholder));
		widget.setMaxLength(400);
		widget.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder(placeholder));
		widget.setText(fieldText.getOrDefault(key, ""));
		widget.setChangedListener(text -> {
			fieldText.put(key, text);
			if (key.equals("search") || key.equals("p.give")) {
				listScroll = 0;
				playerScroll = 0;
			}
		});
		fields.put(key, widget);
		addDrawableChild(widget);
		return widget;
	}

	private String text(String key) {
		TextFieldWidget widget = fields.get(key);
		return (widget != null ? widget.getText() : fieldText.getOrDefault(key, "")).trim();
	}

	private void setText(String key, String value) {
		fieldText.put(key, value);
		TextFieldWidget widget = fields.get(key);
		if (widget != null) {
			widget.setText(value);
		}
	}

	private void setStatus(String text, boolean error) {
		status = text;
		statusColor = error ? MenuKit.RED_TEXT : MenuKit.GREEN;
	}

	/** Runs an owner call; on success shows {@code done}, reloads the panel data and runs {@code after}. */
	private void call(String working, String done, Consumer<Consumer<JsonObject>> start, Runnable after) {
		if (busy) {
			return;
		}
		busy = true;
		setStatus(working, false);
		start.accept(result -> {
			busy = false;
			setStatus(done, false);
			if (after != null) {
				after.run();
			}
			reload();
		});
	}

	private Consumer<String> failed() {
		return error -> {
			busy = false;
			setStatus(error, true);
		};
	}

	// ---- Drawing ----

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		if (tab != Tab.PLAYERS && tab != Tab.REPORTS) {
			drawKpis(context);
		}
		if (data == null && tab != Tab.PLAYERS && tab != Tab.REPORTS) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(status.isEmpty() ? "Loading..." : status), contentX(), kpiBottom(),
					status.isEmpty() ? MenuKit.TEXT_3 : statusColor);
			return;
		}
		switch (tab) {
			case COSMETICS -> drawCosmetics(context, mouseX, mouseY);
			case BUNDLES -> drawBundles(context, mouseX, mouseY);
			case COLLECTIONS -> drawCollections(context, mouseX, mouseY);
			case PLAYERS -> drawPlayers(context, mouseX, mouseY);
			case REPORTS -> drawReports(context, mouseX, mouseY);
		}
		if (!status.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), contentWidth()), contentX(), contentBottom() - 8,
					statusColor);
		}
	}

	private void drawKpis(DrawContext context) {
		List<JsonObject> items = items();
		long[] counts = {
				items.stream().filter(i -> status(i).equals("released")).count(),
				items.stream().filter(i -> status(i).equals("testing")).count(),
				items.stream().filter(i -> status(i).equals("hidden")).count(),
				bundles().stream().filter(b -> b.has("active") && b.get("active").getAsBoolean()).count()};
		String[] labels = {"Released", "Testing", "Hidden", "Bundles live"};
		int[] colors = {MenuKit.GREEN, MenuKit.BLUE, MenuKit.TEXT_2, MenuKit.GOLD};
		int w = (contentWidth() - 3 * 8) / 4;
		for (int i = 0; i < 4; i++) {
			int x = contentX() + i * (w + 8);
			int y = contentY();
			MenuKit.panel(context, x, y, w, 30, 7);
			VeloDraw.fillCircle(context, x + 10f, y + 10f, 2.5f, colors[i]);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(labels[i]), x + 16, y + 7, MenuKit.TEXT_2);
			MenuKit.text(context, this.textRenderer, MenuKit.displaySm(data == null ? "-" : String.valueOf(counts[i])), x + 8, y + 18, MenuKit.TEXT);
		}
	}

	// ---- Cosmetics ----

	private void drawCosmetics(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int top = kpiBottom();
		int lw = contentWidth() * 55 / 100;
		// Filter chips right of the search field.
		int fx = x + lw - filterWidth();
		for (String f : FILTERS) {
			String label = cap(f);
			int w = this.textRenderer.getWidth(label) + 12;
			String chosen = f;
			hits.add(VeloUi.pill(context, fx, top + 1, w, 14, label, f.equals(filter) ? 1 : 0, mouseX, mouseY, () -> {
				filter = chosen;
				listScroll = 0;
			}));
			fx += w + 3;
		}
		// The list.
		String q = text("search").toLowerCase(Locale.ROOT);
		List<JsonObject> rows = items().stream()
				.filter(i -> filter.equals("all") || status(i).equals(filter))
				.filter(i -> q.isEmpty() || (str(i, "name", "") + " " + id(i)).toLowerCase(Locale.ROOT).contains(q))
				.sorted(Comparator.comparingInt((JsonObject o) -> STATUSES.indexOf(status(o)) == 1 ? 0 : STATUSES.indexOf(status(o)) == 0 ? 1 : 2)
						.thenComparing(o -> str(o, "name", "")))
				.toList();
		int ly = top + 22;
		drawList(context, x, ly, lw, contentBottom() - 14 - ly, rows.size(), (i, rx, ry, rw) -> {
			JsonObject item = rows.get(i);
			boolean on = id(item).equals(selectedItem);
			rowBackground(context, rx, ry, rw, on, mouseX, mouseY);
			StoreItems.drawArt(context, id(item), rx + 3, ry + 2, ROW - 7);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(str(item, "name", id(item))), rw - 150), rx + ROW + 2, ry + 4,
					MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.mono(id(item)), rw - 150), rx + ROW + 2, ry + 14, MenuKit.TEXT_3);
			statusPill(context, rx + rw - 110, ry + 6, status(item));
			price(context, rx + rw - 48, ry + 8, item.get("price").getAsInt());
			hits.add(new VeloUi.Hit(rx, ry, rw, ROW - 3, () -> selectItem(item)));
		});
		if (rows.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Nothing matches."), x + 4, ly + 4, MenuKit.TEXT_3);
		}
		// Featured collection, above the editor.
		int ex = editorX();
		int ew = editorWidth();
		MenuKit.text(context, this.textRenderer, MenuKit.small("STORE FEATURES"), ex, top + 4, MenuKit.TEXT_3);
		List<String> collections = collectionIds();
		String label = featuredChoice == null || featuredChoice.isEmpty() ? "The newest collection" : collectionName(featuredChoice);
		cycle(context, ex + 70, top, ew - 70 - 64, label, mouseX, mouseY, step -> featuredChoice = cycleValue(collections, featuredChoice, step));
		hits.add(VeloUi.pill(context, ex + ew - 58, top, 58, 14, "Feature", 1, mouseX, mouseY, () -> call("Saving...",
				"The Store now features " + label, ok -> StoreClient.adminFeatured(featuredChoice == null ? "" : featuredChoice, ok, failed()), null)));
		// Editor.
		JsonObject item = selectedItem == null ? null : item(selectedItem);
		int y = top + 22;
		if (item == null) {
			editorHint(context, ex, y, ew, "Pick a cosmetic to change its status, price, last day or collection.\n\nReleased = in the store (inside its window). "
					+ "Testing = only you hand it out, and everyone sees it on whoever has it. Hidden = out of the store, but owners keep it.");
			return;
		}
		MenuKit.panel(context, ex, y, ew, contentBottom() - 14 - y, 9);
		StoreItems.drawArt(context, selectedItem, ex + 10, y + 10, 44);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.display(str(item, "name", selectedItem)), ew - 70), ex + 62, y + 14, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.mono(selectedItem), ew - 70), ex + 62, y + 30, MenuKit.TEXT_3);
		fieldLabel(context, ex + 10, top + 84, "STATUS");
		int px = ex + 62;
		for (String s : STATUSES) {
			int w = this.textRenderer.getWidth(cap(s)) + 14;
			hits.add(VeloUi.pill(context, px, top + 80, w, 14, cap(s), s.equals(editStatus) ? 1 : 0, mouseX, mouseY, () -> editStatus = s));
			px += w + 4;
		}
		fieldLabel(context, ex + 10, top + 101, "PRICE");
		fieldLabel(context, ex + 100, top + 101, "LAST DAY IN THE STORE");
		int cy = top + 136;
		fieldLabel(context, ex + 10, cy + 3, "COLLECTION");
		String collection = editCollection == null || editCollection.isEmpty() ? "None" : collectionName(editCollection);
		cycle(context, ex + 80, cy, ew - 90, collection, mouseX, mouseY, step -> editCollection = cycleValue(collections, editCollection, step));
		hits.add(VeloUi.pill(context, ex + 10, cy + 26, ew - 20, 18, busy ? "Saving..." : "Save", 1, mouseX, mouseY, this::saveItem));
	}

	private static final List<String> FILTERS = List.of("all", "testing", "released", "hidden");

	private int filterWidth() {
		int w = 0;
		for (String f : FILTERS) {
			w += this.textRenderer.getWidth(cap(f)) + 12 + 3;
		}
		return w;
	}

	private void selectItem(JsonObject item) {
		selectedItem = id(item);
		editStatus = status(item);
		editCollection = str(item, "collection", "");
		fieldText.put("price", String.valueOf(item.get("price").getAsInt()));
		fieldText.put("until", item.has("availableUntil") && !item.get("availableUntil").isJsonNull() ? lastDay(item.get("availableUntil").getAsLong()) : "");
		layoutContent();
	}

	private void saveItem() {
		JsonObject change = new JsonObject();
		change.addProperty("id", selectedItem);
		change.addProperty("status", editStatus);
		try {
			change.addProperty("price", Integer.parseInt(text("price")));
		} catch (NumberFormatException e) {
			setStatus("Price: a whole number", true);
			return;
		}
		change.addProperty("collection", editCollection == null ? "" : editCollection);
		Long until = parseDay(text("until"));
		if (until == null && !text("until").isEmpty()) {
			setStatus("Last day: like 2026-12-31, or empty for no end", true);
			return;
		}
		if (until != null) {
			change.addProperty("availableUntil", until);
		}
		call("Saving...", "Saved " + str(item(selectedItem), "name", selectedItem),
				ok -> StoreClient.adminUpdateCosmetic(change, until == null, ok, failed()), null);
	}

	// ---- Bundles ----

	private void drawBundles(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int top = kpiBottom();
		int lw = contentWidth() * 55 / 100;
		List<JsonObject> bundles = bundles();
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(bundles.isEmpty()
				? "No bundles of your own: the store offers each collection at 20% off." : "A player who owns part of a bundle pays its rate for the rest."),
				lw - 96), x, top + 4, MenuKit.TEXT_3);
		hits.add(VeloUi.pill(context, x + lw - 90, top, 90, 15, "+ New bundle", 1, mouseX, mouseY, () -> editBundle(null)));
		int ly = top + 22;
		drawList(context, x, ly, lw, contentBottom() - 14 - ly, bundles.size(), (i, rx, ry, rw) -> {
			JsonObject bundle = bundles.get(i);
			boolean on = editingBundle != null && str(editingBundle, "id", "").equals(str(bundle, "id", null));
			rowBackground(context, rx, ry, rw, on, mouseX, mouseY);
			JsonArray ids = bundle.getAsJsonArray("items");
			for (int k = 0; k < Math.min(3, ids.size()); k++) {
				StoreItems.drawArt(context, ids.get(k).getAsString(), rx + 3 + k * 12, ry + 2, ROW - 7);
			}
			int tx = rx + 3 + Math.min(3, ids.size()) * 12 + ROW - 4;
			Long until = longOrNull(bundle, "availableUntil");
			boolean active = bundle.has("active") && bundle.get("active").getAsBoolean();
			boolean ended = until != null && until <= System.currentTimeMillis();
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(str(bundle, "name", "")), rw - tx + rx - 120), tx, ry + 4,
					MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(ids.size() + " items" + (until != null ? "  ·  until " + lastDay(until) : "")), tx, ry + 14,
					MenuKit.TEXT_3);
			statusPill(context, rx + rw - 110, ry + 6, ended ? "ended" : active ? "live" : "off");
			price(context, rx + rw - 48, ry + 8, bundle.get("price").getAsInt());
			hits.add(new VeloUi.Hit(rx, ry, rw, ROW - 3, () -> editBundle(bundle)));
		});
		int ex = editorX();
		int ew = editorWidth();
		if (editingBundle == null) {
			editorHint(context, ex, top, ew, "Pick a bundle to edit it, or make a new one.\n\nOnly items that are released and in their window are sold.");
			return;
		}
		boolean isNew = !editingBundle.has("id");
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(isNew ? "New bundle" : "Edit " + str(editingBundle, "name", "")), ex, top + 4, MenuKit.TEXT);
		int y = top + 22;
		MenuKit.text(context, this.textRenderer, MenuKit.small("PRICE"), ex, y + 21, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.small("LAST DAY"), ex + 100, y + 21, MenuKit.TEXT_3);
		int oy = y + 82;
		hits.add(VeloUi.pill(context, ex, oy, 76, 14, bundleActive ? "Live" : "Off", bundleActive ? 1 : 0, mouseX, mouseY, () -> bundleActive = !bundleActive));
		// Summary and a suggested price.
		int separate = 0;
		for (String id : bundleItems) {
			JsonObject item = item(id);
			separate += item == null ? 0 : item.get("price").getAsInt();
		}
		String summary = bundleItems.size() + (bundleItems.size() == 1 ? " item" : " items") + "  ·  " + String.format(Locale.ROOT, "%,d", separate) + " separately";
		try {
			int p = Integer.parseInt(text("b.price"));
			if (separate > 0) {
				long off = Math.round(100.0 * (separate - p) / separate);
				summary += "  ·  " + (off > 0 ? "save " + off + "%" : off < 0 ? "dearer than separately" : "no saving");
			}
		} catch (NumberFormatException ignored) {
			// No price yet.
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(summary), ew - 170), ex + 82, oy + 3, MenuKit.TEXT_2);
		int sep = separate;
		hits.add(VeloUi.pill(context, ex + ew - 80, oy, 80, 14, "Use 20% off", 0, mouseX, mouseY, () -> {
			if (sep > 0) {
				setText("b.price", String.valueOf(Math.max(10, Math.round(sep * 0.8 / 10.0) * 10)));
			}
		}));
		// Item picker.
		int py = oy + 22;
		int ph = contentBottom() - 14 - 26 - py;
		List<JsonObject> all = items().stream()
				.sorted(Comparator.comparing((JsonObject o) -> !status(o).equals("released")).thenComparing(o -> str(o, "name", ""))).toList();
		drawPicker(context, ex, py, ew, ph, all, mouseX, mouseY);
		int by = contentBottom() - 14 - 20;
		int bw = isNew ? ew : (ew - 8) / 2;
		hits.add(VeloUi.pill(context, ex, by, bw, 18, busy ? "Saving..." : isNew ? "Create bundle" : "Save bundle", 1, mouseX, mouseY, this::saveBundle));
		if (!isNew) {
			String id = str(editingBundle, "id", "");
			hits.add(VeloUi.pill(context, ex + bw + 8, by, bw, 18, "Delete", 3, mouseX, mouseY, () -> call("Deleting...", "Deleted the bundle",
					ok -> StoreClient.adminDeleteBundle(id, ok, failed()), () -> editingBundle = null)));
		}
	}

	private void drawPicker(DrawContext context, int x, int y, int w, int h, List<JsonObject> all, int mouseX, int mouseY) {
		pickerBox[0] = x;
		pickerBox[1] = y;
		pickerBox[2] = w;
		pickerBox[3] = h;
		int rowH = 18;
		int visible = Math.max(1, h / rowH);
		pickerScroll = Math.max(0, Math.min(pickerScroll, all.size() - visible));
		VeloDraw.fillRounded(context, x, y, w, h, 7, 0x0DFFFFFF);
		context.enableScissor(x, y, x + w, y + h);
		for (int i = pickerScroll; i < all.size() && i < pickerScroll + visible; i++) {
			JsonObject item = all.get(i);
			String id = id(item);
			int ry = y + 2 + (i - pickerScroll) * rowH;
			boolean on = bundleItems.contains(id);
			boolean hover = MenuKit.inside(mouseX, mouseY, x, ry, w, rowH);
			if (hover) {
				VeloDraw.fillRounded(context, x + 2, ry, w - 4, rowH - 1, 5, 0x0FFFFFFF);
			}
			VeloDraw.fillRounded(context, x + 6, ry + 4, 10, 10, 3, on ? MenuKit.accent() : 0x1FFFFFFF);
			if (on) {
				MenuKit.icon(context, "check", x + 7, ry + 5, 8, MenuKit.onAccent());
			}
			StoreItems.drawArt(context, id, x + 21, ry + 1, rowH - 2);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(str(item, "name", id)), w - 130), x + 23 + rowH, ry + 5, MenuKit.TEXT);
			String meta = String.format(Locale.ROOT, "%,d", item.get("price").getAsInt()) + (status(item).equals("released") ? "" : "  ·  " + status(item));
			Text metaText = MenuKit.ui(meta);
			MenuKit.text(context, this.textRenderer, metaText, x + w - 8 - this.textRenderer.getWidth(metaText), ry + 5, MenuKit.TEXT_3);
			hits.add(new VeloUi.Hit(x, ry, w, rowH, () -> {
				if (!bundleItems.remove(id)) {
					bundleItems.add(id);
				}
			}));
		}
		context.disableScissor();
	}

	private void editBundle(JsonObject bundle) {
		editingBundle = bundle == null ? new JsonObject() : bundle.deepCopy();
		bundleItems.clear();
		if (bundle != null) {
			bundle.getAsJsonArray("items").forEach(e -> bundleItems.add(e.getAsString()));
		}
		bundleActive = bundle == null || !bundle.has("active") || bundle.get("active").getAsBoolean();
		setText("b.id", "");
		setText("b.name", bundle == null ? "" : str(bundle, "name", ""));
		setText("b.price", bundle == null ? "" : String.valueOf(bundle.get("price").getAsInt()));
		setText("b.description", bundle == null ? "" : str(bundle, "description", ""));
		Long until = bundle == null ? null : longOrNull(bundle, "availableUntil");
		setText("b.until", until == null ? "" : lastDay(until));
		pickerScroll = 0;
		layoutContent();
	}

	private void saveBundle() {
		boolean isNew = !editingBundle.has("id");
		String id = isNew ? text("b.id").toLowerCase(Locale.ROOT) : str(editingBundle, "id", "");
		if (id.isEmpty() || text("b.name").isEmpty()) {
			setStatus("A bundle needs an id and a name", true);
			return;
		}
		if (bundleItems.size() < 2) {
			setStatus("Pick at least two items", true);
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("name", text("b.name"));
		body.addProperty("description", text("b.description"));
		try {
			body.addProperty("price", Integer.parseInt(text("b.price")));
		} catch (NumberFormatException e) {
			setStatus("Price: a whole number", true);
			return;
		}
		JsonArray items = new JsonArray();
		bundleItems.forEach(items::add);
		body.add("items", items);
		body.addProperty("active", bundleActive);
		Long until = parseDay(text("b.until"));
		if (until == null && !text("b.until").isEmpty()) {
			setStatus("Last day: like 2026-12-31, or empty for no end", true);
			return;
		}
		if (until != null) {
			body.addProperty("availableUntil", until);
		}
		call("Saving...", "Saved " + text("b.name"), ok -> StoreClient.adminBundle(body, ok, failed()), () -> editingBundle = body);
	}

	// ---- Collections ----

	private void drawCollections(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int top = kpiBottom();
		int lw = contentWidth() * 55 / 100;
		List<JsonObject> collections = new ArrayList<>();
		data.getAsJsonArray("collections").forEach(e -> collections.add(e.getAsJsonObject()));
		MenuKit.text(context, this.textRenderer, MenuKit.ui("Drops: the Store's banner shows the featured one."), x, top + 4, MenuKit.TEXT_3);
		hits.add(VeloUi.pill(context, x + lw - 104, top, 104, 15, "+ New collection", 1, mouseX, mouseY, () -> editCollection(null)));
		int ly = top + 22;
		drawList(context, x, ly, lw, contentBottom() - 14 - ly, collections.size(), (i, rx, ry, rw) -> {
			JsonObject c = collections.get(i);
			String id = str(c, "id", "");
			rowBackground(context, rx, ry, rw, id.equals(selectedCollection), mouseX, mouseY);
			long count = items().stream().filter(it -> str(it, "collection", "").equals(id)).count();
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(str(c, "name", id)), rw - 120), rx + 8, ry + 4, MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.small(str(c, "tag", "").toUpperCase(Locale.ROOT)), rw - 120), rx + 8, ry + 15,
					MenuKit.accentText());
			if (id.equals(str(data, "featured", ""))) {
				statusPill(context, rx + rw - 120, ry + 6, "featured");
			}
			Text n = MenuKit.ui(count + (count == 1 ? " item" : " items"));
			MenuKit.text(context, this.textRenderer, n, rx + rw - 8 - this.textRenderer.getWidth(n), ry + 8, MenuKit.TEXT_3);
			hits.add(new VeloUi.Hit(rx, ry, rw, ROW - 3, () -> editCollection(c)));
		});
		int ex = editorX();
		int ew = editorWidth();
		if (selectedCollection == null) {
			editorHint(context, ex, top, ew, "Pick a collection to rename it or change its tag and description - or make a new drop.");
			return;
		}
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(selectedCollection.isEmpty() ? "New collection" : "Edit " + collectionName(selectedCollection)),
				ex, top + 4, MenuKit.TEXT);
		int by = top + 22 + 84;
		hits.add(VeloUi.pill(context, ex, by, ew, 18, busy ? "Saving..." : selectedCollection.isEmpty() ? "Create collection" : "Save", 1, mouseX, mouseY,
				this::saveCollection));
	}

	private void editCollection(JsonObject c) {
		selectedCollection = c == null ? "" : str(c, "id", "");
		setText("c.id", "");
		setText("c.name", c == null ? "" : str(c, "name", ""));
		setText("c.tag", c == null ? "" : str(c, "tag", ""));
		setText("c.description", c == null ? "" : str(c, "description", ""));
		layoutContent();
	}

	private void saveCollection() {
		String id = selectedCollection.isEmpty() ? text("c.id").toLowerCase(Locale.ROOT) : selectedCollection;
		if (id.isEmpty() || text("c.name").isEmpty()) {
			setStatus("A collection needs an id and a name", true);
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("name", text("c.name"));
		body.addProperty("tag", text("c.tag"));
		body.addProperty("description", text("c.description"));
		call("Saving...", "Saved " + text("c.name"), ok -> StoreClient.adminCollection(body, ok, failed()), () -> selectedCollection = id);
	}

	// ---- Players ----

	private void lookup(String target) {
		if (target.isBlank() || busy) {
			return;
		}
		playerCall("Looking up...", target, (t, ok, err) -> StoreClient.adminLookup(t, ok, err));
	}

	private interface PlayerCall {
		void start(String target, Consumer<JsonObject> ok, Consumer<String> error);
	}

	private void playerCall(String working, String target, PlayerCall call) {
		busy = true;
		setStatus(working, false);
		call.start(target, result -> {
			busy = false;
			view = result;
			playerScroll = 0;
			setStatus("", false);
			StoreClient.refresh(true, null);
			layoutContent();
		}, failed());
	}

	private String uuid() {
		return view.get("uuid").getAsString();
	}

	private void drawPlayers(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		hits.add(VeloUi.pill(context, x + width - 84, y, 84, 16, busy ? "Working..." : "Look up", 1, mouseX, mouseY, () -> lookup(text("p.name"))));
		if (view == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Look a player up to see their coins, Velo+ and cosmetics - and manage them here. "
					+ "Several names at once (comma separated) for giveaways."), x, y + 30, MenuKit.TEXT_2);
			return;
		}
		y += 26;
		String name = view.get("username").getAsString();
		int balance = view.get("balance").getAsInt();
		long plusUntil = view.has("plusUntil") ? view.get("plusUntil").getAsLong() : 0;
		boolean plus = plusUntil > System.currentTimeMillis();
		JsonArray owned = view.getAsJsonArray("owned");
		PlayerHeads.draw(context, uuid(), name, x, y + 2, 28);
		MenuKit.text(context, this.textRenderer, MenuKit.display(name), x + 36, y + 4, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.mono(uuid()), x + 36, y + 18, MenuKit.TEXT_3);
		int tileW = (width - 12) / 3;
		stat(context, x, y + 34, tileW, "Coins", String.format(Locale.ROOT, "%,d", balance), 0xFFFFD56A);
		stat(context, x + tileW + 6, y + 34, tileW, "Velo+", plus ? remaining(plusUntil) : "Not subscribed", plus ? 0xFFFF6B6B : MenuKit.TEXT_2);
		stat(context, x + (tileW + 6) * 2, y + 34, tileW, "Cosmetics", String.valueOf(owned.size()), MenuKit.TEXT);

		int leftWidth = (width - 14) * 46 / 100;
		int ly = y + 78;
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Coins"), x, ly, MenuKit.TEXT);
		int px = x;
		int py = ly + 12;
		for (int amount : new int[] {-1000, -500, -100, 100, 500, 1000, 5000}) {
			String label = (amount > 0 ? "+" : "") + amount;
			int w = this.textRenderer.getWidth(label) + 12;
			if (px + w > x + leftWidth) {
				px = x;
				py += 18;
			}
			hits.add(VeloUi.pill(context, px, py, w, 14, label, amount > 0 ? 0 : 3, mouseX, mouseY,
					() -> playerCall("Applying coins...", uuid(), (t, ok, err) -> StoreClient.adminCoins(t, amount, "owner panel", ok, err))));
			px += w + 4;
		}
		hits.add(VeloUi.pill(context, x + leftWidth - 54, y + 132, 54, 16, "Apply", 1, mouseX, mouseY, () -> {
			try {
				int amount = Integer.parseInt(text("p.amount").replace("+", ""));
				playerCall("Applying coins...", uuid(), (t, ok, err) -> StoreClient.adminCoins(t, amount, "owner panel", ok, err));
			} catch (NumberFormatException e) {
				setStatus("Coins: a whole number, e.g. 500 or -200", true);
			}
		}));
		int vy = y + 160;
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Velo+"), x, vy, MenuKit.TEXT);
		px = x;
		py = vy + 12;
		for (int days : new int[] {1, 7, 14, 30, 90, 365}) {
			String label = "+" + days + "d";
			int w = this.textRenderer.getWidth(label) + 12;
			hits.add(VeloUi.pill(context, px, py, w, 14, label, 0, mouseX, mouseY,
					() -> playerCall("Giving Velo+...", uuid(), (t, ok, err) -> StoreClient.adminPlus(t, days, ok, err))));
			px += w + 4;
		}
		if (plus) {
			hits.add(VeloUi.pill(context, px, py, 62, 14, "End Velo+", 3, mouseX, mouseY,
					() -> playerCall("Ending Velo+...", uuid(), (t, ok, err) -> StoreClient.adminPlus(t, 0, ok, err))));
		}
		MenuKit.text(context, this.textRenderer, MenuKit.ui("Days add to what they have."), x, py + 20, MenuKit.TEXT_3);

		// Right: what they own, or what to give them.
		int rx = x + leftWidth + 14;
		int rw = x + width - rx;
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Cosmetics"), rx, ly, MenuKit.TEXT);
		String query = text("p.give").toLowerCase(Locale.ROOT);
		boolean giving = !query.isEmpty();
		List<String> ids = new ArrayList<>();
		if (giving) {
			for (JsonObject item : items()) {
				if ((str(item, "name", "").toLowerCase(Locale.ROOT).contains(query) || id(item).contains(query)) && !contains(owned, id(item))) {
					ids.add(id(item));
				}
			}
			ids.sort(String::compareTo);
		} else {
			owned.forEach(e -> ids.add(e.getAsString()));
		}
		int listY = contentY() + 26 + 114;
		listScroll = playerScroll;
		drawList(context, rx, listY, rw, contentBottom() - 14 - listY, ids.size(), (i, bx, by, bw) -> {
			String id = ids.get(i);
			JsonObject item = item(id);
			VeloDraw.fillRounded(context, bx, by, bw, ROW - 3, 6, 0x0DFFFFFF);
			StoreItems.drawArt(context, id, bx + 3, by + 2, ROW - 7);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(str(item, "name", id)), bw - 100), bx + ROW + 2, by + 4, MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.mono(id), bw - 100), bx + ROW + 2, by + 14, MenuKit.TEXT_3);
			if (giving) {
				hits.add(VeloUi.pill(context, bx + bw - 56, by + 4, 52, 14, "Give", 1, mouseX, mouseY, () -> {
					setText("p.give", "");
					playerCall("Giving...", uuid(), (t, ok, err) -> StoreClient.adminItem(t, id, true, ok, err));
				}));
			} else {
				hits.add(VeloUi.pill(context, bx + bw - 62, by + 4, 58, 14, "Remove", 3, mouseX, mouseY,
						() -> playerCall("Removing...", uuid(), (t, ok, err) -> StoreClient.adminItem(t, id, false, ok, err))));
			}
		});
		playerScroll = listScroll;
		if (ids.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(giving ? "No matching cosmetic they don't own." : "Owns no cosmetics."), rx + 4, listY + 4,
					MenuKit.TEXT_3);
		}
	}

	private void stat(DrawContext context, int x, int y, int w, String label, String value, int color) {
		MenuKit.panel(context, x, y, w, 34, 7);
		MenuKit.text(context, this.textRenderer, MenuKit.ui(label), x + 8, y + 6, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(value), w - 16), x + 8, y + 19, color);
	}

	// ---- Reports ----

	private void drawReports(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		hits.add(VeloUi.pill(context, x, y, 50, 14, "Open", allReports ? 0 : 1, mouseX, mouseY, () -> {
			allReports = false;
			loadReports();
		}));
		hits.add(VeloUi.pill(context, x + 54, y, 40, 14, "All", allReports ? 1 : 0, mouseX, mouseY, () -> {
			allReports = true;
			loadReports();
		}));
		if (reports == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Loading..."), x, y + 24, MenuKit.TEXT_3);
			return;
		}
		if (reports.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(allReports ? "No reports yet." : "No open reports - all quiet."), x, y + 24, MenuKit.TEXT_3);
			return;
		}
		int cardH = 74;
		int top = y + 22;
		int h = contentBottom() - 14 - top;
		listBox[0] = x;
		listBox[1] = top;
		listBox[2] = w;
		listBox[3] = h;
		int visible = Math.max(1, h / (cardH + 6));
		listScroll = Math.max(0, Math.min(listScroll, reports.size() - visible));
		for (int i = listScroll; i < reports.size() && i < listScroll + visible; i++) {
			JsonObject r = reports.get(i).getAsJsonObject();
			int cy = top + (i - listScroll) * (cardH + 6);
			MenuKit.panel(context, x, cy, w, cardH, 8);
			Text tag = MenuKit.small(str(r, "category", "report").toUpperCase(Locale.ROOT));
			int tw = this.textRenderer.getWidth(tag) + 10;
			VeloDraw.fillRounded(context, x + 8, cy + 8, tw, 11, 3, 0xFFD93A3A);
			MenuKit.text(context, this.textRenderer, tag, x + 13, cy + 11, 0xFFFFFFFF);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(str(r, "reporterName", "?") + "  reported  " + str(r, "targetName", "?")), x + 14 + tw, cy + 10,
					MenuKit.TEXT);
			String when = VeloUi.ago(r.get("at").getAsLong());
			MenuKit.text(context, this.textRenderer, MenuKit.ui(("party".equals(str(r, "context", "")) ? "Party chat" : "Direct messages") + "  ·  " + when
					+ (str(r, "reason", "").isBlank() ? "" : "  ·  \"" + str(r, "reason", "") + "\"")), x + 8, cy + 24, MenuKit.TEXT_2);
			JsonArray messages = r.has("messages") ? r.getAsJsonArray("messages") : new JsonArray();
			String target = str(r, "targetName", "");
			int my = cy + 36;
			for (int k = Math.max(0, messages.size() - 2); k < messages.size(); k++) {
				JsonObject m = messages.get(k).getAsJsonObject();
				String line = str(m, "fromName", "?") + ": " + str(m, "text", "");
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(line), w - 250), x + 8, my,
						str(m, "fromName", "").equals(target) ? 0xFFFFB4B4 : MenuKit.TEXT_2);
				my += 10;
			}
			if (messages.isEmpty()) {
				MenuKit.text(context, this.textRenderer, MenuKit.ui("No messages attached."), x + 8, my, MenuKit.TEXT_3);
			}
			String id = str(r, "id", "");
			if ("open".equals(str(r, "status", "open"))) {
				int bx = x + w - 8;
				for (Object[] action : new Object[][] {{"Ban for good", true, 0}, {"Ban 7 days", true, 7}, {"Dismiss", false, 0}}) {
					String label = (String) action[0];
					int bw = this.textRenderer.getWidth(label) + 14;
					bx -= bw;
					boolean ban = (boolean) action[1];
					int days = (int) action[2];
					hits.add(VeloUi.pill(context, bx, cy + cardH - 22, bw, 14, label, ban ? 3 : 0, mouseX, mouseY, () -> call("Working...",
							ban ? "Banned " + target + " from friends and parties" : "Dismissed", ok -> StoreClient.staffResolve(id, ban, days, ok, failed()),
							this::loadReports)));
					bx -= 4;
				}
			} else {
				Text done = MenuKit.ui(cap(str(r, "status", "")));
				MenuKit.text(context, this.textRenderer, done, x + w - 10 - this.textRenderer.getWidth(done), cy + cardH - 18, MenuKit.TEXT_3);
			}
		}
	}

	// ---- Shared drawing ----

	private interface RowPainter {
		void paint(int index, int x, int y, int w);
	}

	/** A scrolling list of {@code count} rows; remembers its box for the mouse wheel. */
	private void drawList(DrawContext context, int x, int y, int w, int h, int count, RowPainter painter) {
		listBox[0] = x;
		listBox[1] = y;
		listBox[2] = w;
		listBox[3] = h;
		int visible = Math.max(1, h / ROW);
		listScroll = Math.max(0, Math.min(listScroll, count - visible));
		context.enableScissor(x, y, x + w, y + h);
		for (int i = listScroll; i < count && i < listScroll + visible; i++) {
			painter.paint(i, x, y + (i - listScroll) * ROW, w - (count > visible ? 6 : 0));
		}
		context.disableScissor();
		if (count > visible) {
			int track = h;
			int thumb = Math.max(16, track * visible / count);
			int ty = y + (track - thumb) * listScroll / Math.max(1, count - visible);
			VeloDraw.fillRounded(context, x + w - 3, y, 3, track, 1.5f, 0x0FFFFFFF);
			VeloDraw.fillRounded(context, x + w - 3, ty, 3, thumb, 1.5f, 0x48FFFFFF);
		}
	}

	private void rowBackground(DrawContext context, int x, int y, int w, boolean selected, int mouseX, int mouseY) {
		boolean hover = MenuKit.inside(mouseX, mouseY, x, y, w, ROW - 3);
		VeloDraw.fillRounded(context, x, y, w, ROW - 3, 6, selected ? 0x1FFFFFFF : hover ? 0x14FFFFFF : 0x0AFFFFFF);
		if (selected) {
			VeloDraw.strokeRounded(context, x, y, w, ROW - 3, 6, (MenuKit.accent() & 0xFFFFFF) | 0xB0000000);
		}
	}

	private void statusPill(DrawContext context, int x, int y, String status) {
		int color = switch (status) {
			case "released", "live" -> MenuKit.GREEN;
			case "testing" -> MenuKit.BLUE;
			case "featured" -> MenuKit.GOLD;
			default -> MenuKit.TEXT_2;
		};
		Text label = MenuKit.small(status.toUpperCase(Locale.ROOT));
		int w = this.textRenderer.getWidth(label) + 10;
		VeloDraw.fillRounded(context, x, y, w, 11, 3, (color & 0xFFFFFF) | 0x2E000000);
		MenuKit.text(context, this.textRenderer, label, x + 5, y + 3, color);
	}

	private void price(DrawContext context, int x, int y, int price) {
		StoreCard.coin(context, x + 4f, y + 3.5f, 3.6f);
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(String.format(Locale.ROOT, "%,d", price)), x + 10, y, 0xFFFFD56A);
	}

	private void fieldLabel(DrawContext context, int x, int y, String label) {
		if (!label.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.small(label), x, y, MenuKit.TEXT_3);
		}
	}

	/** "‹ value ›": clicking the left half steps back, the right half forward. */
	private void cycle(DrawContext context, int x, int y, int w, String value, int mouseX, int mouseY, Consumer<Integer> step) {
		boolean hover = MenuKit.inside(mouseX, mouseY, x, y, w, 14);
		VeloDraw.fillRounded(context, x, y, w, 14, 4, hover ? 0x1AFFFFFF : 0x0FFFFFFF);
		VeloDraw.strokeRounded(context, x, y, w, 14, 4, 0x17FFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("‹"), x + 5, y + 3, MenuKit.TEXT_2);
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("›"), x + w - 9, y + 3, MenuKit.TEXT_2);
		Text text = MenuKit.fit(this.textRenderer, MenuKit.ui(value), w - 28);
		MenuKit.text(context, this.textRenderer, text, x + (w - this.textRenderer.getWidth(text)) / 2, y + 3, MenuKit.TEXT);
		hits.add(new VeloUi.Hit(x, y, w / 2, 14, () -> step.accept(-1)));
		hits.add(new VeloUi.Hit(x + w / 2, y, w - w / 2, 14, () -> step.accept(1)));
	}

	private void editorHint(DrawContext context, int x, int y, int w, String text) {
		int h = contentBottom() - 14 - y;
		VeloDraw.fillRounded(context, x, y, w, h, 9, 0x08FFFFFF);
		VeloDraw.strokeRounded(context, x, y, w, h, 9, 0x12FFFFFF);
		int ly = y + 14;
		for (String paragraph : text.split("\n")) {
			for (Text line : MenuKit.wrap(this.textRenderer, paragraph, MenuKit::ui, w - 24)) {
				MenuKit.text(context, this.textRenderer, line, x + 12, ly, MenuKit.TEXT_2);
				ly += 11;
			}
			if (paragraph.isEmpty()) {
				ly += 2;
			}
		}
	}

	// ---- Data helpers ----

	private List<JsonObject> items() {
		List<JsonObject> out = new ArrayList<>();
		if (data != null && data.has("items")) {
			data.getAsJsonArray("items").forEach(e -> out.add(e.getAsJsonObject()));
		}
		return out;
	}

	private JsonObject item(String id) {
		for (JsonObject item : items()) {
			if (id(item).equals(id)) {
				return item;
			}
		}
		return null;
	}

	private List<JsonObject> bundles() {
		List<JsonObject> out = new ArrayList<>();
		if (data != null && data.has("bundles") && data.get("bundles").isJsonArray()) {
			data.getAsJsonArray("bundles").forEach(e -> out.add(e.getAsJsonObject()));
		}
		return out;
	}

	/** "" (none / the newest) first, then every collection id. */
	private List<String> collectionIds() {
		List<String> ids = new ArrayList<>();
		ids.add("");
		if (data != null) {
			data.getAsJsonArray("collections").forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
		}
		return ids;
	}

	private String collectionName(String id) {
		if (data != null) {
			for (JsonElement c : data.getAsJsonArray("collections")) {
				if (str(c.getAsJsonObject(), "id", "").equals(id)) {
					return str(c.getAsJsonObject(), "name", id);
				}
			}
		}
		return id;
	}

	private static String cycleValue(List<String> values, String current, int step) {
		int index = Math.max(0, values.indexOf(current == null ? "" : current));
		return values.get(Math.floorMod(index + step, values.size()));
	}

	private static String id(JsonObject item) {
		return item.get("id").getAsString();
	}

	private static String status(JsonObject item) {
		return str(item, "status", "testing");
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	private static Long longOrNull(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : null;
	}

	/** The store keeps "until" as the start of the next day (UTC); this is the last day it's in. */
	private static String lastDay(long until) {
		return Instant.ofEpochMilli(until - 1).atZone(ZoneOffset.UTC).toLocalDate().toString();
	}

	private static Long parseDay(String text) {
		if (text.isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(text).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean contains(JsonArray array, String id) {
		for (JsonElement element : array) {
			if (element.getAsString().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	/** "12 days left" for a Velo+ end time. */
	public static String remaining(long until) {
		Duration left = Duration.between(Instant.now(), Instant.ofEpochMilli(until));
		long days = left.toDays();
		String text = days >= 1 ? days + (days == 1 ? " day" : " days") + (left.toHours() % 24 > 0 ? " " + left.toHours() % 24 + "h" : "")
				: Math.max(1, left.toHours()) + "h";
		return text + " left";
	}

	public static String date(long epochMillis) {
		return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).toString();
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
		int step = -(int) Math.signum(verticalAmount) * 2;
		if (tab == Tab.BUNDLES && editingBundle != null && MenuKit.inside((int) mouseX, (int) mouseY, pickerBox[0], pickerBox[1], pickerBox[2], pickerBox[3])) {
			pickerScroll = Math.max(0, pickerScroll + step);
			return true;
		}
		if (MenuKit.inside((int) mouseX, (int) mouseY, listBox[0], listBox[1], listBox[2], listBox[3])) {
			if (tab == Tab.PLAYERS) {
				playerScroll = Math.max(0, playerScroll + step);
			} else {
				listScroll = Math.max(0, listScroll + (tab == Tab.REPORTS ? step / 2 : step));
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		TextFieldWidget name = fields.get("p.name");
		if ((input.key() == net.veloclient.velo.client.util.Keys.ENTER || input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER)
				&& name != null && name.isFocused()) {
			lookup(name.getText().trim());
			return true;
		}
		return super.keyPressed(input);
	}
}
