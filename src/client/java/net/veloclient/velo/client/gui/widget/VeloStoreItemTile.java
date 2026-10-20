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

	private static final int BOTTOM_STRIP_HEIGHT = StoreCard.INFO_H;

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

		int accent = 0xFF8B7CF6;
		int h = getHeight();
		int lift = StoreCard.frame(context, x, y, w, h, hover, accent);
		AnimatedCapeAsset preview = StoreAssets.preview(item);
		if (preview != null) {
			VeloCapeRender.draw(context, preview.identifier(), preview.width(), preview.height(), x + w / 2f, y - lift + iconHeight - 6,
					iconHeight - 16, VeloCapeRender.idleYaw(phase, hover));
		}
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		StoreCard.info(context, textRenderer, x, y, w, h, lift, "Cape", accent, item.name(), owned ? "Owned" : null, item.priceCoins());
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
