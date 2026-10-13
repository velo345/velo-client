package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import net.veloclient.velo.client.cosmetics.wings.WingsPreview;
import net.veloclient.velo.client.economy.CurrencyManager;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloStoreItemTile;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloWingCard;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreCategory;

import java.util.List;

/**
 * The Velo Store. Opens on <b>Featured</b>: the new wings collection, "Shadows &amp; Angels", front and
 * centre - you wearing each pair in turn on a slowly turning 3D model, flapping - with the whole
 * collection below it. <b>Wings</b> and <b>Capes</b> list everything for sale; "My cosmetics" (top
 * right) is where you equip what you own. Your Velo Coins sit top right (click for more). Everything
 * is bought with Velo Coins; the server checks the price and records what you own.
 */
public final class StoreScreen extends VeloWindow {

	private enum Page { FEATURED, WINGS, CAPES }

	private static final int SIDEBAR_WIDTH = 108;
	private static final int HEADER_HEIGHT = 20;
	private static final int CAPE_TILE_WIDTH = 96;
	private static final int CAPE_TILE_ICON = 108;
	private static final int GAP = 10;
	private static final long HERO_SECONDS = 4;

	private static Page page = Page.FEATURED;
	private VeloScrollRegion region;
	private int columns = 1;
	private int tileWidth;
	private int tileHeight;
	private int balanceX;
	private int balanceWidth;
	private int mineX;
	private int mineWidth;
	private final int heroKey = -500;
	/** Hero height for the current window size (smaller GUI space = smaller hero, cards only if they fit). */
	private int heroH = 220;
	private boolean collectionRow = true;

	public StoreScreen(Screen parent) {
		super(Text.literal("Velo Store"), 720, 470);
		returnTo(parent);
		WingsManager.load();
	}

	/** The pair the Featured hero shows right now (changes every few seconds). */
	private static WingsManager.WingType heroWings() {
		List<WingsManager.WingType> all = WingsManager.catalog();
		if (all.isEmpty()) {
			return null;
		}
		return all.get((int) ((System.currentTimeMillis() / 1000 / HERO_SECONDS) % all.size()));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		region = null;
		StoreClient.refresh(false, null);
		int x = contentX();
		int y = contentY() + HEADER_HEIGHT + 10;
		Page[] pages = Page.values();
		String[] labels = {"Featured", "Wings", "Capes"};
		for (int i = 0; i < pages.length; i++) {
			Page p = pages[i];
			VeloButton nav = new VeloButton(x, y + i * 24, SIDEBAR_WIDTH - 10, 20, Text.literal(labels[i]), b -> {
				page = p;
				layoutContent();
			});
			if (p == page) {
				nav.primary();
			}
			addDrawableChild(nav);
		}
		addDrawableChild(new VeloButton(x, contentBottom() - 20, SIDEBAR_WIDTH - 10, 20, Text.literal("Get coins"),
				b -> this.client.setScreen(new BuyCoinsScreen(this))));

		int cx = x + SIDEBAR_WIDTH;
		int cw = contentX() + contentWidth() - cx;
		switch (page) {
			case FEATURED -> layoutFeatured(cx, y, cw);
			case WINGS -> layoutWings(cx, y, cw, WingsManager.catalog());
			case CAPES -> layoutCapes(cx, y, cw);
		}
		// Generous padding: the UI font can draw a little wider than the vanilla width measure.
		balanceWidth = Math.round((this.textRenderer.getWidth(balanceText()) + this.textRenderer.getWidth("Get coins")) * 1.15f) + 46;
		balanceX = contentX() + contentWidth() - balanceWidth;
		mineWidth = Math.round(this.textRenderer.getWidth("My cosmetics") * 1.15f) + 30;
		mineX = balanceX - mineWidth - 8;
	}

