package net.veloclient.velo.client.gui.store;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import net.veloclient.velo.client.cosmetics.wings.WingsPreview;
import net.veloclient.velo.client.economy.CurrencyManager;
import net.veloclient.velo.client.gui.VeloBadge;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.DrawnCard;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.StoreCard;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloStoreItemTile;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloWingCard;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreCategory;

import java.util.List;
import java.util.Locale;

/**
 * The Velo Store, laid out like the launcher's: the pages are tabs in the window header (Featured,
 * Wings, Capes, Auras, Emotes, Bundles, Velo+), your coins, "My Locker" (and for owners the Owner panel) sit
 * top right. Featured opens on the newest collection - its name, what it is, "Shop the collection" /
 * "Try on", you wearing its pairs on a turning 3D model with a thumbnail per pair - and under it, as in
 * the launcher: today's picks, bundles, Velo+ and coin packs, auras and capes (the part under the
 * collection scrolls; the 3D model stays put). Everything is bought with Velo Coins; the server checks
 * the price.
 */
public final class StoreScreen extends VeloWindow {

	private enum Page { FEATURED, WINGS, CAPES, AURAS, EMOTES, BUNDLES, PLUS }

	private static final String[] TABS = {"Featured", "Wings", "Capes", "Auras", "Emotes", "Bundles", "Velo+"};
	private static final int GAP = 10;
	private static final long HERO_SECONDS = 4;
	private static final int THUMB = 34;

	private static Page page = Page.FEATURED;

	/** Opens on the Velo+ page next time (the screenshot tour). */
	public static void showPlus() {
		page = Page.PLUS;
	}

	/** Opens on the Capes page next time (the screenshot tour). */
	public static void showCapes() {
		page = Page.CAPES;
	}

	/** Opens on the Bundles page next time (the screenshot tour). */
	public static void showBundles() {
		page = Page.BUNDLES;
	}

	/** Scrolls the page as the mouse wheel would (the screenshot tour). */
	public void scrollForTour(double amount) {
		if (region != null) {
			mouseScrolled(region.x() + 20, contentBottom() - 20, 0, amount);
		}
	}

	/** Opens the first bundle's page (the screenshot tour). */
	public void openFirstBundleForTour() {
		if (!StoreItems.bundles().isEmpty()) {
			this.client.setScreen(new BundleScreen(this, StoreItems.bundles().get(0)));
		}
	}

	/** Opens on the Emotes page next time. */
	public static void showEmotes() {
		page = Page.EMOTES;
	}

	/** Opens on the Auras page next time (the screenshot tour). */
	public static void showAuras() {
		page = Page.AURAS;
	}

	private VeloScrollRegion region;
	private int columns = 1;
	private int tileWidth;
	private int tileHeight;
	private final int heroKey = -500;
	private int heroH = 200;
	/** The scrolling part of Featured: section headings drawn at these page offsets. */
	private final List<Heading> headings = new java.util.ArrayList<>();

	private record Heading(int y, String title, String note) {
	}
	/** A pair picked from the hero's thumbnails (null = the hero cycles through the collection). */
	private String pinnedHero;
	/** The Discord link code just requested (copied as "/link CODE"), and why a request failed. */
	private String discordCode = "";
	private String discordError = "";
	// Header extras (coins pill, My Locker), measured when drawn.
	private int coinsX;
	private int coinsW;
	private int lockerX;
	private int lockerW;
	private int ownerX;
	private int ownerW;

	public StoreScreen(Screen parent) {
		super(Text.literal("Store"), 920, 580);
		returnTo(parent);
		WingsManager.load();
	}

	/** The featured collection's pairs (every pair if there are no collections). */
	private static List<WingsManager.WingType> featuredWings() {
		return WingsManager.featured().map(c -> WingsManager.inCollection(c.id())).filter(l -> !l.isEmpty()).orElseGet(WingsManager::forSale);
	}

	/** The pair the Featured hero shows right now (the picked one, else changes every few seconds). */
	private WingsManager.WingType heroWings() {
		List<WingsManager.WingType> all = featuredWings();
		if (all.isEmpty()) {
			return null;
		}
		if (pinnedHero != null) {
			for (WingsManager.WingType type : all) {
				if (type.id().equals(pinnedHero)) {
					return type;
				}
			}
		}
		return all.get((int) ((System.currentTimeMillis() / 1000 / HERO_SECONDS) % all.size()));
	}

	@Override
	protected boolean contentReady() {
		return StoreClient.catalogLoaded();
	}

	// ---- Header ----

	@Override
	protected String[] headerTabs() {
		return TABS;
	}

	@Override
	protected int headerTab() {
		return page.ordinal();
	}

	@Override
	protected void selectHeaderTab(int index) {
		page = Page.values()[index];
		layoutContent();
	}

	@Override
	protected String headerTabBadge(int index) {
		return index == Page.EMOTES.ordinal() ? "NEW" : null;
	}

