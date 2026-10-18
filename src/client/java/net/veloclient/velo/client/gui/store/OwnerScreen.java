package net.veloclient.velo.client.gui.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owner panel, in the game: type a player, see their coins, Velo+ (and how long is left) and every cosmetic they own,
 * and manage it right there - coin buttons, Velo+ days, a Remove button on each cosmetic, and a quick "give a cosmetic"
 * search. The same panel as the launcher's Owner > Players tab (prices, windows and collections are launcher-only).
 * The server checks every call is from an owner.
 */
public final class OwnerScreen extends VeloWindow {

	private static final int ROW = 24;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final Map<String, JsonObject> catalog = new HashMap<>();
	private TextFieldWidget nameField;
	private TextFieldWidget amountField;
	private TextFieldWidget giveField;
	private JsonObject view;
	private String status = "";
	private int statusColor;
	private boolean busy;
	private int scroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public OwnerScreen(Screen parent) {
		super(Text.literal("Owner"), 680, 440);
		returnTo(parent);
		StoreClient.adminCosmetics(result -> result.getAsJsonArray("items").forEach(e -> catalog.put(e.getAsJsonObject().get("id").getAsString(),
				e.getAsJsonObject())), e -> setStatus(e, true));
	}

	@Override
	protected void layoutContent() {
		String keepName = nameField == null ? "" : nameField.getText();
		String keepAmount = amountField == null ? "" : amountField.getText();
		String keepGive = giveField == null ? "" : giveField.getText();
		this.clearChildren();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		nameField = new TextFieldWidget(this.textRenderer, x, y, width - 90, 18, Text.literal("Player"));
		nameField.setPlaceholder(Text.literal("Player name or UUID"));
		nameField.setText(keepName);
		addDrawableChild(nameField);
		amountField = null;
		giveField = null;
		if (view != null) {
			int leftWidth = (width - 14) * 46 / 100;
			amountField = new TextFieldWidget(this.textRenderer, x, y + 158, leftWidth - 60, 18, Text.literal("Coins"));
			amountField.setPlaceholder(Text.literal("Custom: 750 or -200"));
			amountField.setText(keepAmount);
			addDrawableChild(amountField);
			int rightX = x + leftWidth + 14;
			giveField = new TextFieldWidget(this.textRenderer, rightX, y + 116, x + width - rightX, 18, Text.literal("Give"));
			giveField.setPlaceholder(Text.literal("Give a cosmetic (type a name)"));
			giveField.setText(keepGive);
			giveField.setChangedListener(text -> scroll = 0);
			addDrawableChild(giveField);
		}
		this.setInitialFocus(nameField);
	}

	private void setStatus(String text, boolean error) {
		status = text;
		statusColor = error ? 0xFFFF7B7B : 0xFF6FE0A4;
	}

	// ---- Actions ----

	private void lookup(String target) {
		if (target.isBlank() || busy) {
			return;
		}
		run("Looking up...", target, (t, ok, err) -> StoreClient.adminLookup(t, ok, err));
	}

	private interface Call {
		void start(String target, java.util.function.Consumer<JsonObject> ok, java.util.function.Consumer<String> error);
	}

	private void run(String working, String target, Call call) {
		busy = true;
		setStatus(working, false);
		call.start(target, result -> {
			busy = false;
			view = result;
			scroll = 0;
			setStatus("", false);
			StoreClient.refresh(true, null);
			layoutContent();
		}, error -> {
			busy = false;
			setStatus(error, true);
		});
	}

	private String uuid() {
		return view.get("uuid").getAsString();
	}

	private void coins(int amount, String note) {
		run("Applying coins...", uuid(), (t, ok, err) -> StoreClient.adminCoins(t, amount, note, ok, err));
	}

	private void plusDays(int days) {
		run(days > 0 ? "Giving Velo+..." : "Ending Velo+...", uuid(), (t, ok, err) -> StoreClient.adminPlus(t, days, ok, err));
	}