	private void layoutFeatured(int cx, int y, int cw) {
		int available = contentBottom() - y;
		heroH = Math.max(130, Math.min(220, available - 130));
		collectionRow = available - heroH - 22 >= 90;
		if (!collectionRow) {
			heroH = Math.min(220, available);
		}
		int previewW = Math.min(210, cw / 3);
		addDrawableChild(new EntityPreviewWidget(cx + 8, y + 6, previewW, heroH - 12)
				.wings(() -> {
					WingsManager.WingType type = heroWings();
					return type == null ? null
							: new WingsPreview.Tag(new WingsManager.Worn(type.id(), WingsManager.settings(type.id())), WingsPreview.Showcase.FLY, heroKey);
				}).spin(20f, 150f).noStage()
				.fallbackImage(() -> heroWings() == null ? null : VeloWingCard.previewImage(heroWings().id())));
		int tx = cx + previewW + 24;
		int tw = cx + cw - tx - 12;
		int shopW = Math.min(150, (tw - 6) * 3 / 5);
		VeloButton browse = new VeloButton(tx, y + heroH - 30, shopW, 20, Text.literal("Shop the collection"), b -> {
			page = Page.WINGS;
			layoutContent();
		});
		browse.primary();
		addDrawableChild(browse);
		addDrawableChild(new VeloButton(tx + shopW + 6, y + heroH - 30, Math.min(110, tw - shopW - 6), 20, Text.literal("Try them on"), b -> {
			WingsManager.WingType type = heroWings();
			if (type != null) {
				this.client.setScreen(new WingDetailScreen(this, type));
			}
		}));
		// The collection under the hero (when there's room for it).
		if (!collectionRow) {
			return;
		}
		List<WingsManager.WingType> all = WingsManager.catalog();
		int n = Math.max(1, all.size());
		int cardW = (cw - (n - 1) * 8) / n;
		int cardY = y + heroH + 22;
		int cardH = contentBottom() - cardY;
		for (int i = 0; i < all.size(); i++) {
			WingsManager.WingType type = all.get(i);
			VeloWingCard card = new VeloWingCard(cardW, cardH, type, () -> this.client.setScreen(new WingDetailScreen(this, type)));
			card.setX(cx + i * (cardW + 8));
			card.setY(cardY);
			addDrawableChild(card);
		}
	}

	private void layoutWings(int cx, int y, int cw, List<WingsManager.WingType> list) {
		columns = 3;
		tileWidth = (cw - (columns - 1) * GAP - 8) / columns;
		tileHeight = tileWidth * 6 / 5;
		region = new VeloScrollRegion(cx, y, cw, contentBottom() - y);
		for (WingsManager.WingType type : list) {
			VeloWingCard card = new VeloWingCard(tileWidth, tileHeight, type, () -> this.client.setScreen(new WingDetailScreen(this, type)));
			addSelectableChild(card);
			region.addRow(card);
		}
		region.layoutGrid(columns, tileWidth, tileHeight, GAP);
	}

	private void layoutCapes(int cx, int y, int cw) {
		columns = Math.max(1, (cw - 8 + GAP) / (CAPE_TILE_WIDTH + GAP));
		tileWidth = CAPE_TILE_WIDTH;
		tileHeight = CAPE_TILE_ICON + 18;
		region = new VeloScrollRegion(cx, y, cw, contentBottom() - y);
		for (var item : StoreCatalog.byCategory(StoreCategory.CAPES)) {
			VeloStoreItemTile tile = new VeloStoreItemTile(0, 0, CAPE_TILE_WIDTH, CAPE_TILE_ICON, item,
					() -> this.client.setScreen(new StoreItemDetailScreen(this, item)));
			addSelectableChild(tile);
			region.addRow(tile);
		}
		region.layoutGrid(columns, tileWidth, tileHeight, GAP);
	}

