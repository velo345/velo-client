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
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.Consumer;

/**
 * One cape in {@link net.veloclient.velo.client.gui.CapeEquipScreen}'s grid
 * (same shape as {@link VeloCrosshairTile}): click the preview to
 * equip/unequip it, the pencil to edit its name and physics, "✕" to delete
 * it.
 *
 * <p>The preview draws only the cape texture's back panel (the 10x16 region
 * at UV (1,1) in the standard 64x32 cape template - the part actually
 * visible on a player's back) instead of the raw flat PNG, which includes
 * the mirrored front panel and unused padding and reads as a meaningless
 * strip of pixels rather than "a cape" at a glance.
 */
public final class VeloCapeTile extends ClickableWidget {

	private static final int BOTTOM_STRIP_HEIGHT = 20;
	// Standard vanilla cape texture template is 64x32; the back panel (what
	// actually shows when the cape is worn) is the 10x16 region starting one
	// pixel in from the top-left corner.
	private static final int TEMPLATE_WIDTH = 64;
	private static final int TEMPLATE_HEIGHT = 32;
	private static final int PANEL_U = 1;
	private static final int PANEL_V = 1;
	private static final int PANEL_WIDTH = 10;
	private static final int PANEL_HEIGHT = 16;

	private final CapeDefinition definition;
	private final boolean equipped;
	private final Runnable onEdit;
	private final Runnable onToggleEquip;
	private final Consumer<CapeDefinition> onDelete;

	public VeloCapeTile(int x, int y, int width, int iconHeight, CapeDefinition definition,
			boolean equipped, Runnable onEdit, Runnable onToggleEquip, Consumer<CapeDefinition> onDelete) {
		super(x, y, width, iconHeight + BOTTOM_STRIP_HEIGHT, Text.literal(definition.name()));
		this.definition = definition;
		this.equipped = equipped;
		this.onEdit = onEdit;
		this.onToggleEquip = onToggleEquip;
		this.onDelete = onDelete;
	}

	private int iconAreaHeight() {
		return getHeight() - BOTTOM_STRIP_HEIGHT;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		int localY = (int) click.y() - getY();
		if (localY < iconAreaHeight()) {
			onToggleEquip.run();
			return;
		}
		boolean leftHalf = click.x() < getX() + getWidth() / 2.0;
		if (leftHalf) {
			onEdit.run();
		} else {
			onDelete.accept(definition);
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int iconHeight = iconAreaHeight();

		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), iconHeight, 5, theme.surfaceWithOpacity());
		VeloDraw.strokeRect(context, getX(), getY(), getWidth(), iconHeight, equipped ? theme.accentStart() : 0x33FFFFFF);

		Identifier texture = CapeManager.textureIdentifier(definition);
		// Preserve the panel's real aspect ratio inside the icon box instead
		// of stretching it to fill a differently-shaped rectangle, which
		// would visibly distort the cape.
		int maxPreviewWidth = getWidth() - 12;
		int maxPreviewHeight = iconHeight - 8;
		int previewHeight = maxPreviewHeight;
		int previewWidth = Math.round(previewHeight * (PANEL_WIDTH / (float) PANEL_HEIGHT));
		if (previewWidth > maxPreviewWidth) {
			previewWidth = maxPreviewWidth;
			previewHeight = Math.round(previewWidth * (PANEL_HEIGHT / (float) PANEL_WIDTH));
		}
		int previewX = getX() + (getWidth() - previewWidth) / 2;
		int previewY = getY() + 4;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, previewX, previewY, PANEL_U, PANEL_V,
				previewWidth, previewHeight, PANEL_WIDTH, PANEL_HEIGHT, TEMPLATE_WIDTH, TEMPLATE_HEIGHT);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String name = trimToWidth(definition.name(), getWidth() - 6);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name,
				getX() + (getWidth() - nameWidth) / 2, getY() + iconHeight - 12, theme.text());

		int stripY = getY() + iconHeight;
		int halfW = getWidth() / 2;
		boolean editHovered = isHovered() && mouseY >= stripY && mouseX < getX() + halfW;
		boolean deleteHovered = isHovered() && mouseY >= stripY && mouseX >= getX() + halfW;
		int editColor = editHovered ? 0x66FFFFFF : 0x33FFFFFF;
		int deleteColor = deleteHovered ? 0xAAFF5555 : 0x33FF5555;
		VeloDraw.fillRounded(context, getX(), stripY, halfW - 1, BOTTOM_STRIP_HEIGHT, 3, editColor);
		VeloDraw.fillRounded(context, getX() + halfW + 1, stripY, halfW - 1, BOTTOM_STRIP_HEIGHT, 3, deleteColor);
		String editGlyph = "✎";
		String deleteGlyph = "✕";
		int labelY = stripY + (BOTTOM_STRIP_HEIGHT - textRenderer.fontHeight) / 2;
		context.drawTextWithShadow(textRenderer, editGlyph,
				getX() + (halfW - textRenderer.getWidth(editGlyph)) / 2, labelY, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, deleteGlyph,
				getX() + halfW + (halfW - textRenderer.getWidth(deleteGlyph)) / 2, labelY, 0xFFFFFFFF);
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
