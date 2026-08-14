package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
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

		int sidebarX = contentX();
		int gridX = sidebarX + SIDEBAR_WIDTH + 14;
		int topY = contentY() + BALANCE_BAR_HEIGHT + 8;

		sidebarRegion = new VeloScrollRegion(sidebarX, topY, SIDEBAR_WIDTH, contentBottom() - topY);
		for (StoreCategory category : StoreCategory.values()) {
			StoreCategory cat = category;
			VeloButton button = new VeloButton(sidebarX, 0, SIDEBAR_WIDTH, 20, Text.literal(category.displayName()), b -> {
						this.selectedCategory = cat;
						layoutContent();
					})
					.selected(category == selectedCategory);
			addSelectableChild(button);
			sidebarRegion.addRow(button);
		}
		sidebarRegion.layout(20, 2);

		int gridWidth = contentX() + contentWidth() - gridX;
		gridColumns = Math.max(1, (gridWidth + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
		gridRegion = new VeloScrollRegion(gridX, topY, gridWidth, contentBottom() - topY);
		for (var item : StoreCatalog.byCategory(selectedCategory)) {
			VeloStoreItemTile tile = new VeloStoreItemTile(0, 0, TILE_WIDTH, TILE_ICON_HEIGHT, item,
					() -> this.client.setScreen(new StoreItemDetailScreen(this, item)));
			addSelectableChild(tile);
			gridRegion.addRow(tile);
		}
		gridRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);

		String balanceLabel = CurrencyManager.balance() + " Velo Coins";
		balanceWidth = this.textRenderer.getWidth(balanceLabel) + 34;
		balanceX = contentX() + contentWidth() - balanceWidth;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (gridRegion != null && gridRegion.scroll(mouseX, mouseY, verticalAmount)) {
			gridRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);
			return true;
		}
		if (sidebarRegion != null && sidebarRegion.scroll(mouseX, mouseY, verticalAmount)) {
			sidebarRegion.layout(20, 2);
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
		if (sidebarRegion != null) {
			sidebarRegion.renderRows(context, mouseX, mouseY, delta);
			sidebarRegion.renderScrollbar(context, 20, 2);
		}
		if (gridRegion != null) {
			gridRegion.renderRows(context, mouseX, mouseY, delta);
			gridRegion.renderScrollbarGrid(context, gridColumns, TILE_TOTAL_HEIGHT, TILE_GAP);
		}

		Theme theme = ThemeManager.active();
		int balanceY = contentY();
		boolean hovered = mouseX >= balanceX && mouseX <= balanceX + balanceWidth
				&& mouseY >= balanceY && mouseY <= balanceY + BALANCE_BAR_HEIGHT;
		VeloDraw.fillRounded(context, balanceX, balanceY, balanceWidth, BALANCE_BAR_HEIGHT, 4,
				hovered ? theme.accentStart() : theme.surfaceWithOpacity());
		context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, balanceX + 4, balanceY + 2, 0f, 0f,
				16, 16, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE);
		String balanceLabel = CurrencyManager.balance() + " Velo Coins";
		context.drawTextWithShadow(this.textRenderer, balanceLabel, balanceX + 24, balanceY + (BALANCE_BAR_HEIGHT - 8) / 2,
				hovered ? 0xFFFFFFFF : theme.text());
	}
}
