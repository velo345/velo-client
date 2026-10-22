package net.veloclient.velo.client.gui.title;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.gui.BugReportScreen;
import net.veloclient.velo.client.gui.FriendsScreen;
import net.veloclient.velo.client.gui.ModMenuScreen;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.store.StoreScreen;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.module.Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The 0.9.7 pause menu over the blurred game: on the left "Paused", a big "Back to game", one glass panel with
 * every menu entry (Velo menu, Advancements, Statistics, Store, Options, Friends, Report a bug, then whatever the
 * server adds - custom options, server links, quick actions, LAN - and buttons other mods put into the menu), and
 * Disconnect at the bottom; on the right the server you're on (ping, session, FPS, players), your quick toggles,
 * your party and "Save the last seconds as a clip".
 *
 * <p>Other mods: as on the title screen, real buttons stay real widgets, only moved and painted. Buttons a mod
 * puts into vanilla's centre column become rows in the panel; icon-like ones (feedback, bugs, reporting...) a small
 * icon row; anything a mod places elsewhere is left exactly where it is. With Essential installed (its own panel and
 * character take the right side) the cards move next to the menu instead.
 */
public final class PauseMenu {

	public interface Env {
		List<ClickableWidget> buttons();

		/** The vanilla key of a pause-menu button (incl. the LAN fallback), or null. */
		String keyOf(ClickableWidget widget);

		void undraw(ClickableWidget widget);

		ClickableWidget ukulib();
	}

	private static final List<String> MAIN = List.of("gui.advancements", "gui.stats", "menu.options");
	private static final List<String> SERVER = List.of("menu.custom_options", "menu.server_links", "menu.quick_actions", "menu.shareToLan");
	private static final Map<String, String> ICONS = Map.of(
			"gui.advancements", "trophy", "gui.stats", "stats", "menu.options", "sliders",
			"menu.custom_options", "flag", "menu.server_links", "link", "menu.quick_actions", "bolt", "menu.shareToLan", "globe");
	private static final Map<String, String> KEYS = Map.of("gui.advancements", "L");

	private static final Map<ClickableWidget, int[]> FIRST_SEEN = new WeakHashMap<>();
	private static final Set<ClickableWidget> RESTYLED = Collections.newSetFromMap(new WeakHashMap<>());

	private static HotspotButton veloMenu;
	private static HotspotButton store;
	private static HotspotButton friends;
	private static HotspotButton bug;
	private static HotspotButton tabFavorites;
	private static HotspotButton tabRecent;
	private static HotspotButton allModules;
	private static HotspotButton clip;
	private static final List<HotspotButton> toggles = new ArrayList<>();
	/**
	 * With Essential: stand-ins for vanilla's pause buttons (and ukulib's). Essential hangs its character left of
	 * "Back to Game" and its panel right of the button column, so the real buttons stay - invisible - in a narrow column
	 * in the free space right of our cards, and these take the clicks in our menu.
	 */
	private static final Map<String, HotspotButton> PROXIES = new java.util.HashMap<>();
	private static final String UKULIB = "$ukulib";
	private static List<Module> shownToggles = List.of();
	private static boolean showRecent;
	private static long clipSavedAt;
	private static String clipMessage;

	private record Row(ClickableWidget widget, String label, String icon, String key) {
	}

	private PauseMenu() {
	}

	private static long openedAt;

