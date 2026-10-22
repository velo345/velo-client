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

	/** The name strip under the picture. */
	public static final int NAME_H = 16;
	private static final int BOTTOM_STRIP_HEIGHT = NAME_H;
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
		// Shows it on you; Equip / Edit / Delete live under the preview (launcher Locker).
		onToggleEquip.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);
		phase = VeloCapeRender.advancePhase(phase, hover, dt);
		lastNanos = now;
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		// Same tile as the Wings / Auras tabs: graphite card, picture on a lighter well, name bottom-left,
		// green edge + tick when worn, white edge when it's the one on the preview.
		VeloDraw.fillRounded(context, x, y, w, h, 7, VeloAnim.lerpArgb(0xFF16161C, 0xFF1C1C23, hover));
		VeloDraw.fillRounded(context, x + 3, y + 3, w - 6, h - NAME_H - 4, 5, VeloAnim.lerpArgb(0xFF1C1C23, 0xFF22222A, hover));
		int edge = equipped ? 0xFF3DDC97 : selected ? 0xD0FFFFFF : VeloAnim.lerpArgb(0x14FFFFFF, 0x40FFFFFF, hover);
		VeloDraw.strokeRounded(context, x, y, w, h, 7, edge);
		int area = h - NAME_H - 4;
		// Loaded off-thread: a soft pulse until the cape is ready, so the Locker opens instantly.
		Identifier texture = CapeManager.textureIfReady(definition);
		if (texture == null) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 2.5e8);
			int pw = Math.max(8, (w - 6) / 3);
			int ph = Math.max(10, area - 30);
			VeloDraw.fillRounded(context, x + (w - pw) / 2, y + 3 + (area - ph) / 2, pw, ph, 4,
					VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x0A + 0x10 * pulse)));
		} else {
			VeloCapeRender.draw(context, texture, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, x + w / 2f, y + 3 + area - 8,
					area - 18, VeloCapeRender.idleYaw(phase, hover));
		}

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int textY = y + h - NAME_H + 4;
		int right = x + w - 7;
		if (equipped) {
			int cx = right - 6;
			int cy = textY + 4;
			for (int i = 0; i < 3; i++) {
				context.fill(cx + i, cy + i - 1, cx + i + 1, cy + i + 1, 0xFF3DDC97);
			}
			for (int i = 0; i < 5; i++) {
				context.fill(cx + 2 + i, cy + 1 - i, cx + 3 + i, cy + 3 - i, 0xFF3DDC97);
			}
			right -= 12;
		}
		Text name = net.veloclient.velo.client.gui.title.MenuKit.fit(textRenderer, net.veloclient.velo.client.gui.title.MenuKit.uiBold(definition.name()), right - (x + 7));
		net.veloclient.velo.client.gui.title.MenuKit.text(context, textRenderer, name, x + 7, textY, 0xFFF4F4F6);
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