	private static String balanceText() {
		int balance = CurrencyManager.balance();
		return balance < 0 ? "..." : String.format(java.util.Locale.ROOT, "%,d", balance);
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
		int balanceY = contentY();
		if (click.x() >= balanceX && click.x() <= balanceX + balanceWidth && click.y() >= balanceY && click.y() <= balanceY + HEADER_HEIGHT) {
			this.client.setScreen(new BuyCoinsScreen(this));
			return true;
		}
		if (click.x() >= mineX && click.x() <= mineX + mineWidth && click.y() >= balanceY && click.y() <= balanceY + HEADER_HEIGHT) {
			this.client.setScreen(new net.veloclient.velo.client.gui.CapeEquipScreen(this));
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		int x = contentX();
		int y = contentY() + HEADER_HEIGHT + 10;
		int cx = x + SIDEBAR_WIDTH;
		int cw = contentX() + contentWidth() - cx;
		super.render(context, mouseX, mouseY, delta);
		if (page == Page.FEATURED) {
			renderHero(context, cx, y, cw);
		}
		if (region != null) {
			region.renderRows(context, mouseX, mouseY, delta);
			region.renderScrollbarGrid(context, columns, tileHeight, GAP);
		}
		// "NEW" on the Wings button.
		VeloDraw.fillRounded(context, x + SIDEBAR_WIDTH - 40, y + 24 + 4, 26, 12, 4, 0xFFFF4D4D);
		context.drawTextWithShadow(this.textRenderer, "NEW", x + SIDEBAR_WIDTH - 36, y + 24 + 6, 0xFFFFFFFF);

		String title = switch (page) {
			case FEATURED -> "Featured";
			case WINGS -> "Wings";
			case CAPES -> "Capes";
		};
		Text heading = TitleScreenTheme.tileFont(title);
		context.drawTextWithShadow(this.textRenderer, heading, x, contentY() + 6, VeloStyle.text());
		String sub = switch (page) {
			case FEATURED -> "the newest drops";
			case WINGS -> "Shadows & Angels  ·  " + WingsManager.catalog().size() + " pairs of animated 3D wings";
			case CAPES -> "animated capes  ·  preview on your own player";
		};
		context.drawTextWithShadow(this.textRenderer, sub, x + this.textRenderer.getWidth(heading) + 8, contentY() + 6, VeloStyle.textFaint());
		renderBalance(context, mouseX, mouseY);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (page != Page.FEATURED) {
			return;
		}
		int cx = contentX() + SIDEBAR_WIDTH;
		int cw = contentX() + contentWidth() - cx;
		int y = contentY() + HEADER_HEIGHT + 10;
		WingsManager.WingType type = heroWings();
		int accent = type == null ? 0xFFFF4D4D : VeloWingCard.accentFor(type.id());
		VeloDraw.fillRounded(context, cx, y, cw, heroH, 10, 0xFF121218);
		VeloDraw.fillRoundedGradient(context, cx, y, cw, heroH, 10, VeloUi.withAlpha(accent, 0x38), 0x00121218);
	}

	private void renderHero(DrawContext context, int cx, int y, int cw) {
		WingsManager.WingType type = heroWings();
		int accent = type == null ? 0xFFFF4D4D : VeloWingCard.accentFor(type.id());
		VeloDraw.strokeRounded(context, cx, y, cw, heroH, 10, VeloUi.withAlpha(accent, 0x90));
		int previewW = Math.min(210, cw / 3);
		int tx = cx + previewW + 24;
		int tw = cx + cw - tx - 12;
		int ty = y + 14;
		VeloDraw.fillRounded(context, tx, ty, 92, 13, 4, VeloUi.withAlpha(accent, 0xE0));
		context.drawTextWithShadow(this.textRenderer, "NEW COLLECTION", tx + 6, ty + 3, 0xFF101014);
		// As big as fits: "Shadows & Angels" is long.
		float big = Math.min(heroH >= 190 ? 2.2f : 1.7f, tw / (float) Math.max(1, this.textRenderer.getWidth("Shadows & Angels") * 1.1f));
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(tx, ty + 19);
		context.getMatrices().scale(big, big);
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont("Shadows & Angels"), 0, 0, 0xFFFFFFFF);
		context.getMatrices().popMatrix();
		int textTop = ty + 19 + Math.round(10 * big) + 6;
		int textBottom = y + heroH - 46;
		List<String> lines = VeloUi.wrap("Five pairs of real 3D wings that move with you - a slow, calm wingbeat while you stand, wider strokes as you run, "
				+ "spread wide when you glide. Tune each pair's size and flap to your style.", tw);
		for (int i = 0; i < lines.size() && textTop + (i + 1) * 10 <= textBottom; i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), tx, textTop + i * 10, VeloStyle.textMuted());
		}
		if (type != null) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Now showing: " + type.name(), tw), tx, y + heroH - 44,
					VeloUi.withAlpha(accent, 0xFF));
		}
		// Which pair the hero is on.
		List<WingsManager.WingType> all = WingsManager.catalog();
		for (int i = 0; i < all.size(); i++) {
			boolean on = type != null && all.get(i).id().equals(type.id());
			VeloDraw.fillCircle(context, cx + 8 + previewW / 2f - all.size() * 6 + i * 12, y + heroH - 6, on ? 3f : 2f, on ? accent : 0x80FFFFFF);
		}
		if (collectionRow) {
			context.drawTextWithShadow(this.textRenderer, "The collection", cx, y + heroH + 8, VeloStyle.text());
		}
	}

	private void renderBalance(DrawContext context, int mouseX, int mouseY) {
		int balanceY = contentY();
		// "My cosmetics": straight to equipping what you own.
		boolean mineHover = mouseX >= mineX && mouseX <= mineX + mineWidth && mouseY >= balanceY && mouseY <= balanceY + HEADER_HEIGHT;
		VeloDraw.fillRounded(context, mineX, balanceY, mineWidth, HEADER_HEIGHT, HEADER_HEIGHT / 2,
				VeloAnim.lerpArgb(VeloStyle.card(), VeloStyle.accent(), mineHover ? 0.35f : 0.15f));
		VeloDraw.strokeRounded(context, mineX, balanceY, mineWidth, HEADER_HEIGHT, HEADER_HEIGHT / 2,
				VeloUi.withAlpha(VeloStyle.accent(), mineHover ? 0xD0 : 0x70));
		context.drawTextWithShadow(this.textRenderer, "✦", mineX + 9, balanceY + (HEADER_HEIGHT - 8) / 2, VeloStyle.accent());
		context.drawTextWithShadow(this.textRenderer, "My cosmetics", mineX + 20, balanceY + (HEADER_HEIGHT - 8) / 2,
				mineHover ? 0xFFFFFFFF : VeloStyle.text());
		boolean hovered = mouseX >= balanceX && mouseX <= balanceX + balanceWidth && mouseY >= balanceY && mouseY <= balanceY + HEADER_HEIGHT;
		int h = HEADER_HEIGHT;
		VeloDraw.fillRounded(context, balanceX, balanceY, balanceWidth, h, h / 2,
				VeloAnim.lerpArgb(VeloStyle.card(), 0xFFF4B82E, hovered ? 0.22f : 0.10f));
		VeloDraw.strokeRounded(context, balanceX, balanceY, balanceWidth, h, h / 2, VeloUi.withAlpha(0xFFF4B82E, hovered ? 0xC0 : 0x60));
		float coinX = balanceX + 11.5f;
		float coinY = balanceY + h / 2f;
		VeloDraw.fillCircle(context, coinX, coinY, 6f, 0xFFC9861A);
		VeloDraw.fillCircle(context, coinX, coinY, 4.8f, 0xFFFFD56A);
		String balanceLabel = balanceText();
		context.drawTextWithShadow(this.textRenderer, balanceLabel, balanceX + 22, balanceY + (h - 8) / 2, 0xFFFFD56A);
		context.drawTextWithShadow(this.textRenderer, "Get coins", balanceX + 30 + this.textRenderer.getWidth(balanceLabel),
				balanceY + (h - 8) / 2, hovered ? 0xFFFFFFFF : VeloStyle.textMuted());
	}
}