	public static List<ClickableWidget> createHotspots(Screen screen) {
		openedAt = System.nanoTime();
		MinecraftClient client = MinecraftClient.getInstance();
		veloMenu = new HotspotButton(Text.literal("Velo menu"), () -> client.setScreen(new ModMenuScreen()));
		store = new HotspotButton(Text.literal("Store"), () -> client.setScreen(new StoreScreen(screen)));
		friends = new HotspotButton(Text.literal("Friends"), () -> client.setScreen(new FriendsScreen(screen)));
		bug = new HotspotButton(Text.literal("Report a bug"), () -> client.setScreen(new BugReportScreen(screen)));
		tabFavorites = new HotspotButton(Text.literal("Favorites"), () -> showRecent = false);
		tabRecent = new HotspotButton(Text.literal("Recent"), () -> showRecent = true);
		allModules = new HotspotButton(Text.literal("All modules"), () -> client.setScreen(new ModMenuScreen()));
		clip = new HotspotButton(Text.literal("Save clip"), PauseMenu::saveClip);
		toggles.clear();
		for (int i = 0; i < 6; i++) {
			int index = i;
			toggles.add(new HotspotButton(Text.literal("Toggle"), () -> {
				if (index < shownToggles.size()) {
					QuickToggles.toggle(shownToggles.get(index));
				}
			}));
		}
		List<ClickableWidget> all = new ArrayList<>(List.of(veloMenu, store, friends, bug, tabFavorites, tabRecent, allModules, clip));
		all.addAll(toggles);
		PROXIES.clear();
		List<String> proxied = new ArrayList<>(List.of("menu.returnToGame", "menu.disconnect", "menu.returnToMenu", UKULIB));
		proxied.addAll(MAIN);
		proxied.addAll(SERVER);
		for (String key : proxied) {
			HotspotButton proxy = new HotspotButton(Text.literal(key), null);
			PROXIES.put(key, proxy);
			all.add(proxy);
		}
		return all;
	}

	public static void adopt(ClickableWidget widget) {
		RESTYLED.add(widget);
		widget.visible = false;
	}

	private static boolean menuWasShown;

	/**
	 * Start of the frame: hides what we paint and lays the dim down first - under vanilla and under other mods'
	 * menus (Essential's panels draw during the screen render, so a dim painted afterwards greyed them out).
	 */
	public static void beforeVanilla(DrawContext context, int width, int height) {
		for (ClickableWidget widget : RESTYLED) {
			widget.visible = false;
		}
		if (!menuWasShown) {
			return;
		}
		float open = Math.min(1f, (System.nanoTime() - openedAt) / 320_000_000f);
		float eased = 1f - (float) Math.pow(1f - open, 3);
		int dimA = Math.round(0xD0 * eased);
		int dimB = Math.round(0x59 * eased);
		MenuKit.hGradient(context, 0, 0, Math.round(width * 0.55f), height, (dimA << 24) | 0x08080B, (dimB << 24) | 0x08080B);
		context.fill(Math.round(width * 0.55f), 0, width, height, (dimB << 24) | 0x08080B);
	}

	private static boolean hovered(int mouseX, int mouseY, ClickableWidget w) {
		return w.visible && MenuKit.inside(mouseX, mouseY, w.getX(), w.getY(), w.getWidth(), w.getHeight());
	}

	private static void place(ClickableWidget widget, int x, int y, int w, int h) {
		if (widget instanceof HotspotButton hotspot) {
			hotspot.place(x, y, w, h, true);
			if (hotspot.forward() != null) {
				hotspot.active = hotspot.forward().active;
			}
		} else {
			widget.setX(x);
			widget.setY(y);
			widget.setWidth(w);
			widget.setHeight(h);
			RESTYLED.add(widget);
		}
	}

