package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.AnimatedCapeAsset;
import net.veloclient.velo.client.store.StoreAssets;
import net.veloclient.velo.client.store.StoreItem;
import net.veloclient.velo.client.store.StoreOwnership;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * One item in {@link net.veloclient.velo.client.gui.store.StoreScreen}'s
 * grid: the cape's live animated back-panel preview (same 10x16-at-(1,1)
 * template region {@link VeloCapeTile} crops, just computed proportionally
 * since the store's bundled art is higher-resolution than the 64x32
 * template), name, price in Velo Coins, and an "Owned" tag once bought.
 * Clicking anywhere opens the item's detail/purchase screen - there's no
 * equip/delete split here like the cape library has, buying happens on the
 * detail screen instead.
 */
public final class VeloStoreItemTile extends ClickableWidget {

	private static final int BOTTOM_STRIP_HEIGHT = 18;

	private final StoreItem item;
	private final Runnable onOpen;

	public VeloStoreItemTile(int x, int y, int width, int iconHeight, StoreItem item, Runnable onOpen) {
		super(x, y, width, iconHeight + BOTTOM_STRIP_HEIGHT, Text.literal(item.name()));
		this.item = item;
		this.onOpen = onOpen;
	}

	private int iconAreaHeight() {
		return getHeight() - BOTTOM_STRIP_HEIGHT;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		onOpen.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int iconHeight = iconAreaHeight();
		boolean owned = StoreOwnership.owns(item.id());

		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), iconHeight, 5,
				isHovered() ? VeloAnim.lerpArgb(theme.surfaceWithOpacity(), 0xFFFFFFFF, 0.06f) : theme.surfaceWithOpacity());
		VeloDraw.strokeRounded(context, getX(), getY(), getWidth(), iconHeight, 5, owned ? theme.accentStart() : 0x33FFFFFF);

		AnimatedCapeAsset preview = StoreAssets.preview(item);
		// Same back-panel crop VeloCapeTile uses (10x16 starting one pixel
		// in, in the standard 64x32 cape template), scaled proportionally -
		// the store's bundled GIFs are a higher-resolution multiple of that
		// template, not literally 64x32 pixels.
		float scale = preview.width() / 64f;
		float panelU = 1 * scale;
		float panelV = 1 * scale;
		float panelW = 10 * scale;
		float panelH = 16 * scale;

		int maxPreviewWidth = getWidth() - 12;
		int maxPreviewHeight = iconHeight - 8;
		int previewHeight = maxPreviewHeight;
		int previewWidth = Math.round(previewHeight * (panelW / panelH));
		if (previewWidth > maxPreviewWidth) {
			previewWidth = maxPreviewWidth;
			previewHeight = Math.round(previewWidth * (panelH / panelW));
		}
		int previewX = getX() + (getWidth() - previewWidth) / 2;
		int previewY = getY() + 4;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, preview.identifier(), previewX, previewY,
				panelU, panelV, previewWidth, previewHeight, Math.round(panelW), Math.round(panelH), preview.width(), preview.height());

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String name = trimToWidth(item.name(), getWidth() - 6);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name, getX() + (getWidth() - nameWidth) / 2, getY() + iconHeight - 12, theme.text());

		if (owned) {
			String tag = "OWNED";
			int tagWidth = textRenderer.getWidth(tag);
			context.drawTextWithShadow(textRenderer, tag, getX() + getWidth() - tagWidth - 4, getY() + 4, theme.accentStart());
		}

		int stripY = getY() + iconHeight;
		VeloDraw.fillRounded(context, getX(), stripY, getWidth(), BOTTOM_STRIP_HEIGHT, 3, 0x22FFFFFF);
		String priceLabel = owned ? "Owned" : (item.priceCoins() + " VC");
		int priceWidth = textRenderer.getWidth(priceLabel);
		int priceY = stripY + (BOTTOM_STRIP_HEIGHT - textRenderer.fontHeight) / 2;
		context.drawTextWithShadow(textRenderer, priceLabel, getX() + (getWidth() - priceWidth) / 2, priceY,
				owned ? theme.accentStart() : 0xFFF7D774);
	}

	private static String trimToWidth(String text, int maxWidth) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		if (renderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String trimmed = text;
		while (trimmed.length() > 1 && renderer.getWidth(trimmed + "..") > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed + "..";
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
