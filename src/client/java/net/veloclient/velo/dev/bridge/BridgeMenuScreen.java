package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloToggle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A server menu (Velo Bridge) as a real Velo window: text, dividers, buttons, list entries with an item icon and a
 * detail line, input fields, toggles and sliders. Clicking a button or entry sends {@code menu_action} with every
 * field's value; the server decides what happens (often it closes or swaps the menu). The window says which server it
 * comes from, so it can't pass for Velo's own.
 */
final class BridgeMenuScreen extends VeloWindow {

	private static final int ROW = 24;
	private final JsonObject menu;
	private final String id;
	private final Map<String, String> values = new LinkedHashMap<>();
	private final Map<String, TextFieldWidget> fields = new LinkedHashMap<>();
	private final List<Runnable> draws = new ArrayList<>();
	private boolean closedByServer;

	private BridgeMenuScreen(JsonObject menu, int width, int height) {
		super(Text.literal(plain(str(menu, "title", "Menu"))), width, height);
		this.menu = menu;
		this.id = str(menu, "id", "menu");
		for (JsonElement e : items(menu)) {
			JsonObject o = e.getAsJsonObject();
			String type = str(o, "type", "");
			if (type.equals("toggle") || type.equals("slider") || type.equals("input")) {
				values.put(str(o, "id", ""), o.has("value") ? o.get("value").getAsString() : "");
			}
		}
		onClosed(() -> {
			if (!closedByServer) {
				JsonObject m = new JsonObject();
				m.addProperty("t", "menu_closed");
				m.addProperty("menu", id);
				BridgeNet.send(m.toString());
			}
		});
	}

	static void open(JsonObject m) {
		int width = Math.max(240, Math.min(480, m.has("width") ? m.get("width").getAsInt() : 320));
		int inner = width - 32;
		int h = 0;
		for (JsonElement e : items(m)) {
			h += rowHeight(e.getAsJsonObject(), inner);
		}
		boolean sub = m.has("subtitle") && !str(m, "subtitle", "").isBlank();
		int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
		int height = Math.min(screenH - 24, 30 + 16 + (sub ? 16 : 0) + 14 + h + 12);
		if (ClientCompat.currentScreen() instanceof BridgeMenuScreen old) {
			old.closedByServer = true; // replaced, not closed
		}
		MinecraftClient.getInstance().setScreen(new BridgeMenuScreen(m, width, height));
	}

	static void closeFromServer(String id) {
		if (ClientCompat.currentScreen() instanceof BridgeMenuScreen s && (id == null || id.isEmpty() || id.equals(s.id))) {
			s.closedByServer = true;
			MinecraftClient.getInstance().setScreen(null);
		}
	}

	private static JsonArray items(JsonObject m) {
		return m.has("items") && m.get("items").isJsonArray() ? m.getAsJsonArray("items") : new JsonArray();
	}

