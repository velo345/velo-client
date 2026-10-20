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
 * Wings, Capes, Auras, Velo+), your coins and "My Locker" sit top right. Featured opens on the
 * newest collection - big title, what it is, "Shop the collection" / "Try on" on the left, you
 * wearing its pairs on a turning 3D model on the right with a thumbnail per pair to switch - and the
 * collection's cards underneath. Everything is bought with Velo Coins; the server checks the price.
 */
public final class StoreScreen extends VeloWindow {

	private enum Page { FEATURED, WINGS, CAPES, AURAS, PLUS }

	private static final String[] TABS = {"Featured", "Wings", "Capes", "Auras", "Velo+"};
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
	private boolean collectionRow = true;
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
		return index == Page.AURAS.ordinal() ? "NEW" : null;
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
		heroH = Math.max(150, Math.min(250, available - 170));
		collectionRow = available - heroH - 26 >= 110;
		if (!collectionRow) {
			heroH = Math.min(260, available);
		}
		addDrawableChild(new EntityPreviewWidget(previewX(cx, cw), y + 6, previewW(cw), heroH - 12)
				.wings(() -> {
					WingsManager.WingType type = heroWings();
					return type == null ? null
							: new WingsPreview.Tag(new WingsManager.Worn(type.id(), WingsManager.settings(type.id())), WingsPreview.Showcase.FLY, heroKey);
				}).spin(20f, 150f).noStage()
				.fallbackImage(() -> heroWings() == null ? null : VeloWingCard.previewImage(heroWings().id())));
		int tx = cx + 18;
		int buttonsY = y + heroH - 18 - 20;
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
		if (!collectionRow) {
			return;
		}
		List<WingsManager.WingType> all = featuredWings();
		int n = Math.max(1, all.size());
		int cardW = Math.min(230, (cw - (n - 1) * GAP) / n);
		int cardY = y + heroH + 30;
		int cardH = Math.min(contentBottom() - cardY, cardW * 3 / 4 + StoreCard.INFO_H);
		for (int i = 0; i < all.size(); i++) {
			WingsManager.WingType type = all.get(i);
			VeloWingCard card = new VeloWingCard(cardW, cardH, type, () -> this.client.setScreen(new WingDetailScreen(this, type)));
			card.setX(cx + i * (cardW + GAP));
			card.setY(cardY);
			addDrawableChild(card);
		}
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
			"The Velo+ badge: your logo with a red plus next to your name, for every Velo player",
			"VIP role in the Velo Discord - automatic once you've linked it",
			"%,d Velo Coins every month - spend them on wings, capes and auras",
			"All 57 Velo Client Addons modules (updated by the launcher): Cinematic Camera, Fly Boat, Auto Eat, Storage and Entity Finder, "
					+ "Auto Tunnel Miner, Sound Radar and more",
			"Supporting the development of Velo Client"};

	private void layoutPlus(int cx, int y, int cw) {
		boolean member = StoreClient.plus();
		Text label = Text.literal(member ? "You're Velo+" : String.format(Locale.ROOT, "Subscribe  ·  %.2f %s / month", StoreClient.plusPrice(), StoreClient.currency()));
		int buttonW = Math.min(cw - 36, this.textRenderer.getWidth(label) + 34);
		VeloButton plusButton = new VeloButton(cx + 18, contentBottom() - 18 - 22, buttonW, 22, label, b -> {
			if (member) {
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
	}

	// ---- Rendering ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		int cx = contentX();
		int cw = contentWidth();
		int y = contentY();
		switch (page) {
			case FEATURED -> renderHeroBackground(context, cx, y, cw);
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
		if (region != null) {
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
		int tw = Math.min(previewX(cx, cw) - 12 - tx, 360);
		int ty = y + 18;
		WingsManager.Collection collection = WingsManager.featured().orElse(null);
		Long until = type == null ? null : type.availableUntil();
		// Tag + how long a limited drop stays.
		Text tag = MenuKit.small((collection == null ? "New collection" : collection.tag()).toUpperCase(Locale.ROOT));
		int tagW = this.textRenderer.getWidth(tag) + 10;
		VeloDraw.fillRounded(context, tx, ty, tagW, 11, 3, VeloUi.withAlpha(accent, 0x40));
		MenuKit.text(context, this.textRenderer, tag, tx + 5, ty + 3, accentText);
		if (until != null) {
			long left = Math.max(0, until - System.currentTimeMillis());
			long days = left / 86_400_000L;
			long hours = left / 3_600_000L % 24;
			Text ends = MenuKit.mono(days >= 1 ? "Ends in " + days + "d " + hours + "h" : "Ends in " + hours + "h");
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, ends, tw - tagW - 8), tx + tagW + 8, ty + 3, MenuKit.TEXT_2);
		}
		// The collection's name, as big as fits.
		String name = collection == null ? "Wings" : collection.name();
		Text title = MenuKit.hero(name);
		if (this.textRenderer.getWidth(title) > tw) {
			title = MenuKit.displayLg(name);
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, title, tw), tx, ty + 20, MenuKit.TEXT);
		int textTop = ty + 20 + (this.textRenderer.getWidth(MenuKit.hero(name)) > tw ? MenuKit.H_DISPLAY_LG : MenuKit.H_HERO) + 12;
		int buttonsY = y + heroH - 18 - 20;
		int maxLines = Math.max(0, (buttonsY - 10 - textTop) / 11);
		List<Text> lines = MenuKit.wrap(this.textRenderer, (collection == null ? "" : collection.description() + " ") + "Tune each pair's size and flap to your style.",
				MenuKit::ui, tw);
		for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
			MenuKit.text(context, this.textRenderer, lines.get(i), tx, textTop + i * 11, MenuKit.TEXT_2);
		}
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
		if (collectionRow) {
			int sy = y + heroH + 12;
			Text heading = MenuKit.display("The collection");
			MenuKit.text(context, this.textRenderer, heading, cx, sy, MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.small(all.size() + (all.size() == 1 ? " PAIR" : " PAIRS")),
					cx + this.textRenderer.getWidth(heading) + 8, sy + 1, MenuKit.TEXT_3);
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
			region.layoutGrid(columns, tileWidth, tileHeight, GAP);
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
