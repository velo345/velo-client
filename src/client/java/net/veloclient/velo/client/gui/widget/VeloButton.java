package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.Consumer;

/**
 * Flat, theme-colored button with an animated hover/press response - the
 * mod panel's own widget instead of vanilla's beveled gray button texture.
 */
public class VeloButton extends ClickableWidget {

	private final Consumer<VeloButton> onPress;
	private float hoverProgress;
	private boolean primary;
	private boolean selected;
	private long lastNanos;
	private long pressedAt;

	public VeloButton(int x, int y, int width, int height, Text message, Consumer<VeloButton> onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	public VeloButton primary() {
		this.primary = true;
		return this;
	}

	/** Tints the button as "currently active" (e.g. the selected sidebar category), independent of hover. */
	public VeloButton selected(boolean selected) {
		this.selected = selected;
		return this;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (this.active && this.visible) {
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			pressedAt = System.nanoTime();
			onPress.accept(this);
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		boolean hovered = this.active && isHovered();
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		hoverProgress = VeloAnim.step(hoverProgress, hovered ? 1f : 0f, dt);

		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int radius = Math.min(VeloStyle.RADIUS_CONTROL, h / 2);
		VeloStyle.pushScale(context, x + w / 2f, y + h / 2f, VeloStyle.pressScale(pressedAt));

		boolean tinted = primary || selected;
		if (tinted) {
			int top = VeloAnim.lerpArgb(VeloStyle.accent(), VeloStyle.accentHover(), hoverProgress);
			int bottom = VeloAnim.lerpArgb(VeloStyle.accentEnd(), VeloStyle.accent(), hoverProgress * 0.5f);
			if (!this.active) {
				top = VeloAnim.lerpArgb(top, 0xFF000000, 0.5f);
				bottom = VeloAnim.lerpArgb(bottom, 0xFF000000, 0.5f);
			}
			if (hoverProgress > 0.01f) {
				VeloDraw.shadow(context, x, y, w, h, radius, 6, 1, VeloStyle.accentGlow(Math.round(0x50 * hoverProgress)));
			}
			VeloDraw.fillRoundedGradient(context, x, y, w, h, radius, top, bottom);
		} else {
			int bg = VeloAnim.lerpArgb(VeloStyle.card(), VeloStyle.cardHover(), hoverProgress);
			if (!this.active) {
				bg = VeloAnim.lerpArgb(bg, 0xFF000000, 0.35f);
			}
			VeloDraw.fillRounded(context, x, y, w, h, radius, bg);
			VeloDraw.strokeRounded(context, x, y, w, h, radius,
					VeloAnim.lerpArgb(VeloStyle.border(), VeloUi.withAlpha(theme.accentStart(), 0xB0), hoverProgress));
		}

		int textColor = tinted ? 0xFFFFFFFF : VeloStyle.text();
		if (!this.active) {
			textColor = VeloStyle.textFaint();
		}
		// Long labels are cut with "..." instead of spilling past the button (and the window).
		context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,
				VeloUi.trimStyled(getMessage(), w - 8, TitleScreenTheme::bodyFont),
				x + w / 2, y + (h - 8) / 2, textColor);
		VeloStyle.popScale(context);
	}

	private static int lighten(int argb, float amount) {
		return VeloAnim.lerpArgb(argb, 0xFFFFFFFF, amount);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
