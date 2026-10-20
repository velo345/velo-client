package net.veloclient.velo.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.keybind.BindingAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Controls screen's keyboard: every key where it sits on a real keyboard (labels from the player's own layout),
 * coloured by what uses it - Minecraft blue, Velo violet, other mods teal, red when two actions fight over it - and
 * free keys outlined in green, so you can see at a glance where it's safe to bind something. Click a key to see
 * what it does on the right; there you change or unbind it, move one side of a conflict to a free key nearby, or
 * bind something from "Not bound yet" to a free key.
 */
final class KeyboardMapView {

	private static final int MINECRAFT = 0xFF5AA9FF;
	private static final int VELO = 0xFFBCA6FF;
	private static final int MODS = 0xFF3DD6C6;
	private static final int CONFLICT = 0xFFFF5A5F;
	private static final int FREE = 0xFF3DDC97;

	/** One key cap: its key id, a short label when ours reads better than the game's, and its width in key units. */
	private record Cap(String id, String label, float units) {
	}

	private static Cap k(String id) {
		return new Cap("key.keyboard." + id, null, 1f);
	}

	private static Cap k(String id, String label, float units) {
		return new Cap(id == null ? null : "key.keyboard." + id, label, units);
	}

	private static final List<List<Cap>> ROWS = List.of(
			List.of(k("escape", "Esc", 1f), k(null, null, 0.5f), k("f1"), k("f2"), k("f3"), k("f4"), k(null, null, 0.4f), k("f5"), k("f6"), k("f7"), k("f8"),
					k(null, null, 0.4f), k("f9"), k("f10"), k("f11"), k("f12")),
			List.of(k("grave.accent"), k("1"), k("2"), k("3"), k("4"), k("5"), k("6"), k("7"), k("8"), k("9"), k("0"), k("minus"), k("equal"),
					k("backspace", "⌫", 2f)),
			List.of(k("tab", "Tab", 1.5f), k("q"), k("w"), k("e"), k("r"), k("t"), k("y"), k("u"), k("i"), k("o"), k("p"), k("left.bracket"),
					k("right.bracket"), k("backslash", null, 1.5f)),
			List.of(k("caps.lock", "Caps", 1.75f), k("a"), k("s"), k("d"), k("f"), k("g"), k("h"), k("j"), k("k"), k("l"), k("semicolon"),
					k("apostrophe"), k("enter", "Enter", 2.25f)),
			List.of(k("left.shift", "Shift", 1.25f), k("world.2", "<", 1f), k("z"), k("x"), k("c"), k("v"), k("b"), k("n"), k("m"), k("comma"), k("period"),
					k("slash"), k("right.shift", "R-Shift", 2.75f)),
			List.of(k("left.control", "Ctrl", 1.5f), k("left.win", "Win", 1.25f), k("left.alt", "Alt", 1.25f), k("space", "Space", 6.25f),
					k("right.alt", "Alt Gr", 1.25f), k("right.control", "Ctrl", 1.5f)));

	private static final List<List<Cap>> NAV = List.of(
			List.of(k("insert", "Ins", 1f), k("home", "Home", 1f), k("page.up", "PgUp", 1f)),
			List.of(k("delete", "Del", 1f), k("end", "End", 1f), k("page.down", "PgDn", 1f)),
			List.of(k(null, null, 1f), k("up", "↑", 1f), k(null, null, 1f)),
			List.of(k("left", "←", 1f), k("down", "↓", 1f), k("right", "→", 1f)));

	private static final String[][] MOUSE = {{"key.mouse.left", "L"}, {"key.mouse.right", "R"}, {"key.mouse.middle", "M"}, {"key.mouse.4", "M4"},
			{"key.mouse.5", "M5"}};

	/** Keys the operating system takes before Minecraft sees them. */
	private static final List<String> SYSTEM = List.of("key.keyboard.left.win", "key.keyboard.right.win");

	private String selected = "key.keyboard.v";
	private boolean showFree = true;
	/** Where each drawn key is this frame (for "free keys nearby"). */
	private final Map<String, float[]> positions = new LinkedHashMap<>();

	interface Host {
		void listen(KeyBinding binding);

		void status(String text);

		String query();
	}

	private static int colorOf(KeyBinding binding) {
		String category = BindingAccess.categoryId(binding);
		if (category.startsWith("minecraft:")) {
			return MINECRAFT;
		}
		return category.startsWith("velo") ? VELO : MODS;
	}

