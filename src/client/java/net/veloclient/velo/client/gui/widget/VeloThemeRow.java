package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * One row in {@link net.veloclient.velo.client.gui.ThemeEditorScreen}'s theme
 * list: click the name to activate it, the pencil to edit it (activates it
 * first, for live preview while editing), "✕" to delete it. Built-in themes
 * only ever get the name area - {@link #editable} controls whether the icon
 * zone is drawn/clickable at all, since they can't be edited or deleted.
 */
public final class VeloThemeRow extends ClickableWidget {

	private static final int ICON_ZONE_WIDTH = 40;

	private final boolean active;
	private final boolean editable;
	private final Runnable onActivate;
	private final Runnable onEdit;
	private final Runnable onDelete;

	public VeloThemeRow(int x, int y, int width, int height, String displayLabel,
			boolean active, boolean editable, Runnable onActivate, Runnable onEdit, Runnable onDelete) {
		super(x, y, width, height, Text.literal(displayLabel));
		this.active = active;
		this.editable = editable;
		this.onActivate = onActivate;
		this.onEdit = onEdit;
		this.onDelete = onDelete;
	}

	private int nameZoneWidth() {
		return editable ? getWidth() - ICON_ZONE_WIDTH : getWidth();
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (!editable || click.x() < getX() + nameZoneWidth()) {
			onActivate.run();
			return;
		}
		boolean leftIcon = click.x() < getX() + nameZoneWidth() + ICON_ZONE_WIDTH / 2.0;
		if (leftIcon) {
			onEdit.run();
		} else {
			onDelete.run();
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int nameWidth = nameZoneWidth();
		boolean nameHovered = isHovered() && mouseX < getX() + nameWidth;

		VeloDraw.fillRounded(context, getX(), getY(), nameWidth, getHeight(), 3,
				active ? theme.accentStart() & 0x55FFFFFF | 0x33000000 : theme.surfaceWithOpacity());
		VeloDraw.strokeRounded(context, getX(), getY(), nameWidth, getHeight(), 3, active ? theme.accentStart() : (nameHovered ? 0x33FFFFFF : 0x1AFFFFFF));

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String label = getMessage().getString() + (active ? "  ✓" : "");
		context.drawTextWithShadow(textRenderer, label, getX() + 6, getY() + (getHeight() - textRenderer.fontHeight) / 2, theme.text());

		if (editable) {
			int iconX = getX() + nameWidth;
			int halfIcon = ICON_ZONE_WIDTH / 2;
			boolean editHovered = isHovered() && mouseX >= iconX && mouseX < iconX + halfIcon;
			boolean deleteHovered = isHovered() && mouseX >= iconX + halfIcon;
			VeloDraw.fillRounded(context, iconX, getY(), halfIcon - 1, getHeight(), 3, editHovered ? 0x66FFFFFF : 0x33FFFFFF);
			VeloDraw.fillRounded(context, iconX + halfIcon + 1, getY(), halfIcon - 1, getHeight(), 3, deleteHovered ? 0xAAFF5555 : 0x33FF5555);
			String editGlyph = "✎";
			String deleteGlyph = "✕";
			int labelY = getY() + (getHeight() - textRenderer.fontHeight) / 2;
			context.drawTextWithShadow(textRenderer, editGlyph, iconX + (halfIcon - textRenderer.getWidth(editGlyph)) / 2, labelY, 0xFFFFFFFF);
			context.drawTextWithShadow(textRenderer, deleteGlyph, iconX + halfIcon + (halfIcon - textRenderer.getWidth(deleteGlyph)) / 2, labelY, 0xFFFFFFFF);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
