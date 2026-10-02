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
	private float hover;
	private float phase = (float) (Math.random() * Math.PI * 2);
	private long lastNanos;
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
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);
		phase = VeloCapeRender.advancePhase(phase, hover, dt);
		lastNanos = now;
		int iconHeight = iconAreaHeight();
		boolean owned = StoreOwnership.owns(item.id());
		int x = getX();
		int y = getY();
		int w = getWidth();

		VeloCapeTile.drawStage(context, x, y, w, iconHeight, hover, false);
		AnimatedCapeAsset preview = StoreAssets.preview(item);
		VeloCapeRender.draw(context, preview.identifier(), preview.width(), preview.height(), x + w / 2f, y + iconHeight - 20,
				iconHeight - 34, VeloCapeRender.idleYaw(phase, hover));

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String name = trimToWidth(item.name(), w - 10);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name, x + (w - nameWidth) / 2, y + iconHeight - 13, VeloStyle.text());

		int stripY = y + iconHeight + 2;
		if (owned) {
			VeloUi.pill(context, x, stripY, w, BOTTOM_STRIP_HEIGHT - 2, "Owned", 2, -1, -1, () -> { });
		} else {
			// Gold price chip: coin + amount.
			int h = BOTTOM_STRIP_HEIGHT - 2;
			VeloDraw.fillRounded(context, x, stripY, w, h, h / 2, VeloAnim.lerpArgb(VeloStyle.card(), 0xFFF4B82E, 0.10f + 0.08f * hover));
			VeloDraw.strokeRounded(context, x, stripY, w, h, h / 2, VeloUi.withAlpha(0xFFF4B82E, 0x60));
			String price = String.valueOf(item.priceCoins());
			int textWidth = textRenderer.getWidth(price);
			int coinR = 4;
			int startX = x + (w - (textWidth + coinR * 2 + 4)) / 2;
			VeloDraw.fillCircle(context, startX + coinR + 0.5f, stripY + h / 2f, coinR, 0xFFF4B82E);
			VeloDraw.fillCircle(context, startX + coinR + 0.5f, stripY + h / 2f, coinR - 1.5f, 0xFFFFD56A);
			context.drawTextWithShadow(textRenderer, price, startX + coinR * 2 + 4, stripY + (h - 8) / 2, 0xFFFFD56A);
		}
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
