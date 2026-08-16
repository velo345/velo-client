package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.RemoteTextureLoader;
import net.veloclient.velo.client.schematics.PhantomMarketClient;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * One PhantomMarket search result in {@link net.veloclient.velo.client.gui.SchematicsScreen}'s
 * "Browse online" grid - thumbnail (lazily fetched via {@link RemoteTextureLoader}),
 * title, author/downloads. Click opens the post's real page in the system
 * browser (same reasoning as the launcher's schematic browse card: the
 * actual download stays on PhantomMarket's own ad-supported site rather
 * than being scraped).
 */
public final class VeloSchematicSearchTile extends ClickableWidget {

	private static final int THUMB_HEIGHT_RATIO_NUM = 9;
	private static final int THUMB_HEIGHT_RATIO_DEN = 16;

	private final PhantomMarketClient.SearchHit hit;
	private final Runnable onClick;
	private volatile Identifier thumbnail;

	public VeloSchematicSearchTile(int x, int y, int width, int height, PhantomMarketClient.SearchHit hit, Runnable onClick) {
		super(x, y, width, height, Text.literal(hit.title()));
		this.hit = hit;
		this.onClick = onClick;
		RemoteTextureLoader.load(hit.thumbnailUrl(), id -> this.thumbnail = id);
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		onClick.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int thumbHeight = getWidth() * THUMB_HEIGHT_RATIO_NUM / THUMB_HEIGHT_RATIO_DEN;

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), thumbHeight, 5, theme.surfaceWithOpacity());
		if (thumbnail != null) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, thumbnail, getX(), getY(), 0f, 0f,
					getWidth(), thumbHeight, getWidth(), thumbHeight, getWidth(), thumbHeight);
		} else {
			String glyph = "▦";
			context.drawTextWithShadow(textRenderer, glyph, getX() + (getWidth() - textRenderer.getWidth(glyph)) / 2, getY() + thumbHeight / 2 - 4, 0xFF888888);
		}
		if (isHovered()) {
			VeloDraw.strokeRounded(context, getX(), getY(), getWidth(), thumbHeight, 5, theme.accentStart());
		}

		String title = trimToWidth(hit.title(), getWidth() - 4);
		context.drawTextWithShadow(textRenderer, title, getX() + 2, getY() + thumbHeight + 4, theme.text());

		String meta = hit.authorUsername() + "  ·  " + formatCount(hit.downloadCount()) + " dl";
		String metaTrimmed = trimToWidth(meta, getWidth() - 4);
		context.drawTextWithShadow(textRenderer, metaTrimmed, getX() + 2, getY() + thumbHeight + 4 + textRenderer.fontHeight + 2, 0xFFAAAAAA);
	}

	private static String formatCount(long count) {
		if (count >= 1_000_000) {
			return String.format(java.util.Locale.ROOT, "%.1fM", count / 1_000_000.0);
		}
		if (count >= 1_000) {
			return String.format(java.util.Locale.ROOT, "%.1fK", count / 1_000.0);
		}
		return String.valueOf(count);
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