	@Override
	protected void renderHeaderExtras(DrawContext context, int mouseX, int mouseY) {
		int h = 18;
		int y = windowY + (HEADER_HEIGHT - h) / 2;
		// Coins: gold pill with "+" (buys more).
		Text amount = MenuKit.uiBold(balanceText());
		Text plus = MenuKit.uiBold("+");
		coinsW = 12 + 10 + this.textRenderer.getWidth(amount) + 8 + this.textRenderer.getWidth(plus) + 8;
		coinsX = headerRight() - coinsW;
		boolean coinsHover = MenuKit.inside(mouseX, mouseY, coinsX, y, coinsW, h);
		VeloDraw.fillRounded(context, coinsX, y, coinsW, h, h / 2, coinsHover ? 0x33F7C04A : 0x1FF7C04A);
		VeloDraw.strokeRounded(context, coinsX, y, coinsW, h, h / 2, coinsHover ? 0xA0F7C04A : 0x59F7C04A);
		StoreCard.coin(context, coinsX + 11f, y + h / 2f, 5f);
		MenuKit.text(context, this.textRenderer, amount, coinsX + 22, y + (h - 8) / 2, 0xFFFFD56A);
		MenuKit.text(context, this.textRenderer, plus, coinsX + coinsW - 8 - this.textRenderer.getWidth(plus), y + (h - 8) / 2, MenuKit.accentText());
		// My Locker: where you equip what you own.
		Text locker = MenuKit.ui("My Locker");
		lockerW = 8 + 10 + 4 + this.textRenderer.getWidth(locker) + 8;
		lockerX = coinsX - 8 - lockerW;
		boolean lockerHover = MenuKit.inside(mouseX, mouseY, lockerX, y, lockerW, h);
		VeloDraw.fillRounded(context, lockerX, y, lockerW, h, 6, lockerHover ? 0x1AFFFFFF : 0x0DFFFFFF);
		VeloDraw.strokeRounded(context, lockerX, y, lockerW, h, 6, lockerHover ? 0x38FFFFFF : 0x17FFFFFF);
		MenuKit.icon(context, "cosmetics", lockerX + 8, y + 4, 10, lockerHover ? MenuKit.TEXT : MenuKit.TEXT_2);
		MenuKit.text(context, this.textRenderer, locker, lockerX + 22, y + (h - 8) / 2, lockerHover ? MenuKit.TEXT : MenuKit.TEXT_2);
		// Owner panel, for owners (the server checks every call anyway).
		ownerW = 0;
		if (StoreClient.isOwner()) {
			Text owner = MenuKit.ui("Owner panel");
			ownerW = 8 + 10 + 4 + this.textRenderer.getWidth(owner) + 8;
			ownerX = lockerX - 8 - ownerW;
			boolean ownerHover = MenuKit.inside(mouseX, mouseY, ownerX, y, ownerW, h);
			VeloDraw.fillRounded(context, ownerX, y, ownerW, h, 6, ownerHover ? 0x1AFFFFFF : 0x0DFFFFFF);
			VeloDraw.strokeRounded(context, ownerX, y, ownerW, h, 6, ownerHover ? 0x38FFFFFF : 0x17FFFFFF);
			MenuKit.icon(context, "settings", ownerX + 8, y + 4, 10, ownerHover ? MenuKit.TEXT : MenuKit.TEXT_2);
			MenuKit.text(context, this.textRenderer, owner, ownerX + 22, y + (h - 8) / 2, ownerHover ? MenuKit.TEXT : MenuKit.TEXT_2);
		}
	}

	@Override
	protected boolean headerClick(int mouseX, int mouseY) {
		int h = 18;
		int y = windowY + (HEADER_HEIGHT - h) / 2;
		if (MenuKit.inside(mouseX, mouseY, coinsX, y, coinsW, h)) {
			this.client.setScreen(new BuyCoinsScreen(this));
			return true;
		}
		if (MenuKit.inside(mouseX, mouseY, lockerX, y, lockerW, h)) {
			this.client.setScreen(new net.veloclient.velo.client.gui.CapeEquipScreen(this));
			return true;
		}
		if (ownerW > 0 && MenuKit.inside(mouseX, mouseY, ownerX, y, ownerW, h)) {
			this.client.setScreen(new OwnerScreen(this));
			return true;
		}
		return false;
	}

	// ---- Layout ----

	@Override
	protected void layoutContent() {
		this.clearChildren();
		region = null;
		StoreClient.refresh(false, null);
		int cx = contentX();
		int y = contentY();
		int cw = contentWidth();
		switch (page) {
			case FEATURED -> layoutFeatured(cx, y, cw);
			case WINGS -> layoutGrid(cx, y, cw, () -> {
				for (WingsManager.WingType type : WingsManager.forSale()) {
					VeloWingCard card = new VeloWingCard(tileWidth, tileHeight, type, () -> this.client.setScreen(new WingDetailScreen(this, type)));
					addSelectableChild(card);
					region.addRow(card);
				}
			}, 150);
			case CAPES -> layoutCapes(cx, y, cw);
			case AURAS -> layoutGrid(cx, y, cw, () -> {
				// What's for sale, plus any you own that aren't (testing / limited ones you were given).
				for (var type : net.veloclient.velo.client.cosmetics.aura.AuraManager.catalog()) {
					if (!type.inStore() && !net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(type.id())) {
						continue;
					}
					var card = new net.veloclient.velo.client.gui.widget.VeloAuraCard(tileWidth, tileHeight, type,
							() -> this.client.setScreen(new AuraDetailScreen(this, type)));
					addSelectableChild(card);
					region.addRow(card);
				}
			}, 150);
			case EMOTES -> layoutGrid(cx, y, cw, () -> {
				// What's for sale, plus any you own that aren't (testing / limited ones you were given).
				for (var type : net.veloclient.velo.client.emotes.EmoteCatalog.catalog()) {
					if (!type.inStore() && !net.veloclient.velo.client.emotes.EmoteCatalog.owns(type.id())) {
						continue;
					}
					var card = new net.veloclient.velo.client.gui.widget.VeloEmoteCard(tileWidth, tileHeight, type,
							() -> this.client.setScreen(new EmoteDetailScreen(this, type)));
					addSelectableChild(card);
					region.addRow(card);
				}
			}, 150);
			case BUNDLES -> layoutBundles(cx, y, cw);
			case PLUS -> layoutPlus(cx, y, cw);
		}
	}

	/** Hero card geometry (shared by layout and render). */
	private int previewW(int cw) {
		return Math.min(300, cw * 34 / 100);
	}

	private int thumbsX(int cx, int cw) {
		return cx + cw - 14 - THUMB;
	}

	private int previewX(int cx, int cw) {
		return thumbsX(cx, cw) - 10 - previewW(cw);
	}

