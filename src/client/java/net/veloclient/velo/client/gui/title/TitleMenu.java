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
 * TitleScreenMixin) - Velo only moves and paints them. Mod Menu's button always becomes a main "Mods" entry and
 * Replay Mod's / Flashback's viewer a "Replays" row (as icons nobody found them); other buttons a mod puts into
 * vanilla's centre column become extra rows under the menu, icon-only ones join the icon row. Essential's character
 * and side menu go right of the menu (see {@link EssentialAnchors}); the Store card hides when Essential is installed.
 *
 * <p>Partner servers sit in a bar along the top, right of the brand: each with its own server icon, name and live
 * player count (the Velo server pings them; the game never does), one click joins.
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
	/** "Discover": friends' and public hosted worlds and partner servers (World Hosting, Join tab). */
	private static HotspotButton discoverRow;
	/** One per partner card in the top bar (placed - or hidden - every frame). */
	private static final List<HotspotButton> PARTNER_CARDS = new ArrayList<>();
	private static final int MAX_PARTNER_CARDS = 6;
	private static final Map<String, Identifier> PARTNER_ICONS = new java.util.concurrent.ConcurrentHashMap<>();
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
		discoverRow = new HotspotButton(Text.literal("Discover"),
				() -> client.setScreen(net.veloclient.velo.client.gui.WorldHostScreen.discover(screen)));
		List<ClickableWidget> out = new ArrayList<>(List.of(storeRow, jumpCard, storeCard, friends, discoverRow));
		PARTNER_CARDS.clear();
		for (int i = 0; i < MAX_PARTNER_CARDS; i++) {
			int index = i;
			HotspotButton card = new HotspotButton(Text.literal("Partner server"), () -> {
				List<net.veloclient.velo.client.hosting.WorldHosting.Partner> list = net.veloclient.velo.client.hosting.WorldHosting.partnersCached();
				if (index < list.size()) {
					net.veloclient.velo.client.util.ClientCompat.joinServer(list.get(index).address(), list.get(index).name());
				}
			});
			card.place(0, 0, 0, 0, false);
			PARTNER_CARDS.add(card);
			out.add(card);
		}
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
		Row modsRow = null;
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
			// Mod Menu and the replay viewers get real, labelled rows - as small icons nobody recognised them.
			if (className.startsWith("com.terraformersmc.modmenu")) {
				modsRow = new Row(widget, Text.literal("Mods"), null, true, false, false);
				continue;
			}
			if (TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICONS, label) != null
					|| label.isEmpty() && TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICON_CLASSES, className) != null) {
				modRows.add(new Row(widget, Text.literal("Replays"), null, false, false, false));
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_EXTRA_ICONS, label);
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
				if (modsRow != null) {
					rows.add(modsRow);
				}
			} else if (byKey.containsKey(key)) {
				ClickableWidget widget = byKey.get(key);
				ClickableWidget clickable = widget;
				HotspotButton proxy = PROXIES.get(key);
				if (essential && proxy != null) {
					RESTYLED.remove(widget);
					clickable = proxy.forwardTo(widget);
				}
				rows.add(new Row(clickable, widget.getMessage(), null, true, key.equals("menu.singleplayer"), key.equals("menu.quit")));
				if (key.equals("menu.multiplayer")) {
					rows.add(new Row(discoverRow, Text.literal("Discover"), "NEW", true, false, false));
				}
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
		int brandRight = margin + MenuKit.width(renderer, large ? MenuKit.display("VELO CLIENT") : MenuKit.displaySm("VELO CLIENT")) + (large ? 37 : 31);
		drawPartnerBar(context, renderer, brandRight + 28, top, width - margin - (FabricLoader.getInstance().isModLoaded("essential") ? 70 : 0),
				large, open, mouseX, mouseY);
		y += brandH + (compact ? 10 : large ? 22 : 16);

		LastServer.Entry last = LastServer.get();
		// With Essential the free space right of the menu is where its character goes: keep the card narrow.
		int cardW = Math.min(essential ? 190 : large ? 250 : 220, width - margin * 2);
		int contentRight = margin + MenuKit.width(renderer, large ? MenuKit.display("VELO CLIENT") : MenuKit.displaySm("VELO CLIENT")) + (large ? 37 : 31);
		int cardH = large ? 36 : 30;
		int cardBlock = cardH + (compact ? 8 : large ? 16 : 12);

		// Everything has to fit above the footer (you, friends, language...): rows get tighter first, the
		// "Jump back in" card goes only when even tight rows don't fit - most players are at 1080p (GUI 480x270).
		int iconSize = large ? 22 : 18;
		int footerY = height - clamp(Math.round(height * 0.07f), 10, 56) - iconSize;
		int rowH = compact ? 15 : large ? 25 : 19;
		int secondaryH = compact ? 12 : large ? 16 : 14;
		// Only servers that answer: no card pointing at one that's down or gone.
		boolean showCard = last != null && height >= 230 && LastServer.reachable(last);
		int mains = (int) rows.stream().filter(Row::main).count();
		int seconds = rows.size() - mains;
		int room = footerY - 8 - y - (showCard ? cardBlock : 0);
		while (mains * (rowH + 2) + seconds * secondaryH > room && rowH > (large ? 20 : 15)) {
			rowH--;
			secondaryH = Math.max(12, secondaryH - 1);
		}
		if (showCard && mains * (rowH + 2) + seconds * secondaryH > room) {
			showCard = false;
		}
		if (showCard) {
			jumpCard.place(margin, y, cardW, cardH, true);
			slideIn(context, open, step++);
			drawJumpCard(context, renderer, margin, y, cardW, cardH, last, MenuKit.hover(jumpCard, hovered(mouseX, mouseY, jumpCard)));
			context.getMatrices().popMatrix();
			contentRight = Math.max(contentRight, margin + cardW);
			y += cardH + (compact ? 8 : large ? 16 : 12);
		} else {
			jumpCard.place(0, 0, 0, 0, false);
		}

		for (Row row : rows) {
			ClickableWidget widget = row.widget();
			int h = row.main() ? rowH : secondaryH;
			Text label = !row.main() ? MenuKit.uiBold(row.label().getString())
					: large ? MenuKit.displayLg(row.label().getString()) : MenuKit.display(row.label().getString());
			int w = Math.min(width - margin * 2, MenuKit.width(renderer, label) + 30 + (row.badge() != null ? 34 : 0));
			if (widget instanceof HotspotButton hotspot) {
				hotspot.place(margin, y, w, h, true);
				if (hotspot != storeRow && hotspot != discoverRow) {
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
			contentRight = Math.max(contentRight, margin + w);
			y += h + (row.main() ? 2 : 0);
		}

		// ---- Footer: you, friends online, small icon buttons - one row, centred on the icons ----
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
		if (showFooter) {
			contentRight = Math.max(contentRight, x);
		}
		if (essential) {
			anchorForEssential(byKey, width, contentRight);
		}

		// ---- Store card, bottom right (unless a mod owns the right side) ----
		int scW = large ? 180 : 150;
		int scH = large ? 40 : 34;
		boolean showStoreCard = !essential && width >= 420 && width - margin - scW > margin + cardW + 20;
		int scX = width - margin - scW;
		int scY = footerY + iconSize - scH;
		storeCard.place(scX, scY, scW, scH, showStoreCard);
		if (showStoreCard) {
			slideIn(context, open, step + 2);
			drawStoreCard(context, renderer, scX, scY, scW, scH, MenuKit.hover(storeCard, hovered(mouseX, mouseY, storeCard)));
			context.getMatrices().popMatrix();
		}
	}

	/**
	 * Essential: the vanilla menu buttons stay as invisible, unclickable anchors (our proxies take the clicks), parked
	 * where Essential then draws its character in the free space right of our menu and its side menu at the right
	 * edge - see {@link EssentialAnchors}. On screens too narrow for the character it goes off-screen (its Wardrobe is
	 * also in the side menu) instead of over our words.
	 */
	private static void anchorForEssential(Map<String, ClickableWidget> byKey, int width, int contentRight) {
		ClickableWidget top = byKey.get("menu.singleplayer");
		ClickableWidget second = byKey.get("menu.multiplayer");
		EssentialAnchors.Plan plan = EssentialAnchors.plan(width, contentRight, top != null && second != null);
		for (Map.Entry<String, ClickableWidget> entry : byKey.entrySet()) {
			ClickableWidget widget = entry.getValue();
			RESTYLED.remove(widget);
			int[] first = FIRST_SEEN.get(widget);
			int x = plan == null ? -10000 : widget == top ? plan.topX() : plan.menuX();
			widget.setX(x);
			widget.setWidth(EssentialAnchors.ANCHOR_W);
			if (first != null) {
				widget.setY(first[1]);
				widget.setHeight(first[3]);
			}
			widget.visible = false;
		}
	}

	// ---- Partner servers (top bar) ----

	/**
	 * The partner cards from {@code left} to {@code right}, right-aligned at the top; as many as fit (none on narrow
	 * screens). They drop in from above after the menu has slid in.
	 */
	private static void drawPartnerBar(DrawContext context, TextRenderer renderer, int left, int top, int right, boolean large, float open,
			int mouseX, int mouseY) {
		List<net.veloclient.velo.client.hosting.WorldHosting.Partner> partners = net.veloclient.velo.client.hosting.WorldHosting.partnersCached();
		int cardH = large ? 34 : 28;
		int icon = cardH - 10;
		int gap = 6;
		List<Integer> widths = new ArrayList<>();
		int total = 0;
		int shown = 0;
		Text label = MenuKit.mono("PARTNER SERVERS");
		int labelW = MenuKit.width(renderer, label) + 12;
		for (int i = 0; i < Math.min(partners.size(), MAX_PARTNER_CARDS); i++) {
			var p = partners.get(i);
			int nameW = MenuKit.width(renderer, MenuKit.uiBold(p.name()));
			int lineW = MenuKit.width(renderer, MenuKit.ui(playersLine(p)));
			int w = Math.min(170, icon + 18 + Math.max(nameW, lineW) + 10);
			if (left + labelW + total + w + (shown > 0 ? gap : 0) > right) {
				break;
			}
			total += w + (shown > 0 ? gap : 0);
			widths.add(w);
			shown++;
		}
		for (int i = 0; i < PARTNER_CARDS.size(); i++) {
			if (i >= shown) {
				PARTNER_CARDS.get(i).place(0, 0, 0, 0, false);
			}
		}
		if (shown == 0) {
			return;
		}
		float local = Math.max(0f, Math.min(1f, (open * 900f - 260f) / 420f));
		float eased = 1f - (float) Math.pow(1f - local, 3);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(0f, -14f * (1f - eased));
		int x = right - total;
		int alpha = Math.round(eased * 255);
		MenuKit.text(context, renderer, label, x - labelW, top + (cardH - MenuKit.H_MONO) / 2, (Math.round(alpha * 0.55f) << 24) | 0xFFFFFF);
		for (int i = 0; i < shown; i++) {
			var p = partners.get(i);
			int w = widths.get(i);
			HotspotButton hotspot = PARTNER_CARDS.get(i);
			hotspot.place(x, top, w, cardH, true);
			float hover = MenuKit.hover(hotspot, hovered(mouseX, mouseY, hotspot));
			int lift = Math.round(hover * 2);
			int cy = top - lift;
			int accent = p.accent() != 0 ? p.accent() : MenuKit.accent();
			VeloDraw.shadow(context, x, cy, w, cardH, 8, 10 + Math.round(hover * 6), 3, VeloAnim.lerpArgb(0x40000000, (accent & 0xFFFFFF) | 0x55000000, hover));
			VeloDraw.fillRounded(context, x, cy, w, cardH, 8, VeloAnim.lerpArgb(0xB8101015, 0xD81A1A22, hover));
			VeloDraw.strokeRounded(context, x, cy, w, cardH, 8, VeloAnim.lerpArgb(0x1FFFFFFF, accent, hover));
			Identifier texture = p.iconUrl() == null ? null : PARTNER_ICONS.get(p.iconUrl());
			if (texture == null && p.iconUrl() != null) {
				String url = p.iconUrl();
				net.veloclient.velo.client.gui.RemoteTextureLoader.load(url, id -> PARTNER_ICONS.put(url, id));
			}
			int ix = x + 5;
			int iy = cy + 5;
			if (texture != null) {
				MenuKit.texture(context, texture, ix, iy, icon, 64);
			} else {
				VeloDraw.fillRounded(context, ix, iy, icon, icon, 5, (accent & 0xFFFFFF) | 0x33000000);
				MenuKit.icon(context, "globe", ix + icon / 4, iy + icon / 4, icon / 2, accent);
			}
			int tx = ix + icon + 7;
			int textW = w - (tx - x) - 8;
			int ty = cy + (cardH - (MenuKit.H_UI * 2 + 3)) / 2;
			MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(p.name()), textW), tx, ty, MenuKit.TEXT);
			// Hover turns the player count into the call to action.
			Text line = hover > 0.5f ? MenuKit.ui("Click to join") : MenuKit.ui(playersLine(p));
			int lineColor = hover > 0.5f ? MenuKit.accentText() : p.online() ? MenuKit.GREEN : MenuKit.TEXT_3;
			if (p.online() && hover <= 0.5f) {
				// A live dot that breathes.
				float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 4e8);
				VeloDraw.fillCircle(context, tx + 2.5f, ty + MenuKit.H_UI + 3 + 3.5f, 2.2f + pulse * 0.6f, MenuKit.GREEN);
				MenuKit.text(context, renderer, MenuKit.fit(renderer, line, textW - 8), tx + 8, ty + MenuKit.H_UI + 3, lineColor);
			} else {
				MenuKit.text(context, renderer, MenuKit.fit(renderer, line, textW), tx, ty + MenuKit.H_UI + 3, lineColor);
			}
			x += w + gap;
		}
		context.getMatrices().popMatrix();
	}

	private static String playersLine(net.veloclient.velo.client.hosting.WorldHosting.Partner p) {
		if (!p.online()) {
			return p.versions().isEmpty() ? "Offline right now" : p.versions();
		}
		return String.format(java.util.Locale.ROOT, "%,d online", p.players());
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
