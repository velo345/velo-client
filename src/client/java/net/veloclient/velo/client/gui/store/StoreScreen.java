package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.economy.CurrencyManager;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloStoreItemTile;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreCategory;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * The cosmetics store (design spec's Store): a left-hand category list (just
 * "Capes" today - {@link StoreCategory} is built to grow) and a grid of
 * {@link VeloStoreItemTile}s, with the player's Velo Coins balance shown as
 * a clickable pill top-right (the Velo logo as its icon) that opens {@link
 * BuyCoinsScreen}.
 */
public final class StoreScreen extends VeloWindow {

	private static final Identifier LOGO_TEXTURE = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final int LOGO_SOURCE_SIZE = 500;
	private static final int SIDEBAR_WIDTH = 100;
	private static final int TILE_WIDTH = 96;
	private static final int TILE_ICON_HEIGHT = 108;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_HEIGHT + 18;
	private static final int TILE_GAP = 10;
	private static final int BALANCE_BAR_HEIGHT = 20;

	private StoreCategory selectedCategory = StoreCategory.CAPES;
	private VeloScrollRegion sidebarRegion;
	private VeloScrollRegion gridRegion;
	private int gridColumns = 1;
	private int balanceX;
	private int balanceWidth;

	public StoreScreen(Screen parent) {
		super(Text.literal("Store"), 560, 420);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		// One category today, so no sidebar: header (title + coin balance) and a full-width grid.
		int gridX = contentX();
		int topY = contentY() + BALANCE_BAR_HEIGHT + 10;
		int gridWidth = contentWidth();
		gridColumns = Math.max(1, (gridWidth - 8 + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
		gridRegion = new VeloScrollRegion(gridX, topY, gridWidth, contentBottom() - topY);
		for (var item : StoreCatalog.byCategory(selectedCategory)) {
			VeloStoreItemTile tile = new VeloStoreItemTile(0, 0, TILE_WIDTH, TILE_ICON_HEIGHT, item,
					() -> this.client.setScreen(new StoreItemDetailScreen(this, item)));
			addSelectableChild(tile);
			gridRegion.addRow(tile);
		}
		gridRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);

		String balanceLabel = String.valueOf(CurrencyManager.balance());
		balanceWidth = this.textRenderer.getWidth(balanceLabel) + this.textRenderer.getWidth("Get coins") + 44;
		balanceX = contentX() + contentWidth() - balanceWidth;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (gridRegion != null && gridRegion.scroll(mouseX, mouseY, verticalAmount)) {
			gridRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		int balanceY = contentY();
		if (click.x() >= balanceX && click.x() <= balanceX + balanceWidth
				&& click.y() >= balanceY && click.y() <= balanceY + BALANCE_BAR_HEIGHT) {
			this.client.setScreen(new BuyCoinsScreen(this));
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (gridRegion != null) {
			gridRegion.renderRows(context, mouseX, mouseY, delta);
			gridRegion.renderScrollbarGrid(context, gridColumns, TILE_TOTAL_HEIGHT, TILE_GAP);
		}

		// Header: title + count on the left.
		int count = StoreCatalog.byCategory(selectedCategory).size();
		Text title = TitleScreenTheme.tileFont(selectedCategory.displayName());
		int titleY = contentY() + (BALANCE_BAR_HEIGHT - 8) / 2;
		context.drawTextWithShadow(this.textRenderer, title, contentX(), titleY, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, count + (count == 1 ? " item" : " items") + "  ·  preview on your own player",
				contentX() + this.textRenderer.getWidth(title) + 6, titleY, VeloStyle.textFaint());

		// Coin balance chip on the right (click to get coins).
		int balanceY = contentY();
		boolean hovered = mouseX >= balanceX && mouseX <= balanceX + balanceWidth
				&& mouseY >= balanceY && mouseY <= balanceY + BALANCE_BAR_HEIGHT;
		int h = BALANCE_BAR_HEIGHT;
		VeloDraw.fillRounded(context, balanceX, balanceY, balanceWidth, h, h / 2,
				VeloAnim.lerpArgb(VeloStyle.card(), 0xFFF4B82E, hovered ? 0.22f : 0.10f));
		VeloDraw.strokeRounded(context, balanceX, balanceY, balanceWidth, h, h / 2, VeloUi.withAlpha(0xFFF4B82E, hovered ? 0xC0 : 0x60));
		float coinX = balanceX + 11.5f;
		float coinY = balanceY + h / 2f;
		VeloDraw.fillCircle(context, coinX, coinY, 6f, 0xFFC9861A);
		VeloDraw.fillCircle(context, coinX, coinY, 4.8f, 0xFFFFD56A);
		String balanceLabel = String.valueOf(CurrencyManager.balance());
		context.drawTextWithShadow(this.textRenderer, balanceLabel, balanceX + 22, balanceY + (h - 8) / 2, 0xFFFFD56A);
		context.drawTextWithShadow(this.textRenderer, "Get coins", balanceX + 30 + this.textRenderer.getWidth(balanceLabel),
				balanceY + (h - 8) / 2, hovered ? 0xFFFFFFFF : VeloStyle.textMuted());
	}
}