	private void item(String id, boolean grant) {
		run(grant ? "Giving..." : "Removing...", uuid(), (t, ok, err) -> StoreClient.adminItem(t, id, grant, ok, err));
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		hits.add(VeloUi.pill(context, x + width - 84, y, 84, 18, busy ? "Working..." : "Look up", 1, mouseX, mouseY, () -> lookup(nameField.getText().trim())));
		if (view == null) {
			context.drawTextWithShadow(this.textRenderer, "Look a player up to see their coins, Velo+ and cosmetics - and manage them here.", x, y + 30,
					VeloStyle.textMuted());
			drawStatus(context, x, width);
			return;
		}
		drawPlayer(context, x, y + 26, width, mouseX, mouseY);
		drawStatus(context, x, width);
	}

	private void drawStatus(DrawContext context, int x, int width) {
		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, width), x, contentBottom() - 9, statusColor);
		}
	}

	private void drawPlayer(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		String name = view.get("username").getAsString();
		int balance = view.get("balance").getAsInt();
		long plusUntil = view.has("plusUntil") ? view.get("plusUntil").getAsLong() : 0;
		boolean plus = plusUntil > System.currentTimeMillis();
		JsonArray owned = view.getAsJsonArray("owned");

		PlayerHeads.draw(context, uuid(), name, x, y + 2, 28);
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(name), x + 36, y + 3, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, uuid(), x + 36, y + 17, VeloStyle.textFaint());
		int leftWidth = (width - 14) * 46 / 100;
		int tileW = (width - 12) / 3;
		stat(context, x, y + 34, tileW, "Coins", String.format(Locale.ROOT, "%,d", balance), 0xFFFFD56A);
		stat(context, x + tileW + 6, y + 34, tileW, "Velo+", plus ? remaining(plusUntil) : "Not subscribed", plus ? 0xFFFF6B6B : VeloStyle.textMuted());
		stat(context, x + (tileW + 6) * 2, y + 34, tileW, "Cosmetics", String.valueOf(owned.size()), VeloStyle.text());

		// Left: coins and Velo+.
		int ly = y + 78;
		context.drawTextWithShadow(this.textRenderer, "Coins", x, ly, VeloStyle.text());
		int px = x;
		int py = ly + 12;
		for (int amount : new int[] {-1000, -500, -100, 100, 500, 1000, 5000}) {
			String label = (amount > 0 ? "+" : "") + amount;
			int w = this.textRenderer.getWidth(label) + 12;
			if (px + w > x + leftWidth) {
				px = x;
				py += 18;
			}
			hits.add(VeloUi.pill(context, px, py, w, 14, label, amount > 0 ? 0 : 3, mouseX, mouseY, () -> coins(amount, "owner panel")));
			px += w + 4;
		}
		hits.add(VeloUi.pill(context, x + leftWidth - 54, y + 132, 54, 18, "Apply", 1, mouseX, mouseY, () -> {
			try {
				coins(Integer.parseInt(amountField.getText().trim().replace("+", "")), "owner panel");
			} catch (NumberFormatException e) {
				setStatus("Coins: a whole number, e.g. 500 or -200", true);
			}
		}));
		int vy = y + 160;
		context.drawTextWithShadow(this.textRenderer, "Velo+", x, vy, VeloStyle.text());
		px = x;
		py = vy + 12;
		for (int days : new int[] {1, 7, 14, 30, 90, 365}) {
			String label = "+" + days + "d";
			int w = this.textRenderer.getWidth(label) + 12;
			hits.add(VeloUi.pill(context, px, py, w, 14, label, 0, mouseX, mouseY, () -> plusDays(days)));
			px += w + 4;
		}
		if (plus) {
			hits.add(VeloUi.pill(context, px, py, 62, 14, "End Velo+", 3, mouseX, mouseY, () -> plusDays(0)));
		}
		context.drawTextWithShadow(this.textRenderer, "Days add to what they have.", x, py + 20, VeloStyle.textFaint());

		// Right: cosmetics.
		int rx = x + leftWidth + 14;
		int rw = x + width - rx;
		context.drawTextWithShadow(this.textRenderer, "Cosmetics", rx, ly, VeloStyle.text());
		listX = rx;
		listY = y + 114;
		listW = rw;
		listH = contentBottom() - 14 - listY;
		String query = giveField == null ? "" : giveField.getText().trim().toLowerCase(Locale.ROOT);
		List<String> ids = new ArrayList<>();
		boolean giving = !query.isEmpty();
		if (giving) {
			for (var entry : catalog.entrySet()) {
				String itemName = str(entry.getValue(), "name", entry.getKey()).toLowerCase(Locale.ROOT);
				if ((itemName.contains(query) || entry.getKey().contains(query)) && !contains(owned, entry.getKey())) {
					ids.add(entry.getKey());
				}
			}
			ids.sort(String::compareTo);
		} else {
			owned.forEach(e -> ids.add(e.getAsString()));
		}
		int visible = Math.max(1, listH / ROW);
		scroll = Math.max(0, Math.min(scroll, ids.size() - visible));
		context.enableScissor(listX, listY, listX + listW, listY + listH);
		for (int i = scroll; i < ids.size() && i < scroll + visible; i++) {
			String id = ids.get(i);
			JsonObject item = catalog.get(id);
			int ry = listY + (i - scroll) * ROW;
			int accent = accent(item);
			VeloDraw.fillRounded(context, listX, ry, listW, ROW - 3, 6, VeloStyle.card());
			VeloDraw.fillRounded(context, listX + 4, ry + 3, 16, 16, 4, accent);
			String glyph = id.startsWith("wings_") ? "W" : id.startsWith("cape_") ? "C" : id.startsWith("aura_") ? "A" : "?";
			context.drawTextWithShadow(this.textRenderer, glyph, listX + 4 + (16 - this.textRenderer.getWidth(glyph)) / 2, ry + 7, 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(str(item, "name", id), listW - 110), listX + 26, ry + 3, VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(id, listW - 110), listX + 26, ry + 12, VeloStyle.textFaint());
			if (giving) {
				hits.add(VeloUi.pill(context, listX + listW - 56, ry + 2, 52, 16, "Give", 1, mouseX, mouseY, () -> {
					giveField.setText("");
					item(id, true);
				}));
			} else {
				hits.add(VeloUi.pill(context, listX + listW - 62, ry + 2, 58, 16, "Remove", 3, mouseX, mouseY, () -> item(id, false)));
			}
		}
		context.disableScissor();
		if (ids.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, giving ? "No matching cosmetic they don't own." : "Owns no cosmetics.", listX + 4, listY + 4,
					VeloStyle.textMuted());
		} else if (ids.size() > visible) {
			context.drawTextWithShadow(this.textRenderer, "scroll for more (" + ids.size() + ")", listX, listY + listH + 2, VeloStyle.textFaint());
		}
	}

	private void stat(DrawContext context, int x, int y, int w, String label, String value, int color) {
		VeloDraw.fillRounded(context, x, y, w, 34, 7, VeloStyle.card());
		VeloDraw.strokeRounded(context, x, y, w, 34, 7, VeloStyle.border());
		context.drawTextWithShadow(this.textRenderer, label, x + 8, y + 5, VeloStyle.textFaint());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(value, w - 16), x + 8, y + 18, color);
	}

	private static boolean contains(JsonArray array, String id) {
		for (var element : array) {
			if (element.getAsString().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	private static int accent(JsonObject item) {
		String hex = str(item, "accent", "#ff4d4d");
		try {
			return 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
		} catch (NumberFormatException e) {
			return 0xFFFF4D4D;
		}
	}

	/** "12 days left (until 2026-10-30)" for a Velo+ end time. */
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
		if (view != null && mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
			scroll = Math.max(0, scroll - (int) Math.signum(verticalAmount) * 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if ((input.key() == net.veloclient.velo.client.util.Keys.ENTER || input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER)
				&& nameField != null && nameField.isFocused()) {
			lookup(nameField.getText().trim());
			return true;
		}
		return super.keyPressed(input);
	}
}