	public static void render(DrawContext context, int width, int height, int mouseX, int mouseY, Env env) {
		TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
		for (ClickableWidget widget : RESTYLED) {
			widget.visible = true;
		}
		boolean menuShown = false;
		for (ClickableWidget widget : env.buttons()) {
			if ("menu.returnToGame".equals(env.keyOf(widget))) {
				menuShown = true;
			}
		}
		menuWasShown = menuShown;
		if (!menuShown) {
			// F3+Esc pauses without a menu: nothing of ours either.
			hideCards();
			for (HotspotButton hotspot : List.of(veloMenu, store, friends, bug)) {
				hotspot.place(0, 0, 0, 0, false);
			}
			return;
		}
		// Opening: the dim fades in, the menu slides in from the left and the cards from the right.
		float open = Math.min(1f, (System.nanoTime() - openedAt) / 320_000_000f);
		float eased = 1f - (float) Math.pow(1f - open, 3);

		boolean compact = height < 300;
		int margin = clamp(Math.round(width * 0.05f), 12, 48);
		int top = clamp(Math.round(height * 0.07f), 8, 48);
		int colW = clamp(Math.round(width * 0.27f), 150, 200);

		// ---- Sort the buttons ----
		java.util.Map<String, ClickableWidget> byKey = new java.util.HashMap<>();
		List<Row> modRows = new ArrayList<>();
		Row modsRow = null;
		List<ClickableWidget> iconWidgets = new ArrayList<>();
		List<String> iconKeys = new ArrayList<>();
		ClickableWidget ukulib = env.ukulib();
		for (ClickableWidget widget : env.buttons()) {
			if (widget instanceof HotspotButton) {
				continue;
			}
			int[] first = FIRST_SEEN.computeIfAbsent(widget, w -> new int[] {w.getX(), w.getY(), w.getWidth(), w.getHeight()});
			String key = env.keyOf(widget);
			if (key != null) {
				byKey.put(key, widget);
				continue;
			}
			if (widget == ukulib) {
				// ukulib paints a player face over its button every frame: keep the real one off-screen, click it via ours.
				RESTYLED.remove(widget);
				widget.setX(-10000);
				widget.setY(-10000);
				HotspotButton proxy = PROXIES.get(UKULIB);
				if (proxy != null) {
					iconWidgets.add(proxy.forwardTo(widget));
					iconKeys.add("extensions");
				}
				continue;
			}
			String label = widget.getMessage().getString().strip();
			String className = widget.getClass().getName();
			// Essential builds its own pause panel around its widgets - never move or restyle them.
			if (className.startsWith("gg.essential.") || label.startsWith("<") && label.endsWith(">")) {
				continue;
			}
			// Mod Menu gets a labelled row in the panel - as a small icon nobody recognised it.
			if (className.startsWith("com.terraformersmc.modmenu")) {
				modsRow = new Row(widget, "Mods", "mods", null);
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.ESCAPE_EXTRA_ICONS, label);
			String recording = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.RECORDING_SIDE_ICONS, label);
			// Vanilla's own extras (Report Bugs, Feedback...) always belong to the menu, wherever vanilla put them.
			boolean vanilla = widget.getClass().getName().startsWith("net.minecraft.");
			boolean meantForMenu = vanilla || Math.abs(first[0] + first[2] / 2 - width / 2) <= 110 || first[0] < margin + colW + 10;
			if (vanilla && iconKey == null && recording == null) {
				iconKey = "bug";
				if (!label.toLowerCase(java.util.Locale.ROOT).contains("bug")) {
					iconKey = "extensions";
				}
			}
			if (recording != null) {
				modRows.add(new Row(widget, label, recording, null));
			} else if (iconKey != null && (meantForMenu || first[2] <= 24)) {
				iconWidgets.add(widget);
				iconKeys.add(iconKey);
			} else if (meantForMenu && !label.isEmpty() && first[2] >= 40) {
				modRows.add(new Row(widget, label, "extensions", null));
			} else if (label.isEmpty() || first[2] <= 24 && first[3] <= 24) {
				// Small icon buttons from any mod join the icon row in the panel.
				iconWidgets.add(widget);
				iconKeys.add("extensions");
			}
			// Anything else belongs to its mod's own layout: untouched.
		}

		boolean essentialMode = FabricLoader.getInstance().isModLoaded("essential");
		Map<String, ClickableWidget> anchors = new java.util.HashMap<>();
		if (essentialMode) {
			for (Map.Entry<String, ClickableWidget> entry : new ArrayList<>(byKey.entrySet())) {
				HotspotButton proxy = PROXIES.get(entry.getKey());
				if (proxy != null) {
					RESTYLED.remove(entry.getValue());
					anchors.put(entry.getKey(), entry.getValue());
					byKey.put(entry.getKey(), proxy.forwardTo(entry.getValue()));
				}
			}
		}