	private void layoutFeatured(int cx, int y, int cw) {
		int available = contentBottom() - y;
		heroH = Math.max(130, Math.min(200, available * 2 / 5));
		addDrawableChild(new EntityPreviewWidget(previewX(cx, cw), y + 6, previewW(cw), heroH - 12)
				.wings(() -> {
					WingsManager.WingType type = heroWings();
					return type == null ? null
							: new WingsPreview.Tag(new WingsManager.Worn(type.id(), WingsManager.settings(type.id())), WingsPreview.Showcase.FLY, heroKey);
				}).spin(20f, 150f).noStage()
				.fallbackImage(() -> heroWings() == null ? null : VeloWingCard.previewImage(heroWings().id())));
		int tx = cx + 18;
		int buttonsY = heroButtonsY(y, cx, cw);
		Text shop = Text.literal("Shop the collection");
		int shopW = this.textRenderer.getWidth(shop) + 28;
		VeloButton browse = new VeloButton(tx, buttonsY, shopW, 20, shop, b -> {
			page = Page.WINGS;
			restartPageIn();
			layoutContent();
		});
		browse.primary();
		addDrawableChild(browse);
		Text tryOn = Text.literal("Try on");
		addDrawableChild(new VeloButton(tx + shopW + 6, buttonsY, this.textRenderer.getWidth(tryOn) + 26, 20, tryOn, b -> {
			WingsManager.WingType type = heroWings();
			if (type != null) {
				this.client.setScreen(new WingDetailScreen(this, type));
			}
		}));
		layoutFeaturedPage(cx, y + heroH + 12, cw);
	}

	/**
	 * Everything under the collection, as in the launcher's Featured: Daily picks, Bundles, Velo+ next to the coin
	 * packs, Auras, Capes. Tiles are real cards; banners are {@link DrawnCard}s; headings are drawn at their spot.
	 */
	private void layoutFeaturedPage(int cx, int top, int cw) {
		headings.clear();
		region = new VeloScrollRegion(cx, top, cw, contentBottom() - top);
		int usable = region.viewportWidth();
		List<int[]> spots = new java.util.ArrayList<>();
		// A little air at the top: the display font's caps reach above the line they're drawn at.
		int y = 6;
		int headH = 18;

		// Daily picks
		List<String> picks = StoreItems.dailyPicks();
		if (!picks.isEmpty()) {
			headings.add(new Heading(y, "Daily picks", "refreshes in " + StoreItems.countdown(StoreItems.dailyEndsAt())));
			y += headH;
			int n = 4;
			int w = (usable - (n - 1) * GAP) / n;
			int h = w * 3 / 4 + StoreCard.INFO_H;
			for (int i = 0; i < picks.size() && i < n; i++) {
				var card = StoreItems.card(this, picks.get(i), w, h);
				if (card != null) {
					addRegionRow(card, spots, cx + i * (w + GAP), y);
				}
			}
			y += h + 18;
		}

		// Bundles
		List<StoreClient.Bundle> bundles = StoreItems.bundles();
		if (!bundles.isEmpty()) {
			headings.add(new Heading(y, "Bundles", "Whole sets for less"));
			addRegionRow(link("All bundles", Page.BUNDLES), spots, cx + usable - linkWidth("All bundles"), y);
			y += headH;
			int n = Math.min(3, bundles.size());
			int w = (usable - 2 * GAP) / 3;
			for (int i = 0; i < n; i++) {
				addRegionRow(bundleCard(bundles.get(i), w, BUNDLE_H), spots, cx + i * (w + GAP), y);
			}
			y += BUNDLE_H + 18;
		}

		// Velo+ and coins
		boolean coinsFit = usable >= 480;
		int coinsW = coinsFit ? Math.min(250, usable * 2 / 5) : 0;
		int plusW = usable - (coinsFit ? coinsW + GAP : 0);
		addRegionRow(plusBanner(plusW, PLUS_H), spots, cx, y);
		if (coinsFit) {
			addRegionRow(coinsCard(coinsW, PLUS_H), spots, cx + plusW + GAP, y);
		}
		y += PLUS_H + 18;

		// Auras and capes: one row each, with a link to all of them.
		int columns = Math.max(3, Math.min(6, (usable + GAP) / (120 + GAP)));
		int w = (usable - (columns - 1) * GAP) / columns;
		List<net.veloclient.velo.client.cosmetics.aura.AuraManager.AuraType> auras = net.veloclient.velo.client.cosmetics.aura.AuraManager.forSale();
		if (!auras.isEmpty()) {
			headings.add(new Heading(y, "Auras", "Animated effects around you"));
			addRegionRow(link("All auras", Page.AURAS), spots, cx + usable - linkWidth("All auras"), y);
			y += headH;
			int h = w * 5 / 4 + 10;
			for (int i = 0; i < auras.size() && i < columns; i++) {
				var type = auras.get(i);
				addRegionRow(new net.veloclient.velo.client.gui.widget.VeloAuraCard(w, h, type,
						() -> this.client.setScreen(new AuraDetailScreen(this, type))), spots, cx + i * (w + GAP), y);
			}
			y += h + 18;
		}
		List<net.veloclient.velo.client.emotes.EmoteCatalog.EmoteType> emotes = net.veloclient.velo.client.emotes.EmoteCatalog.forSale();
		if (!emotes.isEmpty()) {
			headings.add(new Heading(y, "Emotes", "Hold B in game - with particles and sounds"));
			addRegionRow(link("All emotes", Page.EMOTES), spots, cx + usable - linkWidth("All emotes"), y);
			y += headH;
			int h = w * 5 / 4 + 10;
			for (int i = 0; i < emotes.size() && i < columns; i++) {
				var type = emotes.get(i);
				addRegionRow(new net.veloclient.velo.client.gui.widget.VeloEmoteCard(w, h, type,
						() -> this.client.setScreen(new EmoteDetailScreen(this, type))), spots, cx + i * (w + GAP), y);
			}
			y += h + 18;
		}
		List<net.veloclient.velo.client.store.StoreItem> capes = StoreCatalog.byCategory(StoreCategory.CAPES).stream()
				.filter(net.veloclient.velo.client.store.StoreItem::inStore).toList();
		if (!capes.isEmpty()) {
			headings.add(new Heading(y, "Animated capes", null));
			addRegionRow(link("All capes", Page.CAPES), spots, cx + usable - linkWidth("All capes"), y);
			y += headH;
			int icon = w * 6 / 5 - StoreCard.INFO_H + 20;
			for (int i = 0; i < capes.size() && i < columns; i++) {
				var item = capes.get(i);
				addRegionRow(new VeloStoreItemTile(0, 0, w, icon, item, () -> this.client.setScreen(new StoreItemDetailScreen(this, item))),
						spots, cx + i * (w + GAP), y);
			}
			y += icon + StoreCard.INFO_H + 8;
		}
		pageSpots = spots.toArray(new int[0][]);
		pageHeight = y;
		region.layoutPositioned(pageSpots, pageHeight);
	}

