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
import net.veloclient.velo.client.crosshair.CrosshairDefinition;
import net.veloclient.velo.client.crosshair.CrosshairManager;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.Consumer;

/**
 * One crosshair in {@link net.veloclient.velo.client.gui.CrosshairSelectScreen}'s
 * grid: click the icon to equip it, the pencil to edit it, "Delete" to
 * remove it. Hovering the icon previews the hit state (a separate image, or
 * the idle image with its color-swap map applied).
 *
 * <p>Draws through {@link CrosshairManager}'s cached GPU textures (the same
 * ones the actual in-game crosshair uses) rather than reading raw {@link
 * net.minecraft.client.texture.NativeImage} pixels directly - an earlier
 * version of this widget held its own short-lived NativeImage references
 * supplied by the screen, which crashed the game ("Image is not allocated")
 * whenever a tile was still rendering a frame after the screen closed or
 * reopened its images. The texture cache is long-lived and already handles
 * its own lifecycle correctly (it's what the mixin-based crosshair renderer
 * relies on too), so reusing it here sidesteps the whole problem instead of
 * re-solving native memory lifetimes a second time.
 */
public final class VeloCrosshairTile extends ClickableWidget {

	private static final int BOTTOM_STRIP_HEIGHT = 20;

	private final CrosshairDefinition definition;
	private final boolean equipped;
	private final Runnable onEdit;
	private final Runnable onEquip;
	private final Consumer<CrosshairDefinition> onDelete;

	public VeloCrosshairTile(int x, int y, int size, CrosshairDefinition definition,
			boolean equipped, Runnable onEdit, Runnable onEquip, Consumer<CrosshairDefinition> onDelete) {
		super(x, y, size, size + BOTTOM_STRIP_HEIGHT, Text.literal(definition.name()));
		this.definition = definition;
		this.equipped = equipped;
		this.onEdit = onEdit;
		this.onEquip = onEquip;
		this.onDelete = onDelete;
	}

	private int iconAreaHeight() {
		return getHeight() - BOTTOM_STRIP_HEIGHT;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		int localY = (int) click.y() - getY();
		if (localY < iconAreaHeight()) {
			onEquip.run();
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
		boolean iconHovered = isHovered() && mouseY < getY() + iconHeight;

		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), iconHeight, 5, theme.surfaceWithOpacity());
		VeloDraw.strokeRect(context, getX(), getY(), getWidth(), iconHeight, equipped ? theme.accentStart() : 0x33FFFFFF);

		boolean showHit = iconHovered && definition.hitMode() != CrosshairDefinition.HitMode.NONE;
		Identifier texture = switch (showHit ? definition.hitMode() : CrosshairDefinition.HitMode.NONE) {
			case SEPARATE_IMAGE -> CrosshairManager.textureIdentifier(definition.id(), true);
			case COLOR_SWAP -> CrosshairManager.colorSwapTextureIdentifier(definition);
			case NONE -> CrosshairManager.textureIdentifier(definition.id(), false);
		};
		int iconSize = iconHeight - 8;
		int iconX = getX() + (getWidth() - iconSize) / 2;
		int iconY = getY() + 4;
		// 12-arg overload, not 10 - see CustomCrosshairModule for why (the
		// shorter form ties the sampled texture region to the on-screen
		// size, which tiles/repeats a small texture instead of scaling it).
		int canvas = definition.canvasSize();
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, iconX, iconY, 0f, 0f,
				iconSize, iconSize, canvas, canvas, canvas, canvas);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String name = trimToWidth(definition.name(), getWidth() - 6);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name,
				getX() + (getWidth() - nameWidth) / 2, getY() + iconHeight - 12, theme.text());

		int stripY = getY() + iconHeight;
		int halfW = getWidth() / 2;
		// Clicking the icon itself now equips (see onClick) - this button is
		// just "edit" now, so it no longer needs the equipped-state tint the
		// old equip button had; that state is still shown via the icon's own
		// border highlight above.
		boolean editHovered = isHovered() && mouseY >= stripY && mouseX < getX() + halfW;
		boolean deleteHovered = isHovered() && mouseY >= stripY && mouseX >= getX() + halfW;
		int editColor = editHovered ? 0x66FFFFFF : 0x33FFFFFF;
		int deleteColor = deleteHovered ? 0xAAFF5555 : 0x33FF5555;
		VeloDraw.fillRounded(context, getX(), stripY, halfW - 1, BOTTOM_STRIP_HEIGHT, 3, editColor);
		VeloDraw.fillRounded(context, getX() + halfW + 1, stripY, halfW - 1, BOTTOM_STRIP_HEIGHT, 3, deleteColor);
		// Single-glyph icons instead of words - "Edit"/"Delete" routinely
		// overflowed half of a 64px tile even scaled down to fit.
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
