package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.IntSupplier;

/**
 * A square icon button for the Velo menu's top bar (Friends, Waypoints, Settings...), with a
 * hover label and an optional count badge - or, with {@link #withLabel()}, a wider accent button
 * showing icon + text (used for "Edit HUD"). Client features live here so they're never mistaken
 * for module categories.
 */
public final class VeloIconButton extends ClickableWidget {

	private static final int ICON_SOURCE_SIZE = 1024;

	private final Identifier icon;
	private final Runnable action;
	private IntSupplier badge = () -> 0;
	private boolean accentWithLabel;
	private boolean plainWithLabel;
	private java.util.function.BooleanSupplier active = () -> false;
	private float hover;
	private long lastNanos;
	private long pressedAt;

	public VeloIconButton(int x, int y, int size, Identifier icon, Text label, Runnable action) {
		super(x, y, size, size, label);
		this.icon = icon;
		this.action = action;
	}

	/** Accent-colored button that shows its label next to the icon. Width = icon + text. */
	public VeloIconButton withLabel() {
		this.accentWithLabel = true;
		setWidth(getHeight() + MinecraftClient.getInstance().textRenderer.getWidth(getMessage()) + 12);
		return this;
	}

	/** Like {@link #withLabel()} but in the normal card style, not accent-filled. */
	public VeloIconButton withPlainLabel() {
		this.plainWithLabel = true;
		setWidth(getHeight() + MinecraftClient.getInstance().textRenderer.getWidth(getMessage()) + 12);
		return this;
	}

	/** Keeps the hover look (accent border, bright icon) while {@code active} is true, e.g. its menu is open. */
	public VeloIconButton active(java.util.function.BooleanSupplier active) {
		this.active = active;
		return this;
	}

	public VeloIconButton badge(IntSupplier count) {
		this.badge = count;
		return this;
	}

	/** Text for the hover label (null for labelled buttons, which already show it). */
	public String tooltip() {
		return accentWithLabel || plainWithLabel || !isHovered() ? null : getMessage().getString();
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		pressedAt = System.nanoTime();
		action.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		hover = VeloAnim.step(hover, isHovered() || active.getAsBoolean() ? 1f : 0f, VeloStyle.frameDelta(lastNanos, now));
		lastNanos = now;
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		VeloStyle.pushScale(context, x + w / 2f, y + h / 2f, VeloStyle.pressScale(pressedAt));
		int iconSize = Math.max(10, h - 10);
		int iconColor;
		if (accentWithLabel) {
			int top = VeloAnim.lerpArgb(VeloStyle.accent(), 0xFFFFFFFF, 0.12f * hover);
			VeloDraw.fillRoundedGradient(context, x, y, w, h, 7, top, VeloAnim.lerpArgb(top, 0xFF000000, 0.12f));
			iconColor = 0xFFFFFFFF;
		} else {
			VeloDraw.fillRounded(context, x, y, w, h, 7, VeloAnim.lerpArgb(VeloStyle.card(), VeloStyle.cardHover(), hover));
			VeloDraw.strokeRounded(context, x, y, w, h, 7, VeloAnim.lerpArgb(VeloStyle.border(), VeloStyle.accent(), hover * 0.8f));
			iconColor = VeloAnim.lerpArgb(VeloStyle.textMuted(), VeloStyle.text(), hover);
		}
		int iconX = accentWithLabel || plainWithLabel ? x + 6 : x + (w - iconSize) / 2;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, iconX, y + (h - iconSize) / 2, 0f, 0f, iconSize, iconSize,
				ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, iconColor);
		if (accentWithLabel) {
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(), iconX + iconSize + 5, y + (h - 8) / 2, 0xFFFFFFFF);
		} else if (plainWithLabel) {
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(), iconX + iconSize + 5, y + (h - 8) / 2, iconColor);
		}
		int count = badge.getAsInt();
		if (count > 0) {
			String text = count > 9 ? "9+" : String.valueOf(count);
			int bw = Math.max(9, MinecraftClient.getInstance().textRenderer.getWidth(text) + 4);
			VeloDraw.fillRounded(context, x + w - bw + 3, y - 3, bw, 9, 4, 0xFFE53935);
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x + w - bw + 3 + (bw - MinecraftClient.getInstance().textRenderer.getWidth(text)) / 2,
					y - 2, 0xFFFFFFFF);
		}
		VeloStyle.popScale(context);
	}

	/** Draws a hover label under a button (call after everything else so it sits on top). */
	public static void drawTooltip(DrawContext context, VeloIconButton button) {
		String text = button.tooltip();
		if (text == null) {
			return;
		}
		int w = MinecraftClient.getInstance().textRenderer.getWidth(text) + 10;
		int x = Math.max(2, button.getX() + button.getWidth() / 2 - w / 2);
		int y = button.getY() + button.getHeight() + 4;
		VeloDraw.fillRounded(context, x, y, w, 14, 5, 0xF0101014);
		VeloDraw.strokeRounded(context, x, y, w, 14, 5, VeloStyle.border());
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x + 5, y + 3, VeloStyle.text());
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
