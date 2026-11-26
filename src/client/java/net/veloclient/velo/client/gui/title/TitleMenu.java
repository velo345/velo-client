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
 * <p>Partner servers live in Discover (World Hosting, Join tab), not on the title screen - it was too busy.
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

	/**
	 * Whether {@code widget} is a real button Velo paints itself here - MenuWidgetDrawMixin skips vanilla's drawing of it
	 * always, not only while vanilla renders the screen (anything drawing the widgets outside that made the vanilla
	 * buttons flash in the middle when hovering ours - bug report, 2026-11-06).
	 */
	public static boolean paintsItself(Object widget) {
		return widget instanceof ClickableWidget w && RESTYLED.contains(w);
	}

	/** While the Play Deck shows: where its account pill is ({x, y}) - the account list opens above it. */
	public static int[] deckAccountAnchor;
	private static HotspotButton accountPill;
	private static HotspotButton storeRow;
	private static HotspotButton jumpCard;
	private static HotspotButton storeCard;
	private static HotspotButton friends;
	/** "Discover": friends' and public hosted worlds and partner servers (World Hosting, Join tab). */
	private static HotspotButton discoverRow;
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
		try {
			return createHotspotsUnsafe(screen);
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			org.slf4j.LoggerFactory.getLogger("velo-client").error("Velo title menu couldn't be built - showing the plain menu for this session", e);
			return new ArrayList<>();
		}
	}

	private static List<ClickableWidget> createHotspotsUnsafe(Screen screen) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (animatedFor != screen) {
			animatedFor = screen;
			openedAt = System.nanoTime();
		}
		storeRow = new HotspotButton(Text.literal("Store"), () -> client.setScreen(new StoreScreen(screen)));
		jumpCard = new HotspotButton(Text.literal("Jump back in"), LastServer::join);
		storeCard = new HotspotButton(Text.literal("Store"), () -> {
			if (!net.veloclient.velo.client.emotes.EmoteCatalog.forSale().isEmpty()) {
				StoreScreen.showEmotes();
			}
			client.setScreen(new StoreScreen(screen));
		});
		friends = new HotspotButton(Text.literal("Friends"), () -> client.setScreen(new FriendsScreen(screen)));
		accountPill = new HotspotButton(Text.literal("Account"), () -> {
			if (net.veloclient.velo.devbuild.accounts.AccountSwitcher.toggle()) {
				return;
			}
			client.setScreen(new FriendsScreen(screen));   // no account switcher on this version: your friends
		});
		discoverRow = new HotspotButton(Text.literal("Discover"),
				() -> client.setScreen(net.veloclient.velo.client.gui.WorldHostScreen.discover(screen)));
		List<ClickableWidget> out = new ArrayList<>(List.of(storeRow, jumpCard, storeCard, friends, discoverRow, accountPill));
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
		// Vanilla's buttons are only hidden when the Velo menu can really paint over them: before, a menu whose widgets
		// never got created (another mod's init order, an error) left the title screen with no buttons at all.
		if (broken || !ready()) {
			return;
		}
		for (ClickableWidget widget : RESTYLED) {
			widget.visible = false;
		}
	}

	private static boolean hovered(int mouseX, int mouseY, ClickableWidget w) {
		return w.visible && MenuKit.inside(mouseX, mouseY, w.getX(), w.getY(), w.getWidth(), w.getHeight());
	}

	/** Set once the Velo menu threw: the rest of the session shows the plain menu (with every mod's buttons) instead of crashing. */
	private static boolean broken;
	private static int missingFrames;

	private static boolean ready() {
		return jumpCard != null && storeRow != null && storeCard != null && friends != null && discoverRow != null && accountPill != null;
	}

	/**
	 * Lays out and paints the whole menu; call after vanilla rendered. Never throws: the title screen also draws under
	 * the loading screen at startup, so one odd mod button must not take the whole game down.
	 */
	public static void render(DrawContext context, int width, int height, int mouseX, int mouseY, Env env) {
		if (net.veloclient.velo.client.gui.VeloFonts.needsHealthCheck()) {
			TextRenderer fontCheck = MinecraftClient.getInstance().textRenderer;
			net.veloclient.velo.client.gui.VeloFonts.beginHealthCheck();
			int narrow = 0, wide = 0;
			try {
				narrow = MenuKit.width(fontCheck, Text.literal("iiiiiiii"));
				wide = MenuKit.width(fontCheck, Text.literal("WWWWWWWW"));
			} catch (RuntimeException ignored) {
				// counts as broken
			}
			net.veloclient.velo.client.gui.VeloFonts.endHealthCheck(narrow, wide);
		}
		// The loading screen can draw the title screen before its init() made our widgets (seen with Essential on
		// 26.2): wait for them instead of marking the menu broken for the whole session.
		if (broken || !ready()) {
			if (!broken && animatedFor != null && System.nanoTime() - openedAt > 2_000_000_000L && ++missingFrames > 120) {
				// still no widgets two seconds after the screen opened: the plain menu for this session
				broken = true;
				org.slf4j.LoggerFactory.getLogger("velo-client").warn("Velo title menu never got its widgets - showing the plain menu for this session");
			}
			for (ClickableWidget widget : RESTYLED) {
				widget.visible = true;
			}
			return;
		}
		missingFrames = 0;
		MenuKit.accentOverride = ScreenLook.title().accent;
		try {
			renderMenu(context, width, height, mouseX, mouseY, env);
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			for (ClickableWidget widget : RESTYLED) {
				widget.visible = true;
			}
			org.slf4j.LoggerFactory.getLogger("velo-client").error("Velo title menu failed - showing the plain menu for this session", e);
		} finally {
			MenuKit.accentOverride = -1;
		}
	}

	private static String labelOf(ClickableWidget widget) {
		var message = widget.getMessage();
		String text = message != null ? message.getString() : null;
		return text != null ? text.strip() : "";
	}

	private static void renderMenu(DrawContext context, int width, int height, int mouseX, int mouseY, Env env) {
		net.veloclient.velo.client.sound.VeloUiSounds.gameLoaded();
		deckAccountAnchor = null;
		if (accountPill != null) {
			accountPill.place(0, 0, 0, 0, false);
		}
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
		// A mod that adds its button again on every frame or re-init would otherwise fill the screen with copies.
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (ClickableWidget widget : env.buttons()) {
			if (widget instanceof HotspotButton) {
				continue;
			}
			String identity = widget.getClass().getName() + "|" + labelOf(widget);
			if (!seen.add(identity) && (env.keyOf(widget) == null || !MAIN_KEYS.contains(env.keyOf(widget)))) {
				widget.visible = false;
				continue;
			}
			int[] first = FIRST_SEEN.computeIfAbsent(widget, w -> new int[] {w.getX(), w.getY(), w.getWidth(), w.getHeight()});
			String key = env.keyOf(widget);
			if (key != null && MAIN_KEYS.contains(key)) {
				byKey.put(key, widget);
				continue;
			}
			String className = widget.getClass().getName();
			String label = labelOf(widget);
			// Essential lays out its own menu, character and badges around these - never touch them; same for any
			// placeholder-style widget ("<essential_player>") that only exists to anchor another mod's drawing.
			// Vanilla's copyright line (a clickable text) stays where vanilla puts it - on narrow windows its x fell
			// inside our column and it became a menu row.
			if (className.startsWith("gg.essential.") || label.startsWith("<") && label.endsWith(">") || label.startsWith("Copyright Mojang")) {
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
				modRows.add(new Row(widget, Text.literal(label.toLowerCase(java.util.Locale.ROOT).contains("rewind") ? "Velo Rewind" : "Replays"),
						null, true, false, false));
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_EXTRA_ICONS, label);
			boolean iconOnly = label.isEmpty() || first[2] <= 24 && first[3] <= 24;
			boolean meantForMenu = Math.abs(first[0] + first[2] / 2 - width / 2) <= 110 || first[0] < columnRight;
			if ((iconKey != null || iconOnly) && icons.size() >= 10 || meantForMenu && first[2] >= 40 && modRows.size() >= 8) {
				widget.visible = false;   // more than any real setup has - don't let a misbehaving mod take over the menu
			} else if (iconKey != null) {
				icons.add(new Icon(widget, iconKey));
			} else if (iconOnly) {
				// Small icon buttons (Mod Menu's and the like) go in the icon row at the bottom, wherever the mod put them.
				icons.add(new Icon(widget, "extensions"));
			} else if (meantForMenu && first[2] >= 40) {
				// Same words, size and style as the menu's own rows - a mod's button is just another entry.
				modRows.add(new Row(widget, Text.literal(label), null, true, false, false));
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
		// Velo+ Customize: alignment, free offsets per part, word size/font/colours and button style (ScreenLook).
		ScreenLook.Title look = ScreenLook.title();
		if (deck(look)) {
			renderDeck(context, renderer, width, height, mouseX, mouseY, env, byKey, modsRow, modRows, icons, essential, open);
			return;
		}
		String align = look.align;
		// Essential's side menu sits at the right edge: a right-aligned menu leaves it room.
		int rightEdge = width - margin - (essential && align.equals("right") ? 44 : 0);
		Align at = new Align(align, margin, rightEdge, width, height);
		float sizeK = look.scale;
		String fontId = ScreenLook.fontId(look.font);
		int y = top + at.dy(look, "brand");
		int step = 0;
		int contentLeft = Integer.MAX_VALUE;
		int contentRight = 0;
		Text brandTitle = large ? MenuKit.display("VELO CLIENT") : MenuKit.displaySm("VELO CLIENT");
		int brandW = MenuKit.width(renderer, brandTitle) + (large ? 37 : 31);
		int brandH = large ? 28 : 22;
		if (look.brand) {
			int bx = at.x(brandW, look, "brand");
			slideIn(context, open, step++, align);
			drawBrand(context, renderer, bx, y, large);
			context.getMatrices().popMatrix();
			contentLeft = Math.min(contentLeft, bx);
			contentRight = Math.max(contentRight, bx + brandW);
			y += brandH + (compact ? 10 : large ? 22 : 16);
		}
		y -= at.dy(look, "brand");

		LastServer.Entry last = LastServer.get();
		// With Essential the free space right of the menu is where its character goes: keep the card narrow.
		int cardW = Math.min(essential ? 190 : large ? 250 : 220, width - margin * 2);
		int cardH = large ? 36 : 30;
		int cardBlock = cardH + (compact ? 8 : large ? 16 : 12);

		// Everything has to fit above the footer (you, friends, language...): rows get tighter first, the
		// "Jump back in" card goes only when even tight rows don't fit - most players are at 1080p (GUI 480x270).
		int iconSize = large ? 22 : 18;
		int footerY = height - clamp(Math.round(height * 0.07f), 10, 56) - iconSize;
		int rowH = Math.round((compact ? 15 : large ? 25 : 19) * sizeK);
		int secondaryH = Math.round((compact ? 12 : large ? 16 : 14) * Math.min(1.25f, sizeK));
		int minRowH = Math.round((large ? 20 : 15) * Math.min(1f, sizeK));
		// Only servers that answer: no card pointing at one that's down or gone.
		boolean showCard = look.jump && last != null && height >= 230 && LastServer.reachable(last);
		int mains = (int) rows.stream().filter(Row::main).count();
		int seconds = rows.size() - mains;
		int menuTop = y + (showCard ? cardBlock : 0);
		int room = footerY - 8 - menuTop;
		while (mains * (rowH + 2) + seconds * secondaryH > room && rowH > minRowH) {
			rowH--;
			secondaryH = Math.max(12, secondaryH - 1);
		}
		if (showCard && mains * (rowH + 2) + seconds * secondaryH > room) {
			showCard = false;
			room += cardBlock;
		}
		// Still too many (lots of mods adding entries, small window): the whole column scales down to fit.
		int needed = mains * (rowH + 2) + seconds * secondaryH;
		float columnScale = needed > room && room > 0 ? Math.max(0.55f, room / (float) needed) : 1f;
		// No entry wider than the left part of the screen (long mod labels) - those scale down on their own.
		int columnMaxW = Math.max(120, Math.min(width - margin * 2, align.equals("center") ? width - margin * 2 - (essential ? 90 : 0) : width / 2 - margin));
		if (showCard) {
			int cx = at.x(cardW, look, "card");
			int cy = y + at.dy(look, "card");
			jumpCard.place(cx, cy, cardW, cardH, true);
			slideIn(context, open, step++, align);
			drawJumpCard(context, renderer, cx, cy, cardW, cardH, last, MenuKit.hover(jumpCard, hovered(mouseX, mouseY, jumpCard)));
			context.getMatrices().popMatrix();
			contentLeft = Math.min(contentLeft, cx);
			contentRight = Math.max(contentRight, cx + cardW);
			y += cardH + (compact ? 8 : large ? 16 : 12);
		} else {
			jumpCard.place(0, 0, 0, 0, false);
		}

		// The words of each row and their natural widths (boxed styles use one width for all).
		boolean boxed = !look.buttons.equals("words") && !look.buttons.equals("underline");
		List<Text> labels = new ArrayList<>();
		List<Float> labelScales = new ArrayList<>();
		int widest = 0;
		for (Row row : rows) {
			String words = row.label().getString();
			Text label;
			float labelScale;
			if (fontId != null) {
				label = MenuKit.inNamedFont(words, fontId);
				labelScale = (!row.main() ? 1.0f : large ? 1.9f : 1.35f) * (row.main() ? sizeK : Math.min(1.25f, sizeK));
			} else {
				label = !row.main() ? MenuKit.uiBold(words) : large ? MenuKit.displayLg(words) : MenuKit.display(words);
				labelScale = row.main() ? sizeK : Math.min(1.25f, sizeK);
			}
			labels.add(label);
			labelScales.add(labelScale);
			int w = Math.round(MenuKit.width(renderer, label) * labelScale) + (boxed ? 28 : 30) + (row.badge() != null ? 34 : 0);
			widest = Math.max(widest, w);
		}
		y += at.dy(look, "menu");
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			ClickableWidget widget = row.widget();
			int h = row.main() ? rowH : secondaryH;
			Text label = labels.get(i);
			int natural = Math.round(MenuKit.width(renderer, label) * labelScales.get(i)) + (boxed ? 28 : 30) + (row.badge() != null ? 34 : 0);
			int w = boxed ? widest : natural;
			float scale = Math.min(columnScale, columnMaxW / (float) w);
			int sw = Math.round(w * scale);
			int sh = Math.round(h * scale);
			int rx = at.x(sw, look, "menu");
			if (widget instanceof HotspotButton hotspot) {
				hotspot.place(rx, y, sw, sh, true);
				if (hotspot != storeRow && hotspot != discoverRow) {
					ClickableWidget target = byKey.values().stream().filter(v -> PROXIES.get(env.keyOf(v)) == hotspot).findFirst().orElse(null);
					if (target != null) {
						hotspot.active = target.active;
					}
				}
			} else {
				widget.setX(rx);
				widget.setY(y);
				widget.setWidth(sw);
				widget.setHeight(sh);
				RESTYLED.add(widget);
			}
			slideIn(context, open, step++, align);
			context.getMatrices().translate(rx, y);
			context.getMatrices().scale(scale, scale);
			drawRow(context, renderer, row, label, labelScales.get(i), fontId != null, 0, 0, w, h, large, look,
					MenuKit.hover(widget, widget.active && hovered(mouseX, mouseY, widget)));
			context.getMatrices().popMatrix();
			contentLeft = Math.min(contentLeft, rx);
			contentRight = Math.max(contentRight, rx + sw);
			y += Math.round((h + (row.main() ? 2 : 0)) * columnScale);
		}

		// ---- Footer: you, friends online, small icon buttons - one row, centred on the icons ----
		int footerDy = at.dy(look, "footer");
		boolean showFooter = look.footer && footerY > y + 8 - at.dy(look, "menu");
		footerY += footerDy;
		String name = MenuInfo.username();
		String uuid = MenuInfo.uuid();
		boolean showName = showFooter && name != null && !name.isEmpty();
		int online = onlineFriends();
		Text nameText = MenuKit.uiBold(name == null ? "" : name);
		Text friendsLine = MenuKit.ui(online < 0 ? "Velo Network offline" : online == 0 ? "No friends online"
				: online + (online == 1 ? " friend online" : " friends online"));
		boolean ownFriends = icons.stream().noneMatch(icon -> icon.key().equals("friends"));
		// measured first, so the whole row can sit left, centred or right
		int footerW = (showName ? iconSize + 8 + Math.max(MenuKit.width(renderer, nameText), MenuKit.width(renderer, friendsLine)) + 16 : 0)
				+ (ownFriends ? iconSize + 5 : 0) + icons.size() * (iconSize + 5) - 5;
		int x = at.x(Math.max(0, footerW), look, "footer");
		int footerLeft = x;
		slideIn(context, open, step++, align);
		if (showName) {
			int head = iconSize;
			PlayerHeads.draw(context, uuid, name, x, footerY, head);
			VeloDraw.strokeRounded(context, x - 1, footerY - 1, head + 2, head + 2, 4, MenuKit.accent());
			int blockH = MenuKit.H_UI * 2 + 3;
			int ty = footerY + (iconSize - blockH) / 2;
			MenuKit.text(context, renderer, nameText, x + head + 8, ty, MenuKit.TEXT);
			MenuKit.text(context, renderer, friendsLine, x + head + 8, ty + MenuKit.H_UI + 3, online > 0 ? MenuKit.GREEN : MenuKit.TEXT_3);
			x += head + 8 + Math.max(MenuKit.width(renderer, nameText), MenuKit.width(renderer, friendsLine)) + 16;
		}
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
			contentLeft = Math.min(contentLeft, footerLeft);
			contentRight = Math.max(contentRight, x);
		}
		if (contentLeft == Integer.MAX_VALUE) {
			contentLeft = margin;
		}
		contentRight = Math.max(contentRight, contentLeft + 2);
		if (essential) {
			if (align.equals("left")) {
				anchorForEssential(byKey, width, contentRight);
			} else {
				anchorAroundForEssential(byKey, width, contentLeft, contentRight);
			}
		}

		// ---- Store card, bottom right (bottom left when the menu is on the right; unless a mod owns that side) ----
		int scW = large ? 180 : 150;
		int scH = large ? 40 : 34;
		boolean storeLeft = align.equals("right");
		int scX = (storeLeft ? margin : width - margin - scW) + at.dx(look, "store");
		int scY = footerY - footerDy + iconSize - scH + at.dy(look, "store");
		boolean clear = storeLeft ? scX + scW + 20 < contentLeft : scX > contentRight + 20;
		boolean showStoreCard = look.store && !essential && width >= 420 && (clear || !look.moved.isEmpty() && look.moved.containsKey("store"));
		storeCard.place(scX, scY, scW, scH, showStoreCard);
		if (showStoreCard) {
			slideIn(context, open, step + 2, align);
			drawStoreCard(context, renderer, scX, scY, scW, scH, MenuKit.hover(storeCard, hovered(mouseX, mouseY, storeCard)));
			context.getMatrices().popMatrix();
		}
	}

	// ---- "Play Deck" (2026-11-25, owner-approved design E): the default title screen ----

	/**
	 * The deck is the title screen unless Customize changed the menu's layout (alignment, button style, size, font,
	 * colours, moved parts) - then the classic column with those settings.
	 */
	public static boolean deck(ScreenLook.Title look) {
		// 2026-11-25: the deck is THE title screen; Customize now only changes its background, colours and font (the old
		// layout options - alignment, moved parts, button styles - are gone from the launcher)
		return true;
	}

	/** Customize (Velo+): the deck's font (null = Velo's own) and text colour (-1 = white), set each frame. */
	private static String deckFontId;
	private static int deckTextColor = -1;

	private static Text deckLabel(String s) {
		return deckFontId != null ? MenuKit.inNamedFont(s, deckFontId) : MenuKit.uiBold(s);
	}

	private static int deckText(int fallback) {
		return deckTextColor >= 0 ? 0xFF000000 | deckTextColor : fallback;
	}

	private record Segment(ClickableWidget widget, String label, String icon) {
	}

	/** A main menu button as the deck clicks it (with Essential: our proxy, the real one stays its anchor). */
	private static ClickableWidget deckButton(Map<String, ClickableWidget> byKey, String key, boolean essential) {
		ClickableWidget widget = byKey.get(key);
		if (widget == null) {
			return null;
		}
		HotspotButton proxy = PROXIES.get(key);
		if (essential && proxy != null) {
			RESTYLED.remove(widget);
			return proxy.forwardTo(widget);
		}
		return widget;
	}

	/**
	 * Where UI-font text goes so its capitals sit in the middle of a box {@code h} tall at {@code y}: the glyphs start a
	 * little above the draw y and the capitals are ~5.3 GUI px (measured on a 3x screenshot, 2026-11-25) - the plain
	 * (h - 8) / 2 put every label ~2 px above its icon.
	 */
	private static int deckTextY(int y, int h) {
		return y + Math.round(h / 2f - 2.4f);
	}

	private static void deckPlace(ClickableWidget widget, int x, int y, int w, int h) {
		if (widget == null) {
			return;
		}
		if (widget instanceof HotspotButton hotspot) {
			hotspot.place(x, y, w, h, true);
			return;
		}
		widget.setX(x);
		widget.setY(y);
		widget.setWidth(w);
		widget.setHeight(h);
		RESTYLED.add(widget);
	}

	/**
	 * Centred: the logo, a slim "Continue" strip for your last server (with Play), Singleplayer and Multiplayer as two
	 * light glass buttons, every other place (Realms, Mods, mods' own buttons, Store, Discover) as one segment bar,
	 * Options and Quit side by side, the small icon buttons under them, you + friends bottom-left, versions bottom-right.
	 */
	private static void renderDeck(DrawContext context, TextRenderer renderer, int width, int height, int mouseX, int mouseY, Env env,
			Map<String, ClickableWidget> byKey, Row modsRow, List<Row> modRows, List<Icon> icons, boolean essential, float open) {
		boolean large = height >= 320;
		ScreenLook.Title customLook = ScreenLook.title();
		deckFontId = ScreenLook.fontId(customLook.font);
		deckTextColor = customLook.color;
		int colW = Math.min(width - 24, clamp(Math.round(width * 0.56f), 236, large ? 420 : 372));
		int x0 = (width - colW) / 2;
		int gap = large ? 7 : 5;
		int logo = large ? 30 : 22;
		Text brandText = large ? MenuKit.displayLg("VELO") : MenuKit.display("VELO");
		int brandH = logo + 6 + (large ? 16 : MenuKit.H_DISPLAY);
		int contH = large ? 34 : 26;
		int rowH = large ? 30 : 23;
		int segH = large ? 26 : 20;
		int optH = large ? 26 : 21;
		int iconS = large ? 20 : 16;
		int pillH = large ? 30 : 24;
		int bottomReserve = pillH + 16;

		LastServer.Entry last = LastServer.get();
		boolean showCont = last != null && LastServer.reachable(last);
		boolean showBrand = true;
		boolean brandWords = true;
		int[] parts = new int[1];
		for (int attempt = 0; attempt < 4; attempt++) {
			int t = (showBrand ? (brandWords ? brandH : logo) + (large ? 16 : 10) : 0) + (showCont ? contH + gap + 2 : 0)
					+ rowH + gap + segH + gap + optH + gap + 2 + iconS;
			parts[0] = t;
			if (t <= height - bottomReserve - 12) {
				break;
			}
			if (brandWords) {
				brandWords = false;
			} else if (showCont) {
				showCont = false;
			} else {
				showBrand = false;
			}
		}
		int blockH = parts[0];
		int y = Math.max(8, Math.min(height - bottomReserve - 6 - blockH, Math.round((height - blockH) / 2f - height * 0.04f)));
		int step = 0;
		int accent = MenuKit.accent();

		// ---- brand ----
		if (showBrand) {
			slideIn(context, open, step++, "center");
			MenuKit.logo(context, (width - logo) / 2, y, logo);
			if (brandWords) {
				int bw = MenuKit.width(renderer, brandText);
				MenuKit.text(context, renderer, brandText, (width - bw) / 2, y + logo + 6, deckText(MenuKit.TEXT));
			}
			context.getMatrices().popMatrix();
			y += (brandWords ? brandH : logo) + (large ? 16 : 10);
		}

		// ---- Continue ----
		if (showCont) {
			jumpCard.place(x0, y, colW, contH, true);
			float hover = MenuKit.hover(jumpCard, hovered(mouseX, mouseY, jumpCard));
			slideIn(context, open, step++, "center");
			int r = contH / 2 > 10 ? 10 : contH / 2;
			VeloDraw.fillRounded(context, x0, y, colW, contH, r, VeloAnim.lerpArgb(0xC40C0B0F, 0xD8121117, hover));
			VeloDraw.strokeRounded(context, x0, y, colW, contH, r, VeloAnim.lerpArgb(0x26FFFFFF, (accent & 0xFFFFFF) | 0x99000000, hover));
			int pad = large ? 6 : 4;
			int iconBox = contH - pad * 2;
			Identifier icon = LastServer.icon();
			if (icon != null) {
				MenuKit.texture(context, icon, x0 + pad, y + pad, iconBox, 64);
			} else {
				VeloDraw.fillRounded(context, x0 + pad, y + pad, iconBox, iconBox, 6, (accent & 0xFFFFFF) | 0x40000000);
				MenuKit.logo(context, x0 + pad + 3, y + pad + 3, iconBox - 6);
			}
			Text play = MenuKit.uiBold("Play");
			int playW = MenuKit.width(renderer, play) + (large ? 34 : 28);
			int playH = contH - pad * 2;
			int px = x0 + colW - pad - playW;
			VeloDraw.fillRounded(context, px, y + pad, playW, playH, Math.min(8, playH / 2), VeloAnim.lerpArgb(accent, VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.14f), hover));
			int iconSz = large ? 9 : 7;
			int contentW = iconSz + 5 + MenuKit.width(renderer, play);
			// the icon centred on the text line, not on the button (they sat a pixel or two apart - owner 2026-11-26)
			int textY = deckTextY(y + pad, playH);
			MenuKit.icon(context, "play", px + (playW - contentW) / 2, textY + (MenuKit.H_UI - 2 - iconSz) / 2, iconSz, MenuKit.onAccent());
			MenuKit.text(context, renderer, play, px + (playW - contentW) / 2 + iconSz + 5, textY, MenuKit.onAccent());
			int tx = x0 + pad + iconBox + (large ? 9 : 7);
			int textBlock = MenuKit.H_MONO + 3 + MenuKit.H_UI;
			int ty = y + (contH - textBlock) / 2;
			MenuKit.text(context, renderer, MenuKit.mono("CONTINUE"), tx, ty, 0xFF9A96A0);
			MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(last.serverName()), px - tx - 8), tx, ty + MenuKit.H_MONO + 3, MenuKit.TEXT);
			context.getMatrices().popMatrix();
			y += contH + gap + 2;
		} else {
			jumpCard.place(0, 0, 0, 0, false);
		}

		// ---- Singleplayer | Multiplayer ----
		int half = (colW - gap) / 2;
		ClickableWidget sp = deckButton(byKey, "menu.singleplayer", essential);
		ClickableWidget mp = deckButton(byKey, "menu.multiplayer", essential);
		slideIn(context, open, step++, "center");
		deckBig(context, renderer, sp, "Singleplayer", "d_user", x0, y, half, rowH, large, mouseX, mouseY);
		deckBig(context, renderer, mp, "Multiplayer", "d_users", x0 + colW - half, y, half, rowH, large, mouseX, mouseY);
		context.getMatrices().popMatrix();
		y += rowH + gap;

		// ---- the segment bar ----
		List<Segment> segments = new ArrayList<>();
		ClickableWidget realms = deckButton(byKey, "menu.online", essential);
		if (realms != null) {
			segments.add(new Segment(realms, "Realms", "home"));
		}
		if (modsRow != null) {
			segments.add(new Segment(modsRow.widget(), "Mods", "mods"));
		}
		for (Row row : modRows) {
			String label = row.label().getString();
			String icon = label.equals("Velo Rewind") || label.equals("Replays") ? "replay_editor" : "extensions";
			segments.add(new Segment(row.widget(), label.equals("Velo Rewind") ? "Rewind" : label, icon));
		}
		segments.add(new Segment(storeRow, "Store", "store"));
		segments.add(new Segment(discoverRow, "Discover", "globe"));
		slideIn(context, open, step++, "center");
		int barR = Math.min(10, segH / 2);
		VeloDraw.fillRounded(context, x0, y, colW, segH, barR, 0x1CFFFFFF);
		VeloDraw.strokeRounded(context, x0, y, colW, segH, barR, 0x2BFFFFFF);
		// every segment shows icon + label: widths follow the labels, extra room is shared out evenly, and when the bar
		// is too narrow (many mods' buttons, small windows) the labels shrink a little instead of disappearing
		int n = segments.size();
		int segIcon = large ? 11 : 9;
		int segPad = large ? 14 : 10;
		int[] natural = new int[n];
		int sum = 0;
		for (int i = 0; i < n; i++) {
			natural[i] = segIcon + 4 + MenuKit.width(renderer, deckLabel(segments.get(i).label())) + segPad;
			sum += natural[i];
		}
		int room = colW - 4;
		float textScale = sum <= room ? 1f : Math.max(0.7f, (room - n * (segIcon + 4 + segPad)) / (float) Math.max(1, sum - n * (segIcon + 4 + segPad)));
		int extra = sum <= room ? (room - sum) / Math.max(1, n) : 0;
		int sx = x0 + 2;
		for (int i = 0; i < n; i++) {
			Segment seg = segments.get(i);
			Text label = deckLabel(seg.label());
			int labelW = Math.round(MenuKit.width(renderer, label) * textScale);
			int sw = i == n - 1 ? x0 + colW - 2 - sx : (sum <= room ? natural[i] + extra : segIcon + 4 + labelW + segPad);
			deckPlace(seg.widget(), sx, y + 2, sw, segH - 4);
			boolean on = seg.widget().active && hovered(mouseX, mouseY, seg.widget());
			float hover = MenuKit.hover(seg.widget(), on);
			if (hover > 0.01f) {
				VeloDraw.fillRounded(context, sx, y + 2, sw, segH - 4, Math.max(2, barR - 2), (Math.round(hover * 0x26) << 24) | 0xFFFFFF);
			}
			int color = seg.widget().active ? VeloAnim.lerpArgb(deckText(0xFFE4E2E8), 0xFFFFFFFF, hover) : 0xFF5A5860;
			int cw = segIcon + 4 + labelW;
			int cx = sx + (sw - cw) / 2;
			MenuKit.icon(context, seg.icon(), cx, y + (segH - segIcon) / 2, segIcon, color);
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(cx + segIcon + 4, y + segH / 2f - 2.4f * textScale);
			context.getMatrices().scale(textScale, textScale);
			MenuKit.text(context, renderer, label, 0, 0, color);
			context.getMatrices().popMatrix();
			sx += sw;
		}
		context.getMatrices().popMatrix();
		y += segH + gap;

		// ---- Options | Quit ----
		ClickableWidget options = deckButton(byKey, "menu.options", essential);
		ClickableWidget quit = deckButton(byKey, "menu.quit", essential);
		slideIn(context, open, step++, "center");
		deckSmall(context, renderer, options, "Options", "sliders", x0, y, half, optH, false, mouseX, mouseY);
		deckSmall(context, renderer, quit, "Quit", "logout", x0 + colW - half, y, half, optH, true, mouseX, mouseY);
		context.getMatrices().popMatrix();
		y += optH + gap + 2;

		// ---- the small icon buttons ----
		int iconGap = large ? 6 : 5;
		int rowW = icons.size() * (iconS + iconGap) - iconGap;
		int ix = (width - Math.max(0, rowW)) / 2;
		slideIn(context, open, step++, "center");
		for (Icon icon : icons) {
			ClickableWidget widget = icon.widget();
			env.undraw(widget);
			if (widget instanceof HotspotButton hotspot) {
				hotspot.place(ix, y, iconS, iconS, true);
			}
			widget.setX(ix);
			widget.setY(y);
			widget.setWidth(iconS);
			widget.setHeight(iconS);
			float hover = MenuKit.hover(widget, hovered(mouseX, mouseY, widget));
			VeloDraw.fillCircle(context, ix + iconS / 2f, y + iconS / 2f, iconS / 2f, VeloAnim.lerpArgb(0x1CFFFFFF, 0x33FFFFFF, hover));
			VeloDraw.strokeArc(context, ix + iconS / 2f, y + iconS / 2f, iconS / 2f - 0.5f, 1f, 0f, 360f, VeloAnim.lerpArgb(0x2BFFFFFF, accent, hover));
			int inner = Math.round(iconS * 0.55f);
			MenuKit.icon(context, icon.key(), ix + (iconS - inner) / 2, y + (iconS - inner) / 2, inner, widget.active ? 0xFFE4E2E8 : 0xFF5A5860);
			ix += iconS + iconGap;
		}
		context.getMatrices().popMatrix();

		// ---- you + friends, bottom-left (the account switcher will open from here) ----
		String name = MenuInfo.username();
		String uuid = MenuInfo.uuid();
		int online = onlineFriends();
		Text nameText = MenuKit.uiBold(name == null || name.isEmpty() ? "Player" : name);
		Text friendsLine = MenuKit.ui(online < 0 ? "Velo Network offline" : online == 0 ? "No friends online"
				: online + (online == 1 ? " friend online" : " friends online"));
		int head = pillH - 10;
		int inset = (pillH - head) / 2;
		int textW = Math.max(MenuKit.width(renderer, nameText), MenuKit.width(renderer, friendsLine) + (online > 0 ? 6 : 0));
		int pillW = inset + head + 8 + textW + 24;
		int pxl = large ? 14 : 10;
		int pyl = height - pillH - (large ? 12 : 8);
		int pr = Math.min(9, pillH / 2 - 2);
		accountPill.place(pxl, pyl, pillW, pillH, true);
		friends.place(0, 0, 0, 0, false);
		deckAccountAnchor = new int[] {pxl, pyl};
		float fHover = MenuKit.hover(accountPill, hovered(mouseX, mouseY, accountPill));
		slideIn(context, open, step++, "center");
		VeloDraw.fillRounded(context, pxl, pyl, pillW, pillH, pr, VeloAnim.lerpArgb(0xB80C0B0F, 0xD0141318, fHover));
		VeloDraw.strokeRounded(context, pxl, pyl, pillW, pillH, pr, VeloAnim.lerpArgb(0x26FFFFFF, (accent & 0xFFFFFF) | 0x99000000, fHover));
		if (name != null && !name.isEmpty()) {
			PlayerHeads.draw(context, uuid, name, pxl + inset, pyl + inset, head);
		}
		int tx = pxl + inset + head + 8;
		int line = MenuKit.H_UI + 2;
		int tyl = deckTextY(pyl, pillH) - line / 2;
		MenuKit.text(context, renderer, nameText, tx, tyl, MenuKit.TEXT);
		if (online > 0) {
			VeloDraw.fillCircle(context, tx + 2, tyl + line + 2.6f, 2f, MenuKit.GREEN);
		}
		MenuKit.text(context, renderer, friendsLine, tx + (online > 0 ? 6 : 0), tyl + line, online > 0 ? 0xFFB8F0D2 : MenuKit.TEXT_3);
		MenuKit.icon(context, "d_chev_u", pxl + pillW - 9 - inset - 2, pyl + (pillH - 9) / 2, 9, 0xFFB8B6BE);
		context.getMatrices().popMatrix();

		// ---- versions, bottom-right (above vanilla's copyright line) ----
		String mc = FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		String version = VERSION.contains("+") ? VERSION.substring(0, VERSION.indexOf('+')) : VERSION;
		Text versions = MenuKit.mono("MINECRAFT " + mc + " · VELO " + version);
		MenuKit.text(context, renderer, versions, width - MenuKit.width(renderer, versions) - 3, height - 21, 0x99FFFFFF);

		storeCard.place(0, 0, 0, 0, false);
		if (essential) {
			anchorAroundForEssential(byKey, width, x0, x0 + colW);
		}
	}

	/** Singleplayer / Multiplayer: light glass, icon, bold label, a chevron at the end. */
	private static void deckBig(DrawContext context, TextRenderer renderer, ClickableWidget widget, String fallback, String icon, int x, int y, int w,
			int h, boolean large, int mouseX, int mouseY) {
		if (widget == null) {
			return;
		}
		deckPlace(widget, x, y, w, h);
		float hover = MenuKit.hover(widget, widget.active && hovered(mouseX, mouseY, widget));
		int r = Math.min(10, h / 2);
		VeloDraw.fillRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(0x2EFFFFFF, 0x45FFFFFF, hover));
		VeloDraw.strokeRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(0x40FFFFFF, MenuKit.accent(), hover));
		int is = large ? 12 : 10;
		int color = widget.active ? deckText(0xFFFFFFFF) : 0xFF6A6870;
		MenuKit.icon(context, icon, x + (large ? 12 : 9), y + (h - is) / 2, is, color);
		String words = widget instanceof HotspotButton ? fallback : widget.getMessage().getString();
		Text label = MenuKit.fit(renderer, deckLabel(words), w - (large ? 52 : 42));
		MenuKit.text(context, renderer, label, x + (large ? 12 : 9) + is + (large ? 8 : 6), deckTextY(y, h), color);
		int cs = large ? 9 : 8;
		MenuKit.icon(context, "d_chev_r", x + w - cs - (large ? 10 : 8) + Math.round(hover * 2), y + (h - cs) / 2, cs, (Math.round(0x99 + 0x66 * hover) << 24) | 0xFFFFFF);
	}

	/** Options / Quit: quieter glass, white words; Quit warms to red on hover. */
	private static void deckSmall(DrawContext context, TextRenderer renderer, ClickableWidget widget, String fallback, String icon, int x, int y, int w,
			int h, boolean quit, int mouseX, int mouseY) {
		if (widget == null) {
			return;
		}
		deckPlace(widget, x, y, w, h);
		float hover = MenuKit.hover(widget, widget.active && hovered(mouseX, mouseY, widget));
		int r = Math.min(10, h / 2);
		int hoverFill = quit ? 0x4DE0262E : 0x30FFFFFF;
		VeloDraw.fillRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(0x1CFFFFFF, hoverFill, hover));
		VeloDraw.strokeRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(0x2BFFFFFF, quit ? 0xCCE0262E : 0x66FFFFFF, hover));
		String words = widget instanceof HotspotButton ? fallback : widget.getMessage().getString().replace("...", "").replace("…", "").strip();
		Text label = MenuKit.fit(renderer, deckLabel(words), w - 30);
		int is = 9;
		int cw = is + 5 + MenuKit.width(renderer, label);
		int cx = x + (w - cw) / 2;
		MenuKit.icon(context, icon, cx, y + (h - is) / 2, is, deckText(0xFFFFFFFF));
		MenuKit.text(context, renderer, label, cx + is + 5, deckTextY(y, h), deckText(0xFFFFFFFF));
	}

	/** Where a part of the menu goes for the chosen alignment, plus its free offset (fractions of the screen). */
	private record Align(String mode, int left, int right, int width, int height) {
		int x(int w, ScreenLook.Title look, String part) {
			int base = switch (mode) {
				case "center" -> (width - w) / 2;
				case "right" -> right - w;
				default -> left;
			};
			return base + dx(look, part);
		}

		int dx(ScreenLook.Title look, String part) {
			return Math.round(look.dx(part) * width);
		}

		int dy(ScreenLook.Title look, String part) {
			return Math.round(look.dy(part) * height);
		}
	}

	/**
	 * Essential with the menu centred or on the right: the anchors span our whole menu, so Essential puts its character
	 * in the free space LEFT of it (its natural layout) and its side menu right of it. When the character doesn't fit
	 * there (or would cross the middle of the screen, where it stops taking clicks) the singleplayer anchor goes far
	 * left, which takes the character off-screen while the side menu stays.
	 */
	private static void anchorAroundForEssential(Map<String, ClickableWidget> byKey, int width, int left, int right) {
		ClickableWidget top = byKey.get("menu.singleplayer");
		EssentialAnchors.Plan plan = EssentialAnchors.around(width, left, right, top != null && byKey.get("menu.multiplayer") != null);
		for (Map.Entry<String, ClickableWidget> entry : byKey.entrySet()) {
			ClickableWidget widget = entry.getValue();
			RESTYLED.remove(widget);
			int[] first = FIRST_SEEN.get(widget);
			boolean offLeft = plan == null || widget == top && !plan.playerShown();
			widget.setX(offLeft ? EssentialAnchors.OFF_LEFT : plan.topX());
			widget.setWidth(offLeft ? EssentialAnchors.ANCHOR_W : plan.menuX() - plan.topX());
			if (first != null) {
				widget.setY(first[1]);
				widget.setHeight(first[3]);
			}
			widget.visible = false;
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

	// ---- Entrance ----

	private static Screen animatedFor;
	private static long openedAt;

	/** 0..1 since the title screen opened (a new screen instance restarts it; resizing doesn't). */
	private static float openProgress() {
		return Math.min(1f, (System.nanoTime() - openedAt) / 900_000_000f);
	}

	/** Pushes a slide-in transform for element {@code index}: each one comes in from the left a little after the last. */
	private static void slideIn(DrawContext context, float open, int index, String align) {
		float local = Math.max(0f, Math.min(1f, (open * 900f - index * 45f) / 380f));
		float eased = 1f - (float) Math.pow(1f - local, 3);
		context.getMatrices().pushMatrix();
		// from the side the menu sits on; a centred menu rises up
		switch (align) {
			case "right" -> context.getMatrices().translate(28f * (1f - eased), 0f);
			case "center" -> context.getMatrices().translate(0f, 16f * (1f - eased));
			default -> context.getMatrices().translate(-28f * (1f - eased), 0f);
		}
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

	private static void drawRow(DrawContext context, TextRenderer renderer, Row row, Text label, float labelScale, boolean customFont, int x, int y,
			int w, int h, boolean large, ScreenLook.Title look, float hover) {
		int own = look.color;
		int base = own >= 0 ? (row.primary() ? 0xFF000000 : row.quit() ? 0x99000000 : 0xCC000000) | own
				: row.primary() ? MenuKit.TEXT : row.quit() ? MenuKit.TEXT_3 : MenuKit.TEXT_2;
		if (!row.widget().active) {
			base = 0xFF4A4A52;
		}
		int color = VeloAnim.lerpArgb(base, look.hoverColor >= 0 ? 0xFF000000 | look.hoverColor : 0xFFFFFFFF, hover);
		int accent = MenuKit.accent();
		String style = look.buttons;
		String align = look.align;
		boolean boxed = !style.equals("words") && !style.equals("underline");
		int labelW = Math.round(MenuKit.width(renderer, label) * labelScale);
		int badgeW = row.badge() != null ? 34 : 0;
		// Where the capitals sit for each font (TTF glyphs don't start at the draw y).
		float fontH = customFont ? 8f * labelScale : (!row.main() ? MenuKit.H_UI : large ? 16 : MenuKit.H_DISPLAY) * labelScale;
		int ty = Math.round(y + (h - fontH) / 2f);

		// ---- the button behind the words ----
		int r = Math.min(h / 2, 8);
		switch (style) {
			case "pills" -> VeloDraw.fillRounded(context, x, y, w, h, h / 2, VeloAnim.lerpArgb(0x1CFFFFFF, (accent & 0xFFFFFF) | 0x66000000, hover));
			case "glass" -> {
				VeloDraw.fillRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(0xB80C0A0E, 0xD8141218, hover));
				VeloDraw.strokeRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(MenuKit.HAIRLINE, accent, hover));
			}
			case "outline" -> {
				if (hover > 0.01f) {
					VeloDraw.fillRounded(context, x, y, w, h, r, (Math.round(hover * 0x22) << 24) | (accent & 0xFFFFFF));
				}
				VeloDraw.strokeRounded(context, x, y, w, h, r, VeloAnim.lerpArgb(0x40FFFFFF, accent, hover));
			}
			default -> {
			}
		}

		// ---- the words ----
		int pad = boxed ? 14 : 0;
		int inner = w - pad * 2 - badgeW;
		int slide = boxed || align.equals("center") ? 0 : Math.round(hover * 8) * (align.equals("right") ? -1 : 1);
		int tx = switch (align) {
			case "center" -> x + pad + (inner - labelW) / 2;
			case "right" -> boxed ? x + w - pad - badgeW - labelW : x + w - 12 - labelW;
			default -> x + pad;
		} + slide;
		if (!boxed && !align.equals("center") && hover > 0.02f) {
			int arrowAlpha = Math.round(hover * 255);
			boolean right = align.equals("right");
			Text arrow = MenuKit.uiBold(right ? "‹" : "›");
			int ax = right ? tx + labelW + 4 : tx - 10;
			MenuKit.text(context, renderer, arrow, ax, ty + Math.round((fontH - MenuKit.H_UI) / 2f), (arrowAlpha << 24) | (MenuKit.accentText() & 0xFFFFFF));
		}
		int glowColor = look.accent >= 0 ? look.accent : accent & 0xFFFFFF;
		float glow = look.glow / 100f * (0.55f + 0.45f * hover);
		drawLabel(context, renderer, label, tx, ty, labelScale, color, look.shadow, glow, glowColor);
		if (style.equals("underline")) {
			int lw = Math.round(labelW * (0.25f + 0.75f * hover));
			int lx = align.equals("center") ? tx + (labelW - lw) / 2 : align.equals("right") ? tx + labelW - lw : tx;
			context.fill(lx, Math.round(ty + fontH + 2), lx + lw, Math.round(ty + fontH + 3), VeloAnim.lerpArgb((accent & 0xFFFFFF) | 0x66000000, accent, hover));
		}
		if (row.badge() != null) {
			Text badge = MenuKit.mono(row.badge());
			int bh = large ? 12 : 10;
			int bx = tx + labelW + 8;
			if (align.equals("right") && !boxed) {
				bx = tx - 8 - (MenuKit.width(renderer, badge) + 8);
			}
			int bw = MenuKit.width(renderer, badge) + 8;
			int by = Math.round(ty + (fontH - bh) / 2f);
			VeloDraw.fillRounded(context, bx, by, bw, bh, 3, accent);
			MenuKit.text(context, renderer, badge, bx + 4, by + (bh - MenuKit.H_MONO) / 2, MenuKit.onAccent());
		}
	}

	/** A label at {@code labelScale}, with an optional drop shadow and a soft glow (halo copies around it). */
	private static void drawLabel(DrawContext context, TextRenderer renderer, Text label, int x, int y, float labelScale, int color,
			boolean shadow, float glow, int glowRgb) {
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(labelScale, labelScale);
		if (glow > 0.01f) {
			int alpha = (int) (glow * 0x30) * ((color >>> 24) & 0xFF) / 255;
			if (alpha > 0) {
				for (int i = 0; i < 8; i++) {
					double a = i * Math.PI / 4;
					for (float rad : new float[] {1.2f, 2.4f}) {
						context.getMatrices().pushMatrix();
						context.getMatrices().translate((float) Math.cos(a) * rad / labelScale, (float) Math.sin(a) * rad / labelScale);
						MenuKit.text(context, renderer, label, 0, 0, (alpha << 24) | glowRgb);
						context.getMatrices().popMatrix();
					}
				}
			}
		}
		if (shadow) {
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(1f / labelScale * 1.2f, 1f / labelScale * 1.2f);
			MenuKit.text(context, renderer, label, 0, 0, (((color >>> 24) & 0xFF) * 0x90 / 255 << 24));
			context.getMatrices().popMatrix();
		}
		MenuKit.text(context, renderer, label, 0, 0, color);
		context.getMatrices().popMatrix();
	}

	private static void drawIconButton(DrawContext context, int x, int y, int size, String icon, float hover, boolean active) {
		VeloDraw.fillRounded(context, x, y, size, size, 5, VeloAnim.lerpArgb(0x14FFFFFF, 0x26FFFFFF, hover));
		VeloDraw.strokeRounded(context, x, y, size, size, 5, VeloAnim.lerpArgb(0x17FFFFFF, MenuKit.accent(), hover));
		int inner = Math.round(size * 0.55f);
		MenuKit.icon(context, icon, x + (size - inner) / 2, y + (size - inner) / 2, inner, active ? 0xFFC9C9D1 : 0xFF66666E);
	}

	/** Clock key of the title screen's emote figure. */
	private static final Object STORE_CARD_FIGURE = new Object();

	private static void drawStoreCard(DrawContext context, TextRenderer renderer, int x, int y, int w, int h, float hover) {
		MenuKit.panel(context, x, y, w, h, 8);
		if (hover > 0.01f) {
			VeloDraw.strokeRounded(context, x, y, w, h, 8, VeloAnim.lerpArgb(MenuKit.HAIRLINE, MenuKit.accent(), hover));
		}
		VeloDraw.fillRounded(context, x + 6, y + 6, h - 12, h - 12, 5, (MenuKit.accent() & 0xFFFFFF) | 0x2E000000);
		var emotes = net.veloclient.velo.client.emotes.EmoteCatalog.forSale();
		if (!emotes.isEmpty()) {
			// a little figure dancing through the emotes on sale, one after the other
			var emote = emotes.get((int) (System.currentTimeMillis() / 6000 % emotes.size()));
			context.enableScissor(x + 6, y + 6, x + h - 6, y + h - 6);
			net.veloclient.velo.client.gui.widget.EmoteFigure.draw(context, STORE_CARD_FIGURE, emote.id(), x + h / 2f, y + h - 7, (h - 14) / 34f,
					emote.accent());
			context.disableScissor();
			MenuKit.text(context, renderer, MenuKit.mono("NEW: EMOTES"), x + h + 2, y + 8, 0xFFBCA6FF);
			MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(emote.name() + " and more"), w - h - 8), x + h + 2, y + 18, MenuKit.TEXT);
			return;
		}
		MenuKit.icon(context, "store", x + 6 + (h - 12 - 12) / 2, y + 6 + (h - 12 - 12) / 2, 12, MenuKit.accentText());
		MenuKit.text(context, renderer, MenuKit.mono("NEW IN STORE"), x + h + 2, y + 8, 0xFFBCA6FF);
		String featured = net.veloclient.velo.client.cosmetics.wings.WingsManager.featured().map(c -> c.name() + " collection").orElse("Wings, cloaks and auras");
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.uiBold(featured), w - h - 8), x + h + 2, y + 18, MenuKit.TEXT);
	}
}