		// ---- Left column ----
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(-30f * (1f - eased), 0f);
		int y = top;
		// The logo centred on the word's capitals (Sora at this size draws its caps 2..16 below y).
		MenuKit.logo(context, margin, y, 18);
		MenuKit.text(context, renderer, MenuKit.displayLg("Paused"), margin + 24, y, MenuKit.TEXT);
		y += 24;

		ClickableWidget back = byKey.get("menu.returnToGame");
		int backH = compact ? 20 : 24;
		if (back != null) {
			place(back, margin, y, colW, backH);
			float hover = MenuKit.hover(back, hovered(mouseX, mouseY, back));
			int accent = MenuKit.accent();
			VeloDraw.shadow(context, margin, y, colW, backH, 7, 10, 3, (accent & 0xFFFFFF) | 0x50000000);
			VeloDraw.fillRounded(context, margin, y, colW, backH, 7, VeloAnim.lerpArgb(accent, VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.12f), hover));
			MenuKit.icon(context, "play", margin + 9, y + (backH - 10) / 2, 10, MenuKit.onAccent());
			MenuKit.text(context, renderer, MenuKit.uiBold(label(back)), margin + 25, y + (backH - MenuKit.H_UI) / 2, MenuKit.onAccent());
			MenuKit.keyHint(context, renderer, "ESC", margin + colW - 9, y + (backH - MenuKit.H_MONO) / 2, (MenuKit.onAccent() & 0xFFFFFF) | 0xB0000000);
			y += backH + 8;
		}

		List<Row> rows = new ArrayList<>();
		rows.add(new Row(veloMenu, "Velo menu", "grid", MenuInfo.menuKey()));
		for (String key : MAIN) {
			if (byKey.containsKey(key)) {
				rows.add(new Row(byKey.get(key), label(byKey.get(key)), ICONS.get(key), KEYS.get(key)));
			}
			if (key.equals("gui.stats")) {
				rows.add(new Row(store, "Store", "store", null));
			}
			if (key.equals("menu.options") && modsRow != null) {
				rows.add(modsRow);
			}
		}
		rows.add(new Row(friends, "Friends", "friends", null));
		rows.add(new Row(bug, "Report a bug", "bug", null));
		List<Row> serverRows = new ArrayList<>();
		for (String key : SERVER) {
			if (byKey.containsKey(key)) {
				serverRows.add(new Row(byKey.get(key), label(byKey.get(key)), ICONS.get(key), null));
			}
		}

		ClickableWidget leave = byKey.containsKey("menu.disconnect") ? byKey.get("menu.disconnect") : byKey.get("menu.returnToMenu");
		int leaveH = compact ? 16 : 20;
		int rowH = compact ? 14 : 16;
		int iconRowH = iconWidgets.isEmpty() ? 0 : 22;
		int panelBottomLimit = height - top - (leave != null ? leaveH + 8 : 0);
		int needed = 8 + (rows.size() + serverRows.size() + modRows.size()) * rowH + (serverRows.isEmpty() ? 0 : 7) + (modRows.isEmpty() ? 0 : 7) + iconRowH;
		if (y + needed > panelBottomLimit) {
			rowH = Math.max(11, (panelBottomLimit - y - 8 - iconRowH - 14) / Math.max(1, rows.size() + serverRows.size() + modRows.size()));
			needed = 8 + (rows.size() + serverRows.size() + modRows.size()) * rowH + (serverRows.isEmpty() ? 0 : 7) + (modRows.isEmpty() ? 0 : 7) + iconRowH;
		}
		MenuKit.panel(context, margin, y, colW, needed, 9);
		int ry = y + 4;
		for (Row row : rows) {
			drawNavRow(context, renderer, row, margin + 4, ry, colW - 8, rowH, mouseX, mouseY);
			ry += rowH;
		}
		for (List<Row> group : List.of(serverRows, modRows)) {
			if (group.isEmpty()) {
				continue;
			}
			MenuKit.hairline(context, margin + 10, ry + 3, colW - 20);
			ry += 7;
			for (Row row : group) {
				drawNavRow(context, renderer, row, margin + 4, ry, colW - 8, rowH, mouseX, mouseY);
				ry += rowH;
			}
		}
		if (!iconWidgets.isEmpty()) {
			int ix = margin + 8;
			for (int i = 0; i < iconWidgets.size(); i++) {
				ClickableWidget widget = iconWidgets.get(i);
				if (widget instanceof HotspotButton hotspot) {
					hotspot.place(ix, ry + 3, 16, 16, true);
				} else {
					env.undraw(widget);
					widget.setX(ix);
					widget.setY(ry + 3);
					widget.setWidth(16);
					widget.setHeight(16);
				}
				float hover = MenuKit.hover(widget, hovered(mouseX, mouseY, widget));
				VeloDraw.fillRounded(context, ix, ry + 3, 16, 16, 4, VeloAnim.lerpArgb(0x0FFFFFFF, 0x26FFFFFF, hover));
				MenuKit.icon(context, iconKeys.get(i), ix + 3, ry + 6, 10, widget.active ? 0xFFC9C9D1 : 0xFF66666E);
				ix += 20;
			}
		}

		if (leave != null) {
			int ly = height - top - leaveH;
			place(leave, margin, ly, colW, leaveH);
			float hover = MenuKit.hover(leave, hovered(mouseX, mouseY, leave));
			VeloDraw.fillRounded(context, margin, ly, colW, leaveH, 7, VeloAnim.lerpArgb(0x00000000, 0x24FF5A5F, hover));
			VeloDraw.strokeRounded(context, margin, ly, colW, leaveH, 7, VeloAnim.lerpArgb(0x59FF5A5F, 0xFFFF5A5F, hover));
			Text label = MenuKit.uiBold(label(leave));
			MenuKit.text(context, renderer, label, margin + (colW - MenuKit.width(renderer, label)) / 2, ly + (leaveH - MenuKit.H_UI) / 2, MenuKit.RED_TEXT);
		}
		// Vanilla's other leftovers (the other leave button when both exist) go under Disconnect's spot.
		for (String key : List.of("menu.returnToMenu", "menu.disconnect")) {
			ClickableWidget widget = byKey.get(key);
			if (widget != null && widget != leave) {
				widget.setX(-10000);
			}
		}

		context.getMatrices().popMatrix();

		// ---- Right column: cards ----
		int cardW = clamp(Math.round(width * 0.26f), 160, 210);
		int cardX = essentialMode ? margin + colW + 16 : width - margin - cardW;
		boolean room = cardX > margin + colW + 16 || essentialMode;
		if (essentialMode) {
			// Essential's character goes right next to the menu - it has to stay in the left half of the screen (see
			// EssentialAnchors) - and the cards after it, when they fit before Essential's side menu; else they give way.
			EssentialAnchors.Plan plan = EssentialAnchors.plan(width, margin + colW, false);
			if (plan != null) {
				cardX = EssentialAnchors.playerRight(margin + colW) + 14;
				room = cardX + cardW + 8 <= EssentialAnchors.menuLeft(width, plan.topX() + EssentialAnchors.ANCHOR_W);
			} else {
				room = true;
			}
			anchorForEssential(anchors, plan);
		}
		if (!room) {
			hideCards();
			return;
		}
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(30f * (1f - eased), 0f);
		int cy = top;
		cy = serverCard(context, renderer, cardX, cy, cardW);
		cy += 8;
		cy = togglesCard(context, renderer, cardX, cy, cardW, mouseX, mouseY, height - top);
		JsonObject party = SocialClient.party();
		if (party != null && party.has("members") && party.getAsJsonArray("members").size() > 1 && cy + 60 < height - top - 26) {
			cy += 8;
			cy = partyCard(context, renderer, cardX, cy, cardW, party);
		}
		if (ClipRecorder.active() && cy + 30 < height - top) {
			int ch = 22;
			int clipY = Math.max(cy + 8, height - top - ch);
			clip.place(cardX, clipY, cardW, ch, true);
			float hover = MenuKit.hover(clip, hovered(mouseX, mouseY, clip));
			MenuKit.panel(context, cardX, clipY, cardW, ch, 8);
			if (hover > 0.01f) {
				VeloDraw.strokeRounded(context, cardX, clipY, cardW, ch, 8, VeloAnim.lerpArgb(MenuKit.HAIRLINE, MenuKit.accent(), hover));
			}
			boolean recent = System.currentTimeMillis() - clipSavedAt < 3000 && clipMessage != null;
			String text = recent ? clipMessage : "Save last " + Math.round(Math.min(30, ClipRecorder.bufferedSeconds())) + " s as clip";
			Text label = MenuKit.uiBold(text);
			int lw = MenuKit.width(renderer, label) + 12;
			int lx = cardX + (cardW - lw) / 2;
			VeloDraw.fillCircle(context, lx + 3, clipY + ch / 2f, 3f, recent ? MenuKit.GREEN : 0xFFFF5A5F);
			MenuKit.text(context, renderer, label, lx + 12, clipY + (ch - MenuKit.H_UI) / 2, MenuKit.TEXT);
		} else {
			clip.place(0, 0, 0, 0, false);
		}
		context.getMatrices().popMatrix();
	}

	/** A row's words: the real button's label, also through a stand-in. */
	private static String label(ClickableWidget widget) {
		ClickableWidget real = widget instanceof HotspotButton hotspot && hotspot.forward() != null ? hotspot.forward() : widget;
		return real.getMessage().getString();
	}

	/**
	 * Essential: the real buttons stay as invisible, unclickable anchors (our proxies take the clicks), parked so
	 * Essential draws its character right of our menu and cards and its side menu at the right edge - see
	 * {@link EssentialAnchors}.
	 */
	private static void anchorForEssential(Map<String, ClickableWidget> anchors, EssentialAnchors.Plan plan) {
		for (ClickableWidget widget : anchors.values()) {
			int[] first = FIRST_SEEN.get(widget);
			widget.setX(plan == null ? -10000 : plan.topX());
			widget.setWidth(EssentialAnchors.ANCHOR_W);
			if (first != null) {
				widget.setY(first[1]);
				widget.setHeight(first[3]);
			}
			widget.visible = false;
		}
	}

	private static void hideCards() {
		for (HotspotButton hotspot : List.of(tabFavorites, tabRecent, allModules, clip)) {
			hotspot.place(0, 0, 0, 0, false);
		}
		for (HotspotButton toggle : toggles) {
			toggle.place(0, 0, 0, 0, false);
		}
	}

	private static void drawNavRow(DrawContext context, TextRenderer renderer, Row row, int x, int y, int w, int h, int mouseX, int mouseY) {
		ClickableWidget widget = row.widget();
		place(widget, x, y, w, h);
		float hover = MenuKit.hover(widget, widget.active && hovered(mouseX, mouseY, widget));
		if (hover > 0.01f) {
			VeloDraw.fillRounded(context, x, y, w, h, 5, ((Math.round(0x12 * hover)) << 24) | 0xFFFFFF);
		}
		int slide = Math.round(hover * 3);
		int box = Math.min(12, h - 3);
		VeloDraw.fillRounded(context, x + 4 + slide, y + (h - box) / 2, box, box, 3, 0x10FFFFFF);
		MenuKit.icon(context, row.icon(), x + 4 + slide + (box - 8) / 2, y + (h - 8) / 2, 8, widget.active ? 0xFFC9C9D1 : 0xFF55555C);
		int color = widget.active ? VeloAnim.lerpArgb(0xFFE4E4E8, 0xFFFFFFFF, hover) : 0xFF6E6E78;
		int maxLabel = w - box - 14 - (row.key() == null ? 4 : MenuKit.width(renderer, MenuKit.mono(row.key())) + 10);
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(row.label()), maxLabel), x + 4 + slide + box + 6, y + (h - MenuKit.H_UI) / 2, color);
		MenuKit.keyHint(context, renderer, row.key(), x + w - 5, y + (h - MenuKit.H_MONO) / 2, MenuKit.TEXT_3);
	}

	private static int serverCard(DrawContext context, TextRenderer renderer, int x, int y, int w) {
		int h = 64;
		MenuKit.panel(context, x, y, w, h, 9);
		boolean single = MenuInfo.singleplayer();
		String name = single ? MenuInfo.worldName() : MenuInfo.serverName();
		String address = single ? "Singleplayer" : MenuInfo.serverAddress();
		Identifier icon = single ? null : LastServer.icon();
		if (icon != null) {
			MenuKit.texture(context, icon, x + 8, y + 8, 18, 64);
		} else {
			MenuKit.logo(context, x + 8, y + 8, 18);
		}
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(name == null ? "This world" : name), w - 40), x + 32, y + 8, MenuKit.TEXT);
		int ping = MenuInfo.ping();
		String sub = (address == null ? "" : address) + (single || ping < 0 ? "" : "  ·  " + ping + " ms");
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.mono(sub), w - 40), x + 32, y + 19, 0xFF8A8A95);
		int tileW = (w - 16 - 8) / 3;
		long start = LastServer.sessionStart();
		long minutes = start == 0 ? 0 : (System.currentTimeMillis() - start) / 60_000;
		String session = minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
		int players = MenuInfo.playersOnline();
		String[][] stats = {{"Session", session}, {"FPS", String.valueOf(MenuInfo.fps())}, {single ? "Players" : "Online", players < 0 ? "-" : String.valueOf(players)}};
		for (int i = 0; i < 3; i++) {
			int tx = x + 8 + i * (tileW + 4);
			MenuKit.tile(context, tx, y + 34, tileW, 24, 6);
			MenuKit.text(context, renderer, MenuKit.ui(stats[i][0]), tx + 5, y + 37, 0xFF8A8A95);
			MenuKit.text(context, renderer, MenuKit.displaySm(stats[i][1]), tx + 5, y + 47, MenuKit.TEXT);
		}
		return y + h;
	}

	private static int togglesCard(DrawContext context, TextRenderer renderer, int x, int y, int w, int mouseX, int mouseY, int maxBottom) {
		List<Module> modules = showRecent ? QuickToggles.recent() : QuickToggles.favorites();
		int rowH = 15;
		int fit = Math.max(0, (maxBottom - 30 - y - 50) / rowH);
		int rows = Math.min(Math.min(toggles.size(), modules.size()), fit);
		int h = 8 + 16 + 4 + rows * rowH + 4 + 14 + 4;
		if (rows == 0) {
			hideToggles();
			return y - 8;
		}
		MenuKit.panel(context, x, y, w, h, 9);
		// Tabs: Favorites / Recent
		int tx = x + 8;
		int ty = y + 8;
		Text fav = MenuKit.uiBold("Favorites");
		Text rec = MenuKit.uiBold("Recent");
		int favW = MenuKit.width(renderer, fav) + 14;
		int recW = MenuKit.width(renderer, rec) + 14;
		VeloDraw.fillRounded(context, tx, ty, favW + recW + 4, 16, 5, 0x0DFFFFFF);
		VeloDraw.fillRounded(context, showRecent ? tx + 2 + favW : tx + 2, ty + 2, showRecent ? recW : favW, 12, 4, 0xFF2A2A32);
		MenuKit.text(context, renderer, fav, tx + 2 + 7, ty + 4, showRecent ? 0xFF9D9DA8 : MenuKit.TEXT);
		MenuKit.text(context, renderer, rec, tx + 2 + favW + 7, ty + 4, showRecent ? MenuKit.TEXT : 0xFF9D9DA8);
		tabFavorites.place(tx + 2, ty, favW, 16, true);
		tabRecent.place(tx + 2 + favW, ty, recW, 16, true);

		shownToggles = modules.subList(0, rows);
		int ry = y + 28;
		for (int i = 0; i < toggles.size(); i++) {
			HotspotButton hotspot = toggles.get(i);
			if (i >= shownToggles.size()) {
				hotspot.place(0, 0, 0, 0, false);
				continue;
			}
			Module module = shownToggles.get(i);
			hotspot.place(x + 4, ry, w - 8, rowH, true);
			float hover = MenuKit.hover(hotspot, hovered(mouseX, mouseY, hotspot));
			if (hover > 0.01f) {
				VeloDraw.fillRounded(context, x + 4, ry, w - 8, rowH, 5, ((Math.round(0x12 * hover)) << 24) | 0xFFFFFF);
			}
			MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(module.displayName()), w - 44), x + 10, ry + (rowH - MenuKit.H_UI) / 2, MenuKit.TEXT);
			MenuKit.toggle(context, x + w - 28, ry + (rowH - 9) / 2, MenuKit.hover(module, module.isEnabled()));
			ry += rowH;
		}
		MenuKit.hairline(context, x + 8, ry + 3, w - 16);
		Text all = MenuKit.uiBold("All modules  ›");
		int allW = MenuKit.width(renderer, all);
		allModules.place(x + 8, ry + 6, allW, 12, true);
		MenuKit.text(context, renderer, all, x + 10, ry + 8,
				VeloAnim.lerpArgb(MenuKit.accentText(), 0xFFFFFFFF, MenuKit.hover(allModules, hovered(mouseX, mouseY, allModules))));
		return y + h;
	}

	private static void hideToggles() {
		for (HotspotButton hotspot : List.of(tabFavorites, tabRecent, allModules)) {
			hotspot.place(0, 0, 0, 0, false);
		}
		for (HotspotButton toggle : toggles) {
			toggle.place(0, 0, 0, 0, false);
		}
	}

	private static int partyCard(DrawContext context, TextRenderer renderer, int x, int y, int w, JsonObject party) {
		var members = party.getAsJsonArray("members");
		int shown = Math.min(4, members.size());
		int h = 22 + shown * 18 + 4;
		MenuKit.panel(context, x, y, w, h, 9);
		MenuKit.text(context, renderer, MenuKit.uiBold("Party · " + members.size()), x + 8, y + 8, MenuKit.TEXT);
		String me = SocialClient.myUuidPublic();
		int my = y + 22;
		int n = 0;
		for (JsonElement element : members) {
			if (n++ >= shown) {
				break;
			}
			JsonObject member = element.getAsJsonObject();
			String uuid = member.has("uuid") ? member.get("uuid").getAsString() : "";
			String name = member.has("name") ? member.get("name").getAsString() : "?";
			boolean online = member.has("online") && member.get("online").getAsBoolean();
			PlayerHeads.draw(context, uuid, name, x + 8, my + 1, 14);
			MenuKit.text(context, renderer, MenuKit.uiBold(uuid.equals(me) ? "You" : name), x + 28, my + 4, MenuKit.TEXT);
			Text status = MenuKit.ui(online ? "online" : "offline");
			MenuKit.text(context, renderer, status, x + w - 8 - MenuKit.width(renderer, status), my + 4, online ? MenuKit.GREEN : MenuKit.TEXT_3);
			my += 18;
		}
		return y + h;
	}

	private static void saveClip() {
		ClipRecorder.save("manual", null, 30, path -> {
			clipSavedAt = System.currentTimeMillis();
			clipMessage = path == null ? "Nothing to save yet" : "Clip saved";
		});
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