	private static String sourceOf(KeyBinding binding) {
		String category = BindingAccess.categoryId(binding);
		if (category.startsWith("minecraft:")) {
			return "Minecraft";
		}
		return category.startsWith("velo") ? "Velo" : BindingAccess.categoryLabel(binding);
	}

	private Map<String, List<KeyBinding>> byKey() {
		Map<String, List<KeyBinding>> map = new LinkedHashMap<>();
		for (KeyBinding binding : BindingAccess.all()) {
			if (!BindingAccess.isUnbound(binding)) {
				map.computeIfAbsent(BindingAccess.keyId(binding), id -> new ArrayList<>()).add(binding);
			}
		}
		return map;
	}

	private static boolean conflict(List<KeyBinding> bindings) {
		return bindings != null && bindings.size() > 1 && !BindingAccess.conflicts(bindings.get(0)).isEmpty();
	}

	void render(DrawContext context, TextRenderer renderer, int x, int y, int w, int h, int mouseX, int mouseY, List<VeloUi.Hit> hits, Host host) {
		Map<String, List<KeyBinding>> map = byKey();
		positions.clear();
		int asideW = Math.max(120, Math.min(190, Math.round(w * 0.27f)));
		int mapW = w - asideW - 10;

		// ---- Toolbar: free keys toggle + legend ----
		int free = 0;
		List<String> all = new ArrayList<>();
		for (List<Cap> row : ROWS) {
			row.stream().filter(c -> c.id() != null).forEach(c -> all.add(c.id()));
		}
		for (List<Cap> row : NAV) {
			row.stream().filter(c -> c.id() != null).forEach(c -> all.add(c.id()));
		}
		for (String[] m : MOUSE) {
			all.add(m[0]);
		}
		for (String id : all) {
			if (!map.containsKey(id) && !SYSTEM.contains(id)) {
				free++;
			}
		}
		Text freeLabel = MenuKit.uiBold("Show free keys · " + free);
		int fw = MenuKit.width(renderer, freeLabel) + 18;
		VeloDraw.fillRounded(context, x, y, fw, 15, 5, showFree ? 0x1A3DDC97 : 0x0DFFFFFF);
		VeloDraw.strokeRounded(context, x, y, fw, 15, 5, showFree ? 0x593DDC97 : 0x17FFFFFF);
		VeloDraw.fillCircle(context, x + 7, y + 7.5f, 2.5f, showFree ? FREE : 0xFF6E6E78);
		MenuKit.text(context, renderer, freeLabel, x + 13, y + 4, showFree ? 0xFFA6F0CF : MenuKit.TEXT_2);
		hits.add(new VeloUi.Hit(x, y, fw, 15, () -> showFree = !showFree));
		int lx = x + fw + 12;
		for (Object[] legend : new Object[][] {{"Minecraft", MINECRAFT}, {"Velo", VELO}, {"Other mods", MODS}, {"Conflict", CONFLICT}}) {
			Text label = MenuKit.ui((String) legend[0]);
			int lw = MenuKit.width(renderer, label) + 12;
			if (lx + lw > x + mapW) {
				break;
			}
			VeloDraw.fillRounded(context, lx, y + 4, 7, 7, 2, (int) legend[1]);
			MenuKit.text(context, renderer, label, lx + 10, y + 4, MenuKit.TEXT_2);
			lx += lw + 6;
		}

		// ---- Keyboard + nav cluster + mouse ----
		float units = 15f + 0.6f + 3f + 0.6f + 2.6f;
		float u = Math.min(mapW / units, (h - 24 - 54) / 6.6f);
		float gap = Math.max(1f, u * 0.08f);
		int top = y + 22;
		String q = host.query().trim().toLowerCase(Locale.ROOT);
		for (int r = 0; r < ROWS.size(); r++) {
			float kx = x;
			float ky = top + r * u + (r > 0 ? u * 0.25f : 0);
			for (Cap cap : ROWS.get(r)) {
				float cw = cap.units() * u;
				if (cap.id() != null) {
					drawKey(context, renderer, cap, kx, ky, cw - gap, u - gap, map, q, mouseX, mouseY, hits);
				}
				kx += cw;
			}
		}
		float navX = x + 15.6f * u;
		for (int r = 0; r < NAV.size(); r++) {
			float ky = top + u * 0.25f + (r < 2 ? r * u : (r + 1) * u);
			float kx = navX;
			for (Cap cap : NAV.get(r)) {
				if (cap.id() != null) {
					drawKey(context, renderer, cap, kx, ky, u - gap, u - gap, map, q, mouseX, mouseY, hits);
				}
				kx += u;
			}
		}
		// Mouse: a body with two buttons, the wheel, and side buttons.
		float mx = x + 19.2f * u;
		float my = top + u * 0.25f;
		float mw = 2.4f * u;
		float mh = 4.2f * u;
		VeloDraw.fillRounded(context, mx, my, mw, mh, mw / 2.2f, 0x0AFFFFFF);
		VeloDraw.strokeRounded(context, mx, my, mw, mh, mw / 2.2f, 1f, 0x17FFFFFF);
		MenuKit.text(context, renderer, MenuKit.mono("MOUSE"), Math.round(mx), Math.round(my + mh + 4), MenuKit.TEXT_3);
		drawMouseButton(context, renderer, MOUSE[0], mx + 1, my + 1, mw / 2 - 2, mh * 0.42f, map, q, mouseX, mouseY, hits);
		drawMouseButton(context, renderer, MOUSE[1], mx + mw / 2 + 1, my + 1, mw / 2 - 2, mh * 0.42f, map, q, mouseX, mouseY, hits);
		drawMouseButton(context, renderer, MOUSE[2], mx + mw / 2 - u * 0.18f, my + mh * 0.1f, u * 0.36f, mh * 0.24f, map, q, mouseX, mouseY, hits);
		drawMouseButton(context, renderer, MOUSE[3], mx - 1, my + mh * 0.5f, u * 0.62f, u * 0.5f, map, q, mouseX, mouseY, hits);
		drawMouseButton(context, renderer, MOUSE[4], mx - 1, my + mh * 0.5f + u * 0.58f, u * 0.62f, u * 0.5f, map, q, mouseX, mouseY, hits);

		// ---- Not bound yet ----
		int ny = Math.round(top + u * 6.4f) + 6;
		List<KeyBinding> unbound = BindingAccess.all().stream().filter(BindingAccess::isUnbound).toList();
		boolean selectedFree = !map.containsKey(selected) && !SYSTEM.contains(selected);
		MenuKit.text(context, renderer, MenuKit.uiBold("Not bound yet · " + unbound.size()), x, ny, MenuKit.TEXT);
		Text hint = MenuKit.ui(selectedFree ? "Click one to bind it to " + label(selected) : "Select a free key first");
		MenuKit.text(context, renderer, hint, x + MenuKit.width(renderer, MenuKit.uiBold("Not bound yet · " + unbound.size())) + 8, ny, MenuKit.TEXT_3);
		int cx = x;
		int cy = ny + 12;
		for (KeyBinding binding : unbound) {
			Text name = MenuKit.uiBold(BindingAccess.name(binding));
			int cw = MenuKit.width(renderer, name) + 16;
			if (cx + cw > x + mapW) {
				cx = x;
				cy += 17;
			}
			if (cy + 15 > y + h) {
				break;
			}
			boolean hover = VeloUi.inside(mouseX, mouseY, cx, cy, cw, 15);
			VeloDraw.fillRounded(context, cx, cy, cw, 15, 5, hover && selectedFree ? 0x1A3DDC97 : 0x0DFFFFFF);
			VeloDraw.strokeRounded(context, cx, cy, cw, 15, 5, hover && selectedFree ? 0x733DDC97 : 0x17FFFFFF);
			VeloDraw.fillCircle(context, cx + 6, cy + 7.5f, 2f, colorOf(binding));
			MenuKit.text(context, renderer, name, cx + 11, cy + 4, MenuKit.TEXT);
			int bx = cx;
			int by = cy;
			hits.add(new VeloUi.Hit(bx, by, cw, 15, () -> {
				if (map.containsKey(selected) || SYSTEM.contains(selected)) {
					host.status("Select a free key first - the green outlined ones");
					return;
				}
				BindingAccess.bindKeyId(binding, selected);
				host.status("\"" + BindingAccess.name(binding) + "\" = " + label(selected));
			}));
			cx += cw + 4;
		}

		// ---- Details of the selected key ----
		aside(context, renderer, x + w - asideW, y, asideW, h, map, mouseX, mouseY, hits, host);
	}

