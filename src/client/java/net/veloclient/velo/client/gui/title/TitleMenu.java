package net.veloclient.velo.client.gui.title;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.FriendsScreen;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.store.StoreScreen;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.serverpacks.ServerData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * The 0.9.7 title screen (left column over the panorama): Velo brand, a "Jump back in" card for your last server,
 * the menu as large Sora words that slide on hover, your profile and the small icon buttons at the bottom, and a
 * Store card bottom-right.
 *
 * <p>Other mods: every real button stays a real widget in the screen (mods anchor to vanilla's, see
 * TitleScreenMixin) - Velo only moves and paints them. Buttons another mod puts into vanilla's centre column
 * (Mod Menu's "Mods", Flashback's "Open Replays"...) become extra rows under the menu, icon-only ones join the
 * icon row; anything a mod places elsewhere (Essential's character and panels on the right) is left exactly where
 * and how it is - which is why the right side stays clear, and the Store card hides when Essential is installed.
 */
public final class TitleMenu {

	/** What the screen mixin provides (the parts that are named differently per Minecraft version). */
	public interface Env {
		/** Every button-type child of the screen, in child order. */
		List<ClickableWidget> buttons();

		/** The vanilla translation key a button's label comes from, or null. */
		String keyOf(ClickableWidget widget);

		/** Stops vanilla drawing {@code widget} itself (it stays clickable). */
		void undraw(ClickableWidget widget);

		/** ukulib's icon-only button, or null. */
		ClickableWidget ukulib();
	}

	private static final List<String> MAIN_KEYS = List.of("menu.singleplayer", "menu.multiplayer", "menu.online", "$store",
			"menu.options", "menu.quit");

	/** Where each widget was the first time we saw it - decides whether a mod meant it for the menu column. */
	private static final Map<ClickableWidget, int[]> FIRST_SEEN = new WeakHashMap<>();
	/** Widgets painted by us this screen: hidden while vanilla draws, visible (clickable) otherwise. */
	private static final Set<ClickableWidget> RESTYLED = Collections.newSetFromMap(new WeakHashMap<>());

	private static HotspotButton storeRow;
	private static HotspotButton jumpCard;
	private static HotspotButton storeCard;
	private static HotspotButton friends;
	/**
	 * With Essential: stand-ins for the vanilla menu buttons (and ukulib's), because those real buttons stay put as
	 * invisible anchors - Essential lays out its character, wardrobe and side menu around vanilla's buttons, so they
	 * mark where that cluster should go (right of the menu) instead of being dragged into our column.
	 */
	private static final Map<String, HotspotButton> PROXIES = new java.util.HashMap<>();
	private static final String UKULIB = "$ukulib";

	private record Row(ClickableWidget widget, Text label, String badge, boolean main, boolean primary, boolean quit) {
	}

	private record Icon(ClickableWidget widget, String key) {
	}

	private TitleMenu() {
	}

	/** Velo's own clickable entries, created with the screen (the mixin adds them as children). */
	public static List<ClickableWidget> createHotspots(Screen screen) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (animatedFor != screen) {
			animatedFor = screen;
			openedAt = System.nanoTime();
		}
		storeRow = new HotspotButton(Text.literal("Store"), () -> client.setScreen(new StoreScreen(screen)));
		jumpCard = new HotspotButton(Text.literal("Jump back in"), LastServer::join);
		storeCard = new HotspotButton(Text.literal("Store"), () -> client.setScreen(new StoreScreen(screen)));
		friends = new HotspotButton(Text.literal("Friends"), () -> client.setScreen(new FriendsScreen(screen)));
		List<ClickableWidget> out = new ArrayList<>(List.of(storeRow, jumpCard, storeCard, friends));
		PROXIES.clear();
		for (String key : MAIN_KEYS) {
			if (!key.startsWith("$")) {
				HotspotButton proxy = new HotspotButton(Text.literal(key), null);
				PROXIES.put(key, proxy);
				out.add(proxy);
			}
		}
		HotspotButton uku = new HotspotButton(Text.literal("Mods"), null);
		PROXIES.put(UKULIB, uku);
		out.add(uku);
		return out;
	}

	/** Registers a vanilla button the menu paints, before the first frame (so it never flashes in vanilla's look). */
	public static void adopt(ClickableWidget widget) {
		RESTYLED.add(widget);
		widget.visible = false;
	}

	/** Call at the start of the screen's render: vanilla mustn't paint what we paint. */
	public static void beforeVanilla() {
		for (ClickableWidget widget : RESTYLED) {
			widget.visible = false;
		}
	}

	private static boolean hovered(int mouseX, int mouseY, ClickableWidget w) {
		return w.visible && MenuKit.inside(mouseX, mouseY, w.getX(), w.getY(), w.getWidth(), w.getHeight());
	}

	/** Lays out and paints the whole menu; call after vanilla rendered. */
	public static void render(DrawContext context, int width, int height, int mouseX, int mouseY, Env env) {
		TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
		for (ClickableWidget widget : RESTYLED) {
			widget.visible = true;
		}
		// Three sizes: compact for tiny GUI heights, normal, and large (big screens / low GUI scale) with bigger words.
		boolean compact = height < 260;
		boolean large = height >= 320;
		int margin = clamp(Math.round(width * 0.065f), 14, 72);
		int top = clamp(Math.round(height * 0.1f), 10, 80);
		float open = openProgress();

		// ---- Sort the buttons ----
		java.util.Map<String, ClickableWidget> byKey = new java.util.HashMap<>();
		List<Row> modRows = new ArrayList<>();
		List<Icon> icons = new ArrayList<>();
		ClickableWidget ukulib = env.ukulib();
		int columnRight = margin + 260;
		for (ClickableWidget widget : env.buttons()) {
			if (widget instanceof HotspotButton) {
				continue;
			}
			int[] first = FIRST_SEEN.computeIfAbsent(widget, w -> new int[] {w.getX(), w.getY(), w.getWidth(), w.getHeight()});
			String key = env.keyOf(widget);
			if (key != null && MAIN_KEYS.contains(key)) {
				byKey.put(key, widget);
				continue;
			}
			String className = widget.getClass().getName();
			String label = widget.getMessage().getString().strip();
			// Essential lays out its own menu, character and badges around these - never touch them; same for any
			// placeholder-style widget ("<essential_player>") that only exists to anchor another mod's drawing.
			if (className.startsWith("gg.essential.") || label.startsWith("<") && label.endsWith(">")) {
				continue;
			}
			if (widget == ukulib) {
				// ukulib paints a player face over its button after every frame: keep the real one off-screen and
				// click it through our own icon.
				RESTYLED.remove(widget);
				widget.setX(-10000);
				widget.setY(-10000);
				HotspotButton proxy = PROXIES.get(UKULIB);
				if (proxy != null) {
					icons.add(new Icon(proxy.forwardTo(widget), "extensions"));
				}
				continue;
			}
			if (TitleScreenTheme.matchesAny(TitleScreenTheme.TITLE_HIDE_ONLY_NEEDLES, label)) {
				widget.setX(-10000);
				widget.setY(-10000);
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_EXTRA_ICONS, label);
			if (iconKey == null && className.startsWith("com.terraformersmc.modmenu") && (label.isEmpty() || first[2] <= 24)) {
				iconKey = "mods";
			}
			if (iconKey == null && label.isEmpty()) {
				iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICON_CLASSES, className);
			}
			boolean iconOnly = label.isEmpty() || first[2] <= 24 && first[3] <= 24;
			boolean meantForMenu = Math.abs(first[0] + first[2] / 2 - width / 2) <= 110 || first[0] < columnRight;
			if (iconKey != null) {
				icons.add(new Icon(widget, iconKey));
			} else if (iconOnly) {
				// Small icon buttons (Mod Menu's and the like) go in the icon row at the bottom, wherever the mod put them.
				icons.add(new Icon(widget, "extensions"));
			} else if (meantForMenu && first[2] >= 40) {
				modRows.add(new Row(widget, Text.literal(label), null, false, false, false));
			}
			// Anything else belongs to its mod's own layout: untouched.
		}

		boolean essential = FabricLoader.getInstance().isModLoaded("essential");
		List<Row> rows = new ArrayList<>();
		for (String key : MAIN_KEYS) {
			if (key.equals("$store")) {
				rows.add(new Row(storeRow, Text.literal("Store"), "NEW", true, false, false));
			} else if (byKey.containsKey(key)) {
				ClickableWidget widget = byKey.get(key);
				ClickableWidget clickable = widget;
				HotspotButton proxy = PROXIES.get(key);
				if (essential && proxy != null) {
					anchorForEssential(widget, width, margin);
					clickable = proxy.forwardTo(widget);
				}
				rows.add(new Row(clickable, widget.getMessage(), null, true, key.equals("menu.singleplayer"), key.equals("menu.quit")));
			}
		}
		int quitIndex = rows.size();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).quit()) {
				quitIndex = i;
			}
		}
		rows.addAll(quitIndex, modRows);

		// ---- Layout ----
		int y = top;
		int step = 0;
		slideIn(context, open, step++);
		int brandH = drawBrand(context, renderer, margin, y, large);
		context.getMatrices().popMatrix();
		y += brandH + (compact ? 10 : large ? 22 : 16);

		LastServer.Entry last = LastServer.get();
		int cardW = Math.min(large ? 250 : 220, width - margin * 2);
		int cardH = large ? 36 : 30;
		if (last != null && height >= 230) {
			jumpCard.place(margin, y, cardW, cardH, true);
			slideIn(context, open, step++);
			drawJumpCard(context, renderer, margin, y, cardW, cardH, last, MenuKit.hover(jumpCard, hovered(mouseX, mouseY, jumpCard)));
			context.getMatrices().popMatrix();
			y += cardH + (compact ? 8 : large ? 16 : 12);
		} else {
			jumpCard.place(0, 0, 0, 0, false);
		}

		int rowH = compact ? 15 : large ? 25 : 19;
		int secondaryH = compact ? 12 : large ? 16 : 14;
		for (Row row : rows) {
			ClickableWidget widget = row.widget();
			int h = row.main() ? rowH : secondaryH;
			Text label = !row.main() ? MenuKit.uiBold(row.label().getString())
					: large ? MenuKit.displayLg(row.label().getString()) : MenuKit.display(row.label().getString());
			int w = Math.min(width - margin * 2, MenuKit.width(renderer, label) + 30 + (row.badge() != null ? 34 : 0));
			if (widget instanceof HotspotButton hotspot) {
				hotspot.place(margin, y, w, h, true);
				if (hotspot != storeRow) {
					ClickableWidget target = byKey.values().stream().filter(v -> PROXIES.get(env.keyOf(v)) == hotspot).findFirst().orElse(null);
					if (target != null) {
						hotspot.active = target.active;
					}
				}
			} else {
				widget.setX(margin);
				widget.setY(y);
				widget.setWidth(w);
				widget.setHeight(h);
				RESTYLED.add(widget);
			}
			slideIn(context, open, step++);
			drawRow(context, renderer, row, label, margin, y, w, h, large, MenuKit.hover(widget, widget.active && hovered(mouseX, mouseY, widget)));
			context.getMatrices().popMatrix();
			y += h + (row.main() ? 2 : 0);
		}

		// ---- Footer: you, friends online, small icon buttons - one row, centred on the icons ----
		int iconSize = large ? 22 : 18;
		int footerY = height - clamp(Math.round(height * 0.07f), 10, 56) - iconSize;
		boolean showFooter = footerY > y + 8;
		int x = margin;
		String name = MenuInfo.username();
		String uuid = MenuInfo.uuid();
		slideIn(context, open, step++);
		if (showFooter && name != null && !name.isEmpty()) {
			int head = iconSize;
			PlayerHeads.draw(context, uuid, name, x, footerY, head);
			VeloDraw.strokeRounded(context, x - 1, footerY - 1, head + 2, head + 2, 4, MenuKit.accent());
			int online = onlineFriends();
			Text nameText = MenuKit.uiBold(name);
			Text friendsLine = MenuKit.ui(online < 0 ? "Velo Network offline" : online == 0 ? "No friends online"
					: online + (online == 1 ? " friend online" : " friends online"));
			int blockH = MenuKit.H_UI * 2 + 3;
			int ty = footerY + (iconSize - blockH) / 2;
			MenuKit.text(context, renderer, nameText, x + head + 8, ty, MenuKit.TEXT);
			MenuKit.text(context, renderer, friendsLine, x + head + 8, ty + MenuKit.H_UI + 3, online > 0 ? MenuKit.GREEN : MenuKit.TEXT_3);
			x += head + 8 + Math.max(MenuKit.width(renderer, nameText), MenuKit.width(renderer, friendsLine)) + 16;
		}
		boolean ownFriends = icons.stream().noneMatch(icon -> icon.key().equals("friends"));
		friends.place(x, footerY, iconSize, iconSize, showFooter && ownFriends);
		if (showFooter && ownFriends) {
			drawIconButton(context, x, footerY, iconSize, "friends", MenuKit.hover(friends, hovered(mouseX, mouseY, friends)), true);
			x += iconSize + 5;
		}
		for (Icon icon : icons) {
			ClickableWidget widget = icon.widget();
			env.undraw(widget);
			if (widget instanceof HotspotButton hotspot) {
				hotspot.place(x, footerY, iconSize, iconSize, showFooter);
			}
			widget.setX(showFooter ? x : -10000);
			widget.setY(footerY);
			widget.setWidth(iconSize);
			widget.setHeight(iconSize);
			if (showFooter) {
				drawIconButton(context, x, footerY, iconSize, icon.key(), MenuKit.hover(widget, hovered(mouseX, mouseY, widget)), widget.active);
				x += iconSize + 5;
			}
		}
		context.getMatrices().popMatrix();

		// ---- Store card, bottom right (unless a mod owns the right side) ----
		int scW = large ? 180 : 150;
		int scH = large ? 40 : 34;
		boolean showCard = !essential && width >= 420 && width - margin - scW > margin + cardW + 20;
		int scX = width - margin - scW;
		int scY = footerY + iconSize - scH;
		storeCard.place(scX, scY, scW, scH, showCard);
		if (showCard) {
			slideIn(context, open, step + 2);
			drawStoreCard(context, renderer, scX, scY, scW, scH, MenuKit.hover(storeCard, hovered(mouseX, mouseY, storeCard)));
			context.getMatrices().popMatrix();
		}
	}

	/**
	 * Essential: a vanilla menu button stays (invisible, unclickable) in a narrow column in the middle of the free space
	 * right of our menu, at vanilla's height - Essential hangs its character left of that column and its menu right of
	 * it, so both land in that space instead of off the left edge or under our words.
	 */
	private static void anchorForEssential(ClickableWidget widget, int width, int margin) {
		RESTYLED.remove(widget);
		int[] first = FIRST_SEEN.get(widget);
		int freeLeft = margin + 260;
		int center = Math.max(freeLeft + 130, (freeLeft + width - margin) / 2);
		widget.setX(center - 10);
		widget.setWidth(20);
		if (first != null) {
			widget.setY(first[1]);
			widget.setHeight(first[3]);
		}
		widget.visible = false;
	}

	// ---- Entrance ----

	private static Screen animatedFor;
	private static long openedAt;

	/** 0..1 since the title screen opened (a new screen instance restarts it; resizing doesn't). */
	private static float openProgress() {
		return Math.min(1f, (System.nanoTime() - openedAt) / 900_000_000f);
	}

	/** Pushes a slide-in transform for element {@code index}: each one comes in from the left a little after the last. */
	private static void slideIn(DrawContext context, float open, int index) {
		float local = Math.max(0f, Math.min(1f, (open * 900f - index * 45f) / 380f));
		float eased = 1f - (float) Math.pow(1f - local, 3);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(-28f * (1f - eased), 0f);
	}

	private static int onlineFriends() {
		SocialClient.State state = SocialClient.snapshot();
		if (state == null || state.friends == null) {
			return -1;
		}
		int n = 0;
		for (SocialClient.Friend friend : state.friends) {
			if (friend.online) {
				n++;
			}
		}
		return n;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static final String VERSION = FabricLoader.getInstance().getModContainer("velo-client")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

	/** Logo, "VELO CLIENT" and the version line; returns its height. */
	private static int drawBrand(DrawContext context, TextRenderer renderer, int x, int y, boolean large) {
		int logo = large ? 28 : 22;
		MenuKit.logo(context, x, y, logo);
		Text title = large ? MenuKit.display("VELO CLIENT") : MenuKit.displaySm("VELO CLIENT");
		MenuKit.text(context, renderer, title, x + logo + 9, y + (large ? 2 : 2), MenuKit.TEXT);
		String mc = FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		String version = VERSION.contains("+") ? VERSION.substring(0, VERSION.indexOf('+')) : VERSION;
		MenuKit.text(context, renderer, MenuKit.mono(version + " · Minecraft " + mc), x + logo + 9, y + (large ? 17 : 13), 0xFF8A8A95);
		return logo;
	}

	private static void drawJumpCard(DrawContext context, TextRenderer renderer, int x, int y, int w, int h, LastServer.Entry last, float hover) {
		int accent = MenuKit.accent();
		VeloDraw.fillRounded(context, x, y, w, h, 7, VeloAnim.lerpArgb((accent & 0xFFFFFF) | 0x1F000000, (accent & 0xFFFFFF) | 0x33000000, hover));
		VeloDraw.strokeRounded(context, x, y, w, h, 7, VeloAnim.lerpArgb((accent & 0xFFFFFF) | 0x59000000, accent, hover));
		Identifier icon = LastServer.icon();
		if (icon != null) {
			MenuKit.texture(context, icon, x + 6, y + 6, h - 12, 64);
		} else {
			MenuKit.logo(context, x + 6, y + 6, h - 12);
		}
		int textY = y + (h - (MenuKit.H_MONO + MenuKit.H_UI + 4)) / 2;
		MenuKit.text(context, renderer, MenuKit.mono("JUMP BACK IN"), x + h, textY, MenuKit.accentText());
		Text name = MenuKit.fit(renderer, MenuKit.uiBold(last.serverName()), w - h - 30);
		MenuKit.text(context, renderer, name, x + h, textY + MenuKit.H_MONO + 4, MenuKit.TEXT);
		int cx = x + w - 15;
		int cy = y + h / 2;
		VeloDraw.fillCircle(context, cx, cy, 9 + hover, accent);
		MenuKit.icon(context, "play", cx - 5, cy - 5, 10, MenuKit.onAccent());
	}

	private static void drawRow(DrawContext context, TextRenderer renderer, Row row, Text label, int x, int y, int w, int h, boolean large, float hover) {
		int base = row.primary() ? MenuKit.TEXT : row.quit() ? MenuKit.TEXT_3 : MenuKit.TEXT_2;
		if (!row.widget().active) {
			base = 0xFF4A4A52;
		}
		int color = VeloAnim.lerpArgb(base, 0xFFFFFFFF, hover);
		int slide = Math.round(hover * 8);
		// Where the capitals actually sit for each font (TTF glyphs don't start at the draw y).
		int fontH = !row.main() ? MenuKit.H_UI : large ? 16 : MenuKit.H_DISPLAY;
		int ty = y + (h - fontH) / 2;
		if (hover > 0.02f) {
			int arrowAlpha = Math.round(hover * 255);
			Text arrow = !row.main() ? MenuKit.uiBold("›") : large ? MenuKit.displayLg("›") : MenuKit.display("›");
			MenuKit.text(context, renderer, arrow, x - 10 + slide, ty, (arrowAlpha << 24) | (MenuKit.accentText() & 0xFFFFFF));
		}
		MenuKit.text(context, renderer, label, x + slide, ty, color);
		if (row.badge() != null) {
			Text badge = MenuKit.mono(row.badge());
			int bh = large ? 12 : 10;
			int bx = x + slide + MenuKit.width(renderer, label) + 8;
			int bw = MenuKit.width(renderer, badge) + 8;
			// Centred on the word's capitals, not on the row box.
			int by = ty + (fontH - bh) / 2 - (large ? -1 : 3);
			VeloDraw.fillRounded(context, bx, by, bw, bh, 3, MenuKit.accent());
			MenuKit.text(context, renderer, badge, bx + 4, by + (bh - MenuKit.H_MONO) / 2, MenuKit.onAccent());
		}
	}

	private static void drawIconButton(DrawContext context, int x, int y, int size, String icon, float hover, boolean active) {
		VeloDraw.fillRounded(context, x, y, size, size, 5, VeloAnim.lerpArgb(0x14FFFFFF, 0x26FFFFFF, hover));
		VeloDraw.strokeRounded(context, x, y, size, size, 5, VeloAnim.lerpArgb(0x17FFFFFF, MenuKit.accent(), hover));
		int inner = Math.round(size * 0.55f);
		MenuKit.icon(context, icon, x + (size - inner) / 2, y + (size - inner) / 2, inner, active ? 0xFFC9C9D1 : 0xFF66666E);
	}

	private static void drawStoreCard(DrawContext context, TextRenderer renderer, int x, int y, int w, int h, float hover) {
		MenuKit.panel(context, x, y, w, h, 8);
		if (hover > 0.01f) {
			VeloDraw.strokeRounded(context, x, y, w, h, 8, VeloAnim.lerpArgb(MenuKit.HAIRLINE, MenuKit.accent(), hover));
		}
		VeloDraw.fillRounded(context, x + 6, y + 6, h - 12, h - 12, 5, (MenuKit.accent() & 0xFFFFFF) | 0x2E000000);
		MenuKit.icon(context, "store", x + 6 + (h - 12 - 12) / 2, y + 6 + (h - 12 - 12) / 2, 12, MenuKit.accentText());
		MenuKit.text(context, renderer, MenuKit.mono("NEW IN STORE"), x + h + 2, y + 8, 0xFFBCA6FF);
		String featured = net.veloclient.velo.client.cosmetics.wings.WingsManager.featured().map(c -> c.name() + " collection").orElse("Wings, capes and auras");
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(featured), w - h - 8), x + h + 2, y + 18, MenuKit.TEXT);
	}
}