	private static int rowHeight(JsonObject o, int inner) {
		return switch (str(o, "type", "")) {
			case "text" -> VeloUi.wrap(plain(str(o, "text", "")), inner).size() * 11 + 6;
			case "divider" -> 12;
			case "entry" -> 34;
			case "input" -> 34;
			default -> ROW;
		};
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		draws.clear();
		fields.clear();
		int x = contentX(), w = contentWidth();
		int y = contentY() + 14 + (str(menu, "subtitle", "").isBlank() ? 0 : 14);
		int bottom = contentBottom();
		for (JsonElement e : items(menu)) {
			JsonObject o = e.getAsJsonObject();
			int h = rowHeight(o, w);
			if (y + h > bottom + 4) {
				break; // more than fits: the server should split it up
			}
			String type = str(o, "type", "button");
			String itemId = str(o, "id", "");
			String label = plain(str(o, "label", ""));
			int top = y;
			switch (type) {
				case "text" -> {
					List<String> lines = VeloUi.wrap(plain(str(o, "text", "")), w);
					draws.add(() -> {
						int ly = top;
						for (String line : lines) {
							drawText(line, x, ly, VeloStyle.text());
							ly += 11;
						}
					});
				}
				case "divider" -> draws.add(() -> currentContext.fill(x, top + 5, x + w, top + 6, VeloStyle.border()));
				case "entry" -> {
					String detail = plain(str(o, "detail", ""));
					String icon = str(o, "icon", null);
					addDrawableChild(new VeloButton(x, top, w, 30, Text.literal(""), b -> click(itemId)));
					draws.add(() -> {
						int tx = x + 8;
						if (icon != null && !icon.isBlank()) {
							try {
								net.veloclient.velo.dev.storage.StorageMc.drawItem(currentContext, net.veloclient.velo.dev.storage.StorageMc.stack(icon, 1), tx, top + 7, "");
								tx += 22;
							} catch (RuntimeException ignored) {
								// unknown item
							}
						}
						drawText(VeloUi.trim(label, w - (tx - x) - 20), tx, top + (detail.isEmpty() ? 11 : 5), VeloStyle.text());
						if (!detail.isEmpty()) {
							drawText(VeloUi.trim(detail, w - (tx - x) - 20), tx, top + 17, VeloStyle.textMuted());
						}
						drawText("›", x + w - 12, top + 11, VeloStyle.textMuted());
					});
				}
				case "input" -> {
					draws.add(() -> drawText(label, x, top, VeloStyle.textMuted()));
					TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, top + 11, w, 18, Text.literal(label));
					f.setMaxLength(128);
					f.setText(values.getOrDefault(itemId, ""));
					String ph = str(o, "placeholder", "");
					if (!ph.isEmpty()) {
						f.setPlaceholder(VeloUi.placeholder(ph));
					}
					f.setChangedListener(v -> values.put(itemId, v));
					fields.put(itemId, f);
					addDrawableChild(f);
				}
				case "toggle" -> addDrawableChild(new VeloToggle(x, top + 2, w, Text.literal(label), () -> "true".equals(values.get(itemId)),
						v -> values.put(itemId, String.valueOf(v))));
				case "slider" -> {
					double min = num(o, "min", 0), max = num(o, "max", 10), step = Math.max(0.0001, num(o, "step", 1));
					addDrawableChild(new VeloSlider(x, top + 2, w, 20, label, min, max, () -> num(values.get(itemId), min),
							v -> values.put(itemId, fmt(v)), v -> Math.round(v / step) * step, v -> label + ": " + fmt(v)));
				}
				default -> {
					VeloButton b = new VeloButton(x, top + 1, w, 20, Text.literal(label), btn -> click(itemId));
					if ("primary".equals(str(o, "style", ""))) {
						b.primary();
					}
					addDrawableChild(b);
				}
			}
			y += h;
		}
	}

	private void click(String itemId) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "menu_action");
		m.addProperty("menu", id);
		m.addProperty("id", itemId);
		JsonObject v = new JsonObject();
		values.forEach(v::addProperty);
		m.add("values", v);
		BridgeNet.send(m.toString());
	}

	private DrawContext currentContext;

	private void drawText(String s, int x, int y, int color) {
		currentContext.drawTextWithShadow(this.textRenderer, s, x, y, color);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		currentContext = context;
		int x = contentX(), y = contentY();
		int accent = menu.has("color") ? VeloBridgeClient.color(str(menu, "color", "#5aa9ff")) : VeloBridgeClient.serverColor();
		var tag = MenuKit.small(("From " + VeloBridgeClient.server()).toUpperCase(java.util.Locale.ROOT));
		VeloDraw.fillRounded(context, x, y, 3, 8, 1, accent | 0xFF000000);
		MenuKit.text(context, this.textRenderer, tag, x + 7, y, VeloStyle.textMuted());
		String sub = plain(str(menu, "subtitle", ""));
		if (!sub.isBlank()) {
			drawText(VeloUi.trim(sub, contentWidth()), x, y + 14, VeloStyle.textMuted());
		}
		for (Runnable r : draws) {
			r.run();
		}
	}

	/** Server text may carry &-colour codes; Velo windows use their own colours. */
	private static String plain(String s) {
		return s == null ? "" : s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0);
	}

	private static double num(JsonObject o, String k, double fallback) {
		try {
			return o.has(k) ? o.get(k).getAsDouble() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static double num(String s, double fallback) {
		try {
			return s == null || s.isEmpty() ? fallback : Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String str(JsonObject o, String k, String fallback) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : fallback;
	}
}