	private String label(String id) {
		for (List<List<Cap>> group : List.of(ROWS, NAV)) {
			for (List<Cap> row : group) {
				for (Cap cap : row) {
					if (id.equals(cap.id()) && cap.label() != null) {
						return cap.label();
					}
				}
			}
		}
		for (String[] m : MOUSE) {
			if (m[0].equals(id)) {
				return switch (m[1]) {
					case "L" -> "Left click";
					case "R" -> "Right click";
					case "M" -> "Middle click";
					default -> "Mouse " + m[1].substring(1);
				};
			}
		}
		String text = BindingAccess.keyLabel(id);
		if (text.equals("SS")) {
			return "ß";
		}
		// Letters read as capitals like on the keycap - but "ß" mustn't become "SS".
		return text.length() == 1 && Character.isLetter(text.charAt(0)) && Character.toUpperCase(text.charAt(0)) != text.charAt(0)
				&& !text.equals("ß") ? text.toUpperCase(Locale.ROOT) : text;
	}

	private void drawKey(DrawContext context, TextRenderer renderer, Cap cap, float x, float y, float w, float h,
			Map<String, List<KeyBinding>> map, String query, int mouseX, int mouseY, List<VeloUi.Hit> hits) {
		String id = cap.id();
		positions.put(id, new float[] {x + w / 2, y + h / 2});
		List<KeyBinding> bindings = map.get(id);
		boolean system = SYSTEM.contains(id);
		boolean conflict = conflict(bindings);
		boolean hover = VeloUi.inside(mouseX, mouseY, Math.round(x), Math.round(y), Math.round(w), Math.round(h));
		boolean matches = query.isEmpty() || bindings != null && bindings.stream().anyMatch(b -> BindingAccess.name(b).toLowerCase(Locale.ROOT).contains(query))
				|| label(id).toLowerCase(Locale.ROOT).equals(query);
		float radius = Math.max(2f, h * 0.18f);
		int fill;
		int text;
		if (system) {
			fill = 0x14FFFFFF;
			text = 0xFF6E6E78;
		} else if (bindings != null) {
			fill = (colorOf(bindings.get(0)) & 0xFFFFFF) | 0x38000000;
			text = MenuKit.TEXT;
		} else {
			fill = showFree ? 0x0D3DDC97 : 0x0BFFFFFF;
			text = showFree ? 0xFFA6F0CF : 0xFF8A8A95;
		}
		if (!matches) {
			fill = 0x08FFFFFF;
			text = 0xFF4A4A52;
		}
		if (hover) {
			fill = VeloAnim.lerpArgb(fill, 0x40FFFFFF, 0.35f);
		}
		VeloDraw.fillRounded(context, x, y, w, h, radius, fill);
		if (conflict) {
			VeloDraw.strokeRounded(context, x, y, w, h, radius, 1.2f, CONFLICT);
		} else if (bindings == null && !system && showFree && matches) {
			dashed(context, x, y, w, h, 0xB33DDC97);
		}
		if (id.equals(selected)) {
			VeloDraw.strokeRounded(context, x - 1.5f, y - 1.5f, w + 3, h + 3, radius + 1.5f, 1.2f, 0xFFF4F4F6);
		}
		// The key's own name, scaled down to fit if it must - never cut short.
		Text cap1 = MenuKit.uiBold(cap.label() != null ? cap.label() : label(id));
		int tw = MenuKit.width(renderer, cap1);
		boolean showTag = bindings != null && w >= 34 && h >= 22;
		float scale = Math.min(1f, (w - 3) / Math.max(1f, tw));
		float ty = y + (showTag ? h * 0.18f : (h - MenuKit.H_UI * scale) / 2f);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x + (w - tw * scale) / 2f, ty);
		context.getMatrices().scale(scale, scale);
		MenuKit.text(context, renderer, cap1, 0, 0, text);
		context.getMatrices().popMatrix();
		if (showTag) {
			// The action's name, only when it fits whole - never cut off.
			String tag = conflict ? bindings.size() + " actions" : BindingAccess.name(bindings.get(0));
			Text tagText = MenuKit.ui(tag);
			if (MenuKit.width(renderer, tagText) <= w - 3) {
				MenuKit.text(context, renderer, tagText, Math.round(x + (w - MenuKit.width(renderer, tagText)) / 2), Math.round(y + h * 0.58f),
						conflict ? 0xFFFF9EA1 : 0xFFC9C9D1);
			}
		}
		hits.add(new VeloUi.Hit(Math.round(x), Math.round(y), Math.round(w), Math.round(h), () -> selected = id));
	}

	private void drawMouseButton(DrawContext context, TextRenderer renderer, String[] button, float x, float y, float w, float h,
			Map<String, List<KeyBinding>> map, String query, int mouseX, int mouseY, List<VeloUi.Hit> hits) {
		drawKey(context, renderer, new Cap(button[0], button[1], 1f), x, y, w, h, map, query, mouseX, mouseY, hits);
	}

	/** A dashed rounded-ish outline (free keys). */
	private static void dashed(DrawContext context, float x, float y, float w, float h, int color) {
		int x0 = Math.round(x);
		int y0 = Math.round(y);
		int x1 = Math.round(x + w);
		int y1 = Math.round(y + h);
		for (int i = x0 + 2; i < x1 - 2; i += 4) {
			context.fill(i, y0, Math.min(i + 2, x1 - 2), y0 + 1, color);
			context.fill(i, y1 - 1, Math.min(i + 2, x1 - 2), y1, color);
		}
		for (int j = y0 + 2; j < y1 - 2; j += 4) {
			context.fill(x0, j, x0 + 1, Math.min(j + 2, y1 - 2), color);
			context.fill(x1 - 1, j, x1, Math.min(j + 2, y1 - 2), color);
		}
	}

	private void aside(DrawContext context, TextRenderer renderer, int x, int y, int w, int h, Map<String, List<KeyBinding>> map,
			int mouseX, int mouseY, List<VeloUi.Hit> hits, Host host) {
		MenuKit.panel(context, x, y, w, h, 9);
		List<KeyBinding> bindings = map.get(selected);
		boolean system = SYSTEM.contains(selected);
		boolean free = bindings == null && !system;
		boolean conflict = conflict(bindings);
		String name = label(selected);
		Text big = MenuKit.displaySm(name);
		int capW = Math.max(28, MenuKit.width(renderer, big) + 14);
		VeloDraw.fillRounded(context, x + 10, y + 10, capW, 28, 7, free ? 0x143DDC97 : 0x14FFFFFF);
		VeloDraw.strokeRounded(context, x + 10, y + 10, capW, 28, 7, free ? FREE : conflict ? CONFLICT : 0x33FFFFFF);
		MenuKit.text(context, renderer, big, x + 10 + (capW - MenuKit.width(renderer, big)) / 2, y + 10 + (28 - MenuKit.H_DISPLAY_SM) / 2, MenuKit.TEXT);
		String state = free ? "Free · safe to bind" : system ? "Reserved by your system" : conflict ? bindings.size() + " actions fight over this key"
				: bindings.size() == 1 ? "1 action" : bindings.size() + " actions (Minecraft's own defaults)";
		int stateColor = free ? FREE : conflict ? 0xFFFF7A7E : MenuKit.TEXT_2;
		List<String> stateLines = VeloUi.wrap(state, w - 20 - capW - 8);
		int sy = y + 13;
		for (String line : stateLines) {
			MenuKit.text(context, renderer, MenuKit.ui(line), x + 18 + capW, sy, stateColor);
			sy += 10;
		}
		int ry = y + 46;
		if (bindings != null) {
			for (KeyBinding binding : bindings) {
				int rowH = 42;
				if (ry + rowH > y + h - 30) {
					break;
				}
				VeloDraw.fillRounded(context, x + 8, ry, w - 16, rowH, 6, 0x0AFFFFFF);
				VeloDraw.strokeRounded(context, x + 8, ry, w - 16, rowH, 6, conflict ? 0x59FF5A5F : 0x0FFFFFFF);
				VeloDraw.fillRounded(context, x + 14, ry + 7, 3, 9, 1, colorOf(binding));
				MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(BindingAccess.name(binding)), w - 36), x + 21, ry + 6, MenuKit.TEXT);
				MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.ui(sourceOf(binding)), w - 36), x + 21, ry + 16, MenuKit.TEXT_3);
				int bx = x + 21;
				for (String action : new String[] {"Change", "Unbind"}) {
					Text label = MenuKit.uiBold(action);
					int bw = MenuKit.width(renderer, label) + 10;
					int buttonX = bx;
					int buttonY = ry + rowH - 14;
					boolean hover = VeloUi.inside(mouseX, mouseY, buttonX, buttonY, bw, 11);
					if (bx + bw > x + w - 10) {
						break;
					}
					MenuKit.text(context, renderer, label, buttonX + 5, buttonY + 2, hover ? 0xFFFFFFFF : MenuKit.accentText());
					hits.add(new VeloUi.Hit(buttonX, buttonY, bw, 11, action.equals("Change") ? () -> host.listen(binding) : () -> {
						BindingAccess.unbind(binding);
						host.status("\"" + BindingAccess.name(binding) + "\" is now unbound");
					}));
					bx += bw + 2;
				}
				ry += rowH + 4;
			}
		}
		if (conflict) {
			// Move one side (Velo's or another mod's first, Minecraft's last) to a free key close by.
			KeyBinding mover = bindings.stream().filter(b -> !BindingAccess.categoryId(b).startsWith("minecraft:")).findFirst().orElse(bindings.get(bindings.size() - 1));
			List<String> near = nearestFree(map, 3);
			if (!near.isEmpty() && ry + 40 < y + h) {
				MenuKit.text(context, renderer, MenuKit.mono("FREE KEYS NEARBY"), x + 10, ry + 2, FREE);
				int bx = x + 10;
				for (String id : near) {
					Text label = MenuKit.uiBold(label(id));
					int bw = Math.max(20, MenuKit.width(renderer, label) + 12);
					boolean hover = VeloUi.inside(mouseX, mouseY, bx, ry + 12, bw, 16);
					VeloDraw.fillRounded(context, bx, ry + 12, bw, 16, 5, hover ? 0x263DDC97 : 0x143DDC97);
					dashed(context, bx, ry + 12, bw, 16, 0xB33DDC97);
					MenuKit.text(context, renderer, label, bx + (bw - MenuKit.width(renderer, label)) / 2, ry + 16, 0xFFA6F0CF);
					hits.add(new VeloUi.Hit(bx, ry + 12, bw, 16, () -> {
						BindingAccess.bindKeyId(mover, id);
						selected = id;
						host.status("Moved \"" + BindingAccess.name(mover) + "\" to " + label(id));
					}));
					bx += bw + 4;
				}
				List<String> lines = VeloUi.wrap("Moves " + BindingAccess.name(mover) + " there and clears the conflict.", w - 20);
				int ly = ry + 32;
				for (String line : lines) {
					MenuKit.text(context, renderer, MenuKit.ui(line), x + 10, ly, MenuKit.TEXT_3);
					ly += 10;
				}
			}
		} else if (free) {
			int ly = ry;
			for (String line : VeloUi.wrap("Nothing uses this key, not even other mods. Pick an action from Not bound yet to bind it here.", w - 20)) {
				MenuKit.text(context, renderer, MenuKit.ui(line), x + 10, ly, MenuKit.TEXT_2);
				ly += 10;
			}
		} else if (system) {
			int ly = ry;
			for (String line : VeloUi.wrap("Your operating system catches this key before Minecraft sees it.", w - 20)) {
				MenuKit.text(context, renderer, MenuKit.ui(line), x + 10, ly, MenuKit.TEXT_2);
				ly += 10;
			}
		}
		int bound = 0;
		int freeCount = 0;
		for (String id : positions.keySet()) {
			if (map.containsKey(id)) {
				bound++;
			} else if (!SYSTEM.contains(id)) {
				freeCount++;
			}
		}
		MenuKit.hairline(context, x + 10, y + h - 26, w - 20);
		MenuKit.text(context, renderer, MenuKit.ui("Bound"), x + 10, y + h - 20, MenuKit.TEXT_3);
		Text b = MenuKit.mono(String.valueOf(bound));
		MenuKit.text(context, renderer, b, x + w - 10 - MenuKit.width(renderer, b), y + h - 20, MenuKit.TEXT);
		MenuKit.text(context, renderer, MenuKit.ui("Free"), x + 10, y + h - 10, MenuKit.TEXT_3);
		Text f = MenuKit.mono(String.valueOf(freeCount));
		MenuKit.text(context, renderer, f, x + w - 10 - MenuKit.width(renderer, f), y + h - 10, FREE);
	}

	private List<String> nearestFree(Map<String, List<KeyBinding>> map, int count) {
		float[] at = positions.get(selected);
		if (at == null) {
			return List.of();
		}
		List<String> candidates = new ArrayList<>();
		for (Map.Entry<String, float[]> entry : positions.entrySet()) {
			String id = entry.getKey();
			if (!map.containsKey(id) && !SYSTEM.contains(id) && id.startsWith("key.keyboard.") && !id.contains("escape")) {
				candidates.add(id);
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble(id -> {
			float[] p = positions.get(id);
			return Math.hypot(p[0] - at[0], p[1] - at[1]);
		}));
		return candidates.subList(0, Math.min(count, candidates.size()));
	}

	void select(String keyId) {
		selected = keyId;
	}
}