	private static final int BUNDLE_H = 74;
	private static final int PLUS_H = 64;
	private int[][] pageSpots;
	private int pageHeight;

	private void addRegionRow(net.minecraft.client.gui.widget.ClickableWidget widget, List<int[]> spots, int x, int y) {
		addSelectableChild(widget);
		region.addRow(widget);
		spots.add(new int[] {x, y});
	}

	private int linkWidth(String label) {
		return this.textRenderer.getWidth(MenuKit.uiBold(label + "  ›")) + 4;
	}

	/** "All auras  ›": switches tab. */
	private DrawnCard link(String label, Page target) {
		Text text = MenuKit.uiBold(label + "  ›");
		return new DrawnCard(linkWidth(label), 12, label, (context, x, y, w, h, hover) ->
				MenuKit.text(context, this.textRenderer, text, x, y + 2, VeloAnim.lerpArgb(MenuKit.accentText(), 0xFFFFFFFF, hover)), () -> {
			page = target;
			restartPageIn();
			layoutContent();
		});
	}

	private DrawnCard bundleCard(StoreClient.Bundle bundle, int width, int height) {
		return new DrawnCard(width, height, bundle.name(), (context, x, y, w, h, hover) -> {
			int lift = Math.round(2 * hover);
			int accent = StoreItems.accent(bundle.items().isEmpty() ? "" : bundle.items().get(0));
			if (hover > 0.01f) {
				VeloDraw.shadow(context, x, y - lift, w, h, 8, 12, 5, VeloUi.withAlpha(0xFF000000, Math.round(0x90 * hover)));
			}
			VeloDraw.fillRounded(context, x, y - lift, w, h, 8, 0xFF15151B);
			VeloDraw.fillRoundedGradient(context, x, y - lift, w, h, 8, VeloUi.withAlpha(accent, 0x22 + Math.round(0x14 * hover)), 0x00191920);
			VeloDraw.strokeRounded(context, x, y - lift, w, h, 8, VeloAnim.lerpArgb(0x17FFFFFF, 0x40FFFFFF, hover));
			paintBundle(context, bundle, x, y - lift, w, h);
		}, () -> this.client.setScreen(new BundleScreen(this, bundle)));
	}

