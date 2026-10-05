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

	private float hover;
	private boolean selected;
	private float phase = (float) (Math.random() * Math.PI * 2);
	private long lastNanos;

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

	/** Outlines the tile as the one currently shown in the preview. */
	public VeloCapeTile selected(boolean selected) {
		this.selected = selected;
		return this;
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
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);
		phase = VeloCapeRender.advancePhase(phase, hover, dt);
		lastNanos = now;
		int iconHeight = iconAreaHeight();
		int x = getX();
		int y = getY();
		int w = getWidth();

		drawStage(context, x, y, w, iconHeight, hover, selected);
		Identifier texture = CapeManager.textureIdentifier(definition);
		VeloCapeRender.draw(context, texture, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, x + w / 2f, y + iconHeight - 20,
				iconHeight - 34, VeloCapeRender.idleYaw(phase, hover));
		if (equipped) {
			VeloUi.pill(context, x + 6, y + 6, 54, 13, "Equipped", 2, -1, -1, () -> { });
		}

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String name = trimToWidth(definition.name(), w - 10);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name, x + (w - nameWidth) / 2, y + iconHeight - 13, VeloStyle.text());

		int stripY = y + iconHeight + 2;
		int halfW = w / 2;
		boolean editHovered = isHovered() && mouseY >= stripY && mouseX < x + halfW;
		boolean deleteHovered = isHovered() && mouseY >= stripY && mouseX >= x + halfW;
		VeloUi.pill(context, x, stripY, halfW - 2, BOTTOM_STRIP_HEIGHT - 2, "Edit", 0, editHovered ? mouseX : -1, editHovered ? mouseY : -1, () -> { });
		VeloUi.pill(context, x + halfW + 2, stripY, halfW - 2, BOTTOM_STRIP_HEIGHT - 2, "Delete", deleteHovered ? 3 : 0,
				deleteHovered ? mouseX : -1, deleteHovered ? mouseY : -1, () -> { });
	}

	/** The shared cape "stage": a card with an accent glow rising from the floor. */
	public static void drawStage(DrawContext context, int x, int y, int w, int h, float hover, boolean highlighted) {
		Theme theme = ThemeManager.active();
		if (hover > 0.01f) {
			VeloDraw.shadow(context, x, y, w, h, VeloStyle.RADIUS_CARD, 8, 3, VeloUi.withAlpha(0xFF000000, Math.round(0x55 * hover)));
		}
		int card = VeloAnim.lerpArgb(VeloStyle.card(), VeloStyle.cardHover(), hover);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, VeloStyle.RADIUS_CARD, card,
				VeloAnim.lerpArgb(card, theme.accentStart() | 0xFF000000, 0.18f + 0.1f * hover));
		VeloDraw.strokeRounded(context, x, y, w, h, VeloStyle.RADIUS_CARD,
				highlighted ? (theme.accentStart() | 0xFF000000) : VeloAnim.lerpArgb(VeloStyle.border(), VeloStyle.borderStrong(), hover));
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