	/** Bundle card: pictures of what's in it on the left, name, count and price on the right. */
	private void paintBundle(DrawContext context, StoreClient.Bundle bundle, int x, int y, int w, int h) {
		int size = Math.min(26, (h - 20) / 2);
		int shown = Math.min(4, bundle.items().size());
		for (int i = 0; i < shown; i++) {
			StoreItems.drawArt(context, bundle.items().get(i), x + 8 + i % 2 * (size + 4), y + 8 + i / 2 * (size + 4), size);
		}
		int tx = x + 8 + 2 * (size + 4) + 6;
		int tw = x + w - 8 - tx;
		MenuKit.text(context, this.textRenderer, MenuKit.small(bundle.items().size() + " ITEMS"), tx, y + 9, MenuKit.accentText());
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(bundle.name()), tw), tx, y + 19, MenuKit.TEXT);
		int owned = StoreItems.ownedCount(bundle);
		int py = y + h - 18;
		if (owned == bundle.items().size()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Owned"), tx, py, MenuKit.GREEN);
			return;
		}
		int separate = StoreItems.separatePrice(bundle);
		int price = bundle.price() >= 0 ? bundle.price() : separate;
		StoreCard.coin(context, tx + 4f, py + 3.5f, 3.6f);
		Text p = MenuKit.uiBold(String.format(Locale.ROOT, "%,d", price));
		MenuKit.text(context, this.textRenderer, p, tx + 10, py, 0xFFFFD56A);
		if (bundle.price() >= 0 && separate > bundle.price()) {
			int sx = tx + 10 + this.textRenderer.getWidth(p) + 6;
			Text save = MenuKit.small("-" + Math.round(100.0 * (separate - bundle.price()) / separate) + "%");
			int sw = this.textRenderer.getWidth(save) + 8;
			VeloDraw.fillRounded(context, sx, py - 1, sw, 10, 3, 0x333DDC97);
			MenuKit.text(context, this.textRenderer, save, sx + 4, py + 1, MenuKit.GREEN);
		}
	}

	/** The Velo+ pitch, as in the launcher: badge, one line of what you get, "Get Velo+". */
	private DrawnCard plusBanner(int width, int height) {
		return new DrawnCard(width, height, "Velo+", (context, x, y, w, h, hover) -> {
			VeloDraw.fillRounded(context, x, y, w, h, 9, 0xFF141013);
			VeloDraw.fillRoundedGradient(context, x, y, w, h, 9, VeloAnim.lerpArgb(0x40FF2D3C, 0x5CFF2D3C, hover), 0x00141013);
			VeloDraw.strokeRounded(context, x, y, w, h, 9, VeloAnim.lerpArgb(0x59FF2D3C, 0xB0FF2D3C, hover));
			int logo = h - 28;
			MenuKit.logo(context, x + 14, y + (h - logo) / 2, logo);
			VeloDraw.fillRounded(context, x + 14 + logo - 9, y + (h + logo) / 2 - 11, 12, 12, 4, 0xFFFF2D3C);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold("+"), x + 14 + logo - 6, y + (h + logo) / 2 - 9, 0xFFFFFFFF);
			int tx = x + 14 + logo + 14;
			Text button = MenuKit.uiBold(StoreClient.plus() ? "You're Velo+" : "Get Velo+");
			int bw = this.textRenderer.getWidth(button) + 22;
			int bx = x + w - 12 - bw;
			int tw = bx - 10 - tx;
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.display("World Hosting, Cloud Sync, Clips Pro"), tw),
					tx, y + h / 2 - 11, MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui("Monthly coins, the + badge and more. Cancel any time."), tw),
					tx, y + h / 2 + 4, MenuKit.TEXT_2);
			VeloDraw.fillRounded(context, bx, y + (h - 20) / 2, bw, 20, 6, StoreClient.plus() ? 0x263DDC97 : VeloAnim.lerpArgb(MenuKit.accent(), 0xFFFFFFFF, 0.1f * hover));
			MenuKit.text(context, this.textRenderer, button, bx + 11, y + (h - 8) / 2, StoreClient.plus() ? MenuKit.GREEN : MenuKit.onAccent());
		}, () -> {
			page = Page.PLUS;
			restartPageIn();
			layoutContent();
		});
	}

	/** Velo Coins: the first three packs (most bonus highlighted); opens the coin page. */
	private DrawnCard coinsCard(int width, int height) {
		return new DrawnCard(width, height, "Velo Coins", (context, x, y, w, h, hover) -> {
			MenuKit.panel(context, x, y, w, h, 9);
			if (hover > 0.01f) {
				VeloDraw.strokeRounded(context, x, y, w, h, 9, VeloAnim.lerpArgb(MenuKit.HAIRLINE, 0xA0F7C04A, hover));
			}
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Velo Coins"), x + 10, y + 8, MenuKit.TEXT);
			Text free = MenuKit.ui("Free coins  ›");
			MenuKit.text(context, this.textRenderer, free, x + w - 10 - this.textRenderer.getWidth(free), y + 8, MenuKit.accentText());
			List<StoreClient.CoinPack> packs = StoreClient.packs();
			if (packs.isEmpty()) {
				MenuKit.text(context, this.textRenderer, MenuKit.ui("Sign in to buy coins."), x + 10, y + 30, MenuKit.TEXT_3);
				return;
			}
			int n = Math.min(3, packs.size());
			int best = 0;
			for (int i = 0; i < n; i++) {
				if (packs.get(i).bonus() > packs.get(best).bonus()) {
					best = i;
				}
			}
			int pw = (w - 20 - (n - 1) * 6) / n;
			for (int i = 0; i < n; i++) {
				StoreClient.CoinPack pack = packs.get(i);
				int px = x + 10 + i * (pw + 6);
				int py = y + 24;
				VeloDraw.fillRounded(context, px, py, pw, h - 32, 6, i == best ? 0x1FF7C04A : 0x0DFFFFFF);
				VeloDraw.strokeRounded(context, px, py, pw, h - 32, 6, i == best ? 0x80F7C04A : 0x17FFFFFF);
				StoreCard.coin(context, px + 9f, py + 9.5f, 3.6f);
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(String.format(Locale.ROOT, "%,d", pack.total())), pw - 18),
						px + 15, py + 6, 0xFFFFD56A);
				MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer,
						MenuKit.ui(String.format(Locale.ROOT, "%.2f %s", pack.price(), StoreClient.currency())), pw - 10), px + 6, py + 18, MenuKit.TEXT_2);
			}
		}, () -> this.client.setScreen(new BuyCoinsScreen(this)));
	}

	/** Bundles tab: every bundle, two to a row. */
	private void layoutBundles(int cx, int y, int cw) {
		headings.clear();
		region = new VeloScrollRegion(cx, y, cw, contentBottom() - y);
		int usable = region.viewportWidth();
		List<StoreClient.Bundle> bundles = StoreItems.bundles();
		int columns = usable >= 560 ? 3 : 2;
		int w = (usable - (columns - 1) * GAP) / columns;
		int h = BUNDLE_H + 16;
		List<int[]> spots = new java.util.ArrayList<>();
		for (int i = 0; i < bundles.size(); i++) {
			addRegionRow(bundleCard(bundles.get(i), w, h), spots, cx + i % columns * (w + GAP), i / columns * (h + GAP));
		}
		pageSpots = spots.toArray(new int[0][]);
		pageHeight = bundles.isEmpty() ? 0 : (bundles.size() + columns - 1) / columns * (h + GAP);
		region.layoutPositioned(pageSpots, pageHeight);
	}

	/** Where the hero's buttons go: right under its description (the text block is centred in the hero). */
	private int heroButtonsY(int y, int cx, int cw) {
		return y + heroH / 2 + heroTextHeight(cx, cw) / 2 - 20;
	}

	/** Tag, name, description and buttons stacked. */
	private int heroTextHeight(int cx, int cw) {
		return 11 + 9 + heroTitleHeight(cx, cw) + 10 + heroLines(cx, cw).size() * 11 + 12 + 20;
	}

	private int heroTextWidth(int cx, int cw) {
		return Math.min(previewX(cx, cw) - 12 - (cx + 18), 360);
	}

	private boolean heroTitleLarge(int cx, int cw) {
		WingsManager.Collection collection = WingsManager.featured().orElse(null);
		return this.textRenderer.getWidth(MenuKit.hero(collection == null ? "Wings" : collection.name())) <= heroTextWidth(cx, cw);
	}

	private int heroTitleHeight(int cx, int cw) {
		return heroTitleLarge(cx, cw) ? MenuKit.H_HERO : MenuKit.H_DISPLAY_LG;
	}

	/** The description, at most as many lines as fit (three on a roomy hero). */
	private List<Text> heroLines(int cx, int cw) {
		WingsManager.Collection collection = WingsManager.featured().orElse(null);
		List<Text> lines = MenuKit.wrap(this.textRenderer, (collection == null ? "" : collection.description() + " ") + "Tune each pair's size and flap to your style.",
				MenuKit::ui, heroTextWidth(cx, cw));
		int room = (heroH - 36 - (11 + 9 + heroTitleHeight(cx, cw) + 10 + 12 + 20)) / 11;
		int shown = Math.max(0, Math.min(Math.min(3, room), lines.size()));
		List<Text> out = new java.util.ArrayList<>(lines.subList(0, shown));
		if (shown > 0 && shown < lines.size()) {
			// Cut short: end on an ellipsis instead of mid-sentence.
			out.set(shown - 1, MenuKit.fit(this.textRenderer, MenuKit.ui(out.get(shown - 1).getString() + " " + lines.get(shown).getString()),
					heroTextWidth(cx, cw)));
		}
		return out;
	}

	private void layoutGrid(int cx, int y, int cw, Runnable fill, int minTile) {
		columns = Math.max(2, Math.min(5, (cw + GAP) / (minTile + GAP)));
		tileWidth = (cw - 8 - (columns - 1) * GAP) / columns;
		tileHeight = tileWidth * 5 / 4 + 10;
		region = new VeloScrollRegion(cx, y, cw, contentBottom() - y);
		fill.run();
		region.layoutGrid(columns, tileWidth, tileHeight, GAP);
	}

	private static final int CAPE_BANNER_H = 34;

	private void layoutCapes(int cx, int y, int cw) {
		// The Velo Neon cape isn't sold: it comes from linking your Discord.
		Text label = Text.literal(discordCode.isEmpty() ? "Link Discord" : "Copied /link " + discordCode);
		int bw = this.textRenderer.getWidth(label) + 26;
		VeloButton link = new VeloButton(cx + cw - 8 - bw, y + (CAPE_BANNER_H - 20) / 2, bw, 20, label,
				b -> StoreClient.discordCode(code -> {
					String value = code.get("code").getAsString();
					MinecraftClient.getInstance().keyboard.setClipboard("/link " + value);
					discordCode = value;
					discordError = "";
					layoutContent();
				}, error -> {
					discordError = error;
					layoutContent();
				}));
		addDrawableChild(link);
		int gy = y + CAPE_BANNER_H + GAP;
		columns = Math.max(2, Math.min(6, (cw + GAP) / (118 + GAP)));
		tileWidth = (cw - 8 - (columns - 1) * GAP) / columns;
		int icon = tileWidth * 6 / 5 - StoreCard.INFO_H + 20;
		tileHeight = icon + StoreCard.INFO_H;
		region = new VeloScrollRegion(cx, gy, cw, contentBottom() - gy);
		for (var item : StoreCatalog.byCategory(StoreCategory.CAPES)) {
			VeloStoreItemTile tile = new VeloStoreItemTile(0, 0, tileWidth, icon, item,
					() -> this.client.setScreen(new StoreItemDetailScreen(this, item)));
			addSelectableChild(tile);
			region.addRow(tile);
		}
		region.layoutGrid(columns, tileWidth, tileHeight, GAP);
	}

	/** What Velo+ includes - shown on its page (and in the launcher's Store). */
	private static final String[] PLUS_PERKS = {
			"World Hosting: play your world with friends from anywhere, no port forwarding - invite, share a code or go public",
			"Cloud Sync: settings, HUD layouts, profiles, waypoints and keybinds on every PC, with restore points",
			"Clips Pro: a 2-5 minute replay buffer at 1080p60 with sound, auto clips on kills and wins, share links (60 days) and sending to friends",
			"Name Styles: animated gradients, rainbow, glitch and more on your name - in chat, tab list and above your head",
			"Animated custom capes: import your own GIF cape and every Velo player sees it move",
			"Home background: your own picture or animated GIF on the launcher's Home screen instead of the island",
			"%,d Velo Coins every month - spend them on wings, capes and auras",
			"The Velo+ badge and the VIP role in the Velo Discord"};

	private void layoutPlus(int cx, int y, int cw) {
		boolean member = StoreClient.plus();
		boolean trial = !member && StoreClient.plusTrialAvailable();
		Text label = Text.literal(member ? "You're Velo+" : trial ? "Start " + StoreClient.plusTrialDays() + "-day free trial"
				: String.format(Locale.ROOT, "Subscribe  ·  %.2f %s / month", StoreClient.plusPrice(), StoreClient.currency()));
		int buttonW = Math.min(cw - 36, this.textRenderer.getWidth(label) + 34);
		VeloButton plusButton = new VeloButton(cx + 18, contentBottom() - 18 - 22, buttonW, 22, label, b -> {
			if (member) {
				return;
			}
			if (trial) {
				// Free once per account that never had Velo+ - no payment page.
				StoreClient.plusTrial(this::layoutContent, error -> {
					discordError = error;
					layoutContent();
				});
				return;
			}
			StoreClient.plusCheckout(VeloUi::openUrl, error -> {
				discordError = error;
				layoutContent();
			});
		});
		if (!member) {
			plusButton.primary();
		}
		addDrawableChild(plusButton);
		if (!member && StoreClient.plusYearlyPrice() > 0) {
			Text yearLabel = Text.literal(String.format(Locale.ROOT, "Yearly  ·  %.2f %s", StoreClient.plusYearlyPrice(), StoreClient.currency()));
			int yearW = Math.min(cw - 36 - buttonW - 6, this.textRenderer.getWidth(yearLabel) + 24);
			if (yearW > 60) {
				addDrawableChild(new VeloButton(cx + 18 + buttonW + 6, contentBottom() - 18 - 22, yearW, 22, yearLabel,
						b -> StoreClient.plusCheckout(true, VeloUi::openUrl, error -> {
							discordError = error;
							layoutContent();
						})));
			}
		}
	}

	// ---- Rendering ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		int cx = contentX();
		int cw = contentWidth();
		int y = contentY();
		switch (page) {
			case FEATURED -> renderHeroBackground(context, cx, y, cw);
			case BUNDLES -> {
				if (StoreItems.bundles().isEmpty()) {
					MenuKit.text(context, this.textRenderer, MenuKit.ui("No bundles right now."), cx, y + 4, MenuKit.TEXT_3);
				}
			}
			case CAPES -> renderCapesBanner(context, cx, y, cw);
			case PLUS -> renderPlus(context, cx, y, cw);
			default -> {
			}
		}
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (page == Page.FEATURED) {
			renderHero(context, contentX(), contentY(), contentWidth(), mouseX, mouseY);
		}
		if (region != null && (page == Page.FEATURED || page == Page.BUNDLES)) {
			region.renderRows(context, mouseX, mouseY, delta);
			region.renderScissorStart(context);
			int top = region.pageTop();
			for (Heading heading : headings) {
				Text title = MenuKit.display(heading.title());
				MenuKit.text(context, this.textRenderer, title, contentX(), top + heading.y(), MenuKit.TEXT);
				if (heading.note() != null) {
					MenuKit.text(context, this.textRenderer, MenuKit.ui(heading.note()), contentX() + this.textRenderer.getWidth(title) + 8, top + heading.y() + 1,
							MenuKit.TEXT_3);
				}
			}
			region.renderScissorEnd(context);
			region.renderScrollbarPage(context);
		} else if (region != null) {
			region.renderRows(context, mouseX, mouseY, delta);
			region.renderScrollbarGrid(context, columns, tileHeight, GAP);
		}
	}

	private void renderHeroBackground(DrawContext context, int cx, int y, int cw) {
		WingsManager.WingType type = heroWings();
		int accent = type == null ? MenuKit.accent() : VeloWingCard.accentFor(type.id());
		VeloDraw.fillRounded(context, cx, y, cw, heroH, 10, 0xFF131318);
		VeloDraw.fillRoundedGradient(context, cx, y, cw, heroH, 10, VeloUi.withAlpha(accent, 0x26), 0x00131318);
		VeloDraw.strokeRounded(context, cx, y, cw, heroH, 10, 0x17FFFFFF);
	}

	private void renderHero(DrawContext context, int cx, int y, int cw, int mouseX, int mouseY) {
		WingsManager.WingType type = heroWings();
		int accent = type == null ? MenuKit.accent() : VeloWingCard.accentFor(type.id());
		int accentText = VeloAnim.lerpArgb(accent | 0xFF000000, 0xFFFFFFFF, 0.3f);
		int tx = cx + 18;
		int tw = heroTextWidth(cx, cw);
		// The text block (tag, name, description, buttons) sits centred in the hero.
		int ty = heroButtonsY(y, cx, cw) + 20 - heroTextHeight(cx, cw);
		WingsManager.Collection collection = WingsManager.featured().orElse(null);
		Long until = type == null ? null : type.availableUntil();
		// Tag + how long a limited drop stays.
		Text tag = MenuKit.small((collection == null ? "New collection" : collection.tag()).toUpperCase(Locale.ROOT));
		int tagW = this.textRenderer.getWidth(tag) + 10;
		VeloDraw.fillRounded(context, tx, ty, tagW, 11, 3, VeloUi.withAlpha(accent, 0x40));
		MenuKit.text(context, this.textRenderer, tag, tx + 5, ty + 3, accentText);
		if (until != null) {
			Text ends = MenuKit.mono("Ends in " + StoreItems.countdown(until));
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, ends, tw - tagW - 8), tx + tagW + 8, ty + 3, MenuKit.TEXT_2);
		}
		// The collection's name, as big as fits.
		String name = collection == null ? "Wings" : collection.name();
		Text title = heroTitleLarge(cx, cw) ? MenuKit.hero(name) : MenuKit.displayLg(name);
		int titleY = ty + 11 + 9;
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, title, tw), tx, titleY, MenuKit.TEXT);
		int textTop = titleY + heroTitleHeight(cx, cw) + 10;
		List<Text> lines = heroLines(cx, cw);
		for (int i = 0; i < lines.size(); i++) {
			MenuKit.text(context, this.textRenderer, lines.get(i), tx, textTop + i * 11, MenuKit.TEXT_2);
		}
		int buttonsY = heroButtonsY(y, cx, cw);
		// "from (coin) price" after the buttons.
		List<WingsManager.WingType> all = featuredWings();
		int from = Integer.MAX_VALUE;
		for (WingsManager.WingType pair : all) {
			from = Math.min(from, StoreClient.price(pair.itemId(), pair.price()));
		}
		if (from != Integer.MAX_VALUE) {
			int fx = tx + this.textRenderer.getWidth(Text.literal("Shop the collection")) + 28 + 6 + this.textRenderer.getWidth(Text.literal("Try on")) + 26 + 12;
			int fy = buttonsY + 6;
			Text fromLabel = MenuKit.ui("from");
			if (fx + 60 < previewX(cx, cw)) {
				MenuKit.text(context, this.textRenderer, fromLabel, fx, fy, MenuKit.TEXT_3);
				int coinX = fx + this.textRenderer.getWidth(fromLabel) + 9;
				StoreCard.coin(context, coinX, fy + 3.5f, 4f);
				MenuKit.text(context, this.textRenderer, MenuKit.uiBold(String.format(Locale.ROOT, "%,d", from)), coinX + 7, fy, 0xFFFFD56A);
			}
		}
		// A thumbnail per pair: click to show it.
		int thumbX = thumbsX(cx, cw);
		int total = all.size() * THUMB + Math.max(0, all.size() - 1) * 8;
		int thumbY = y + (heroH - total) / 2;
		for (int i = 0; i < all.size(); i++) {
			WingsManager.WingType pair = all.get(i);
			int ty2 = thumbY + i * (THUMB + 8);
			boolean on = type != null && pair.id().equals(type.id());
			boolean hovered = MenuKit.inside(mouseX, mouseY, thumbX, ty2, THUMB, THUMB);
			VeloDraw.fillRounded(context, thumbX, ty2, THUMB, THUMB, 6, on ? 0x1FFFFFFF : hovered ? 0x14FFFFFF : 0x0AFFFFFF);
			VeloDraw.strokeRounded(context, thumbX, ty2, THUMB, THUMB, 6, on ? 0xD0FFFFFF : hovered ? 0x40FFFFFF : 0x17FFFFFF);
			var picture = VeloWingCard.previewImage(pair.id());
			if (picture != null) {
				int pw = THUMB - 6;
				int ph = pw * 3 / 4;
				context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, thumbX + 3, ty2 + (THUMB - ph) / 2, 0f, 0f, pw, ph, pw, ph, pw, ph);
			}
		}
	}

	private void renderCapesBanner(DrawContext context, int cx, int y, int cw) {
		VeloDraw.fillRounded(context, cx, y, cw, CAPE_BANNER_H, 8, 0xFF131318);
		VeloDraw.fillRoundedGradient(context, cx, y, cw, CAPE_BANNER_H, 8, 0x335865F2, 0x00131318);
		VeloDraw.strokeRounded(context, cx, y, cw, CAPE_BANNER_H, 8, 0x17FFFFFF);
		MenuKit.icon(context, "link", cx + 12, y + (CAPE_BANNER_H - 12) / 2, 12, 0xFF8E9BFF);
		String line = !discordError.isEmpty() ? discordError
				: discordCode.isEmpty() ? "The Velo Neon cape is free - link your Discord to get it."
				: "Paste /link " + discordCode + " in the Velo Discord.";
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(line), cw - 160), cx + 32, y + (CAPE_BANNER_H - 8) / 2,
				discordError.isEmpty() ? MenuKit.TEXT : MenuKit.RED_TEXT);
	}

	private void renderPlus(DrawContext context, int cx, int y, int cw) {
		int h = contentBottom() - y;
		VeloDraw.fillRounded(context, cx, y, cw, h, 10, 0xFF141013);
		VeloDraw.fillRoundedGradient(context, cx, y, cw, Math.min(140, h), 10, 0x40FF2D3C, 0x00141013);
		VeloDraw.strokeRounded(context, cx, y, cw, h, 10, 0x59FF2D3C);
		int tx = cx + 18;
		int top = y + 18;
		VeloBadge.draw(context, tx, top + 4, true);
		MenuKit.text(context, this.textRenderer, MenuKit.displayLg("Velo+"), tx + VeloBadge.width(true) + 8, top, MenuKit.TEXT);
		String status = StoreClient.plus()
				? "Active until " + java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(StoreClient.plusUntil()), java.time.ZoneId.systemDefault())
				: String.format(Locale.ROOT, "Everything below for %.2f %s a month", StoreClient.plusPrice(), StoreClient.currency());
		MenuKit.text(context, this.textRenderer, MenuKit.ui(status), tx, top + 24, StoreClient.plus() ? MenuKit.GREEN : MenuKit.TEXT_2);
		int line = top + 44;
		int bottom = contentBottom() - 18 - 22 - 10;
		for (String perkFormat : PLUS_PERKS) {
			String perk = perkFormat.contains("%,d") ? String.format(Locale.ROOT, perkFormat, StoreClient.plusMonthlyCoins()) : perkFormat;
			List<Text> wrapped = MenuKit.wrap(this.textRenderer, perk, MenuKit::ui, cw - 60);
			if (line + wrapped.size() * 11 > bottom) {
				break;
			}
			VeloDraw.fillRounded(context, tx, line - 1, 10, 10, 5, 0x33FF2D3C);
			VeloDraw.fillCircle(context, tx + 5f, line + 4f, 2f, 0xFFFF4D5A);
			for (Text w : wrapped) {
				MenuKit.text(context, this.textRenderer, w, tx + 18, line, MenuKit.TEXT);
				line += 11;
			}
			line += 5;
		}
		if (!discordError.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(discordError), cw - 36), tx, bottom, MenuKit.RED_TEXT);
		}
	}

	private static String balanceText() {
		int balance = CurrencyManager.balance();
		return balance < 0 ? "-" : String.format(Locale.ROOT, "%,d", balance);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (region != null && region.scroll(mouseX, mouseY, verticalAmount)) {
			if (page == Page.FEATURED || page == Page.BUNDLES) {
				region.layoutPositioned(pageSpots, pageHeight);
			} else {
				region.layoutGrid(columns, tileWidth, tileHeight, GAP);
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (page == Page.FEATURED) {
			List<WingsManager.WingType> all = featuredWings();
			int cx = contentX();
			int cw = contentWidth();
			int thumbX = thumbsX(cx, cw);
			int total = all.size() * THUMB + Math.max(0, all.size() - 1) * 8;
			int thumbY = contentY() + (heroH - total) / 2;
			for (int i = 0; i < all.size(); i++) {
				if (MenuKit.inside((int) click.x(), (int) click.y(), thumbX, thumbY + i * (THUMB + 8), THUMB, THUMB)) {
					pinnedHero = all.get(i).id();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}
}
