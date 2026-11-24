package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.MinecraftClient;
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
			net.veloclient.velo.client.sound.VeloUiSounds.click(1.0f);
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
		boolean glass = VeloStyle.glass();
		float radius = glass ? Math.min(h / 2f, 10f) : Math.min(VeloStyle.RADIUS_CONTROL, h / 2);   // glass: pills (tall buttons get corners, not a stadium)
		VeloStyle.pushScale(context, x + w / 2f, y + h / 2f, VeloStyle.pressScale(pressedAt));

		// 0.9.7 (launcher look): primary = flat accent; selected (a sidebar entry) = soft accent tint with white text;
		// everything else = graphite with a hairline that brightens on hover.
		boolean tinted = primary;
		if (primary) {
			int fill = VeloAnim.lerpArgb(VeloStyle.accent(), VeloStyle.accentHover(), hoverProgress);
			if (!this.active) {
				fill = VeloAnim.lerpArgb(fill, 0xFF000000, 0.5f);
			}
			// launcher look: flat, no glow on hover
			VeloDraw.fillRounded(context, x, y, w, h, radius, fill);
		} else if (selected && glass) {
			// glass: the selected one is a white pill with dark text (like the R-Shift tabs)
			tinted = true;
			VeloDraw.fillRounded(context, x, y, w, h, radius, VeloAnim.lerpArgb(0xFFFFFFFF, 0xFFE9E9EC, hoverProgress));
		} else if (selected) {
			VeloDraw.fillRounded(context, x, y, w, h, radius, VeloUi.withAlpha(theme.accentStart(), 0x33 + Math.round(0x14 * hoverProgress)));
		} else {
			int bg = glass ? VeloAnim.lerpArgb(0x16FFFFFF, 0x26FFFFFF, hoverProgress) : VeloAnim.lerpArgb(0x0DFFFFFF, 0x1AFFFFFF, hoverProgress);
			if (!this.active) {
				bg = 0x08FFFFFF;
			}
			VeloDraw.fillRounded(context, x, y, w, h, radius, bg);
			if (!glass) {
				VeloDraw.strokeRounded(context, x, y, w, h, (int) radius, VeloAnim.lerpArgb(0x17FFFFFF, 0x38FFFFFF, hoverProgress));
			}
		}

		int textColor = tinted ? (glass ? 0xFF141416 : VeloStyle.onAccent()) : selected ? 0xFFFFFFFF : VeloStyle.text();
		if (!this.active) {
			textColor = VeloStyle.textFaint();
		}
		// Long labels are cut with "..." instead of spilling past the button (and the window).
		net.minecraft.text.Text label = VeloUi.trimStyled(getMessage(), w - 8, TitleScreenTheme::bodyFont);
		if (glass) {
			// no shadow on glass (dark text on a white pill would get a smear)
			var tr = MinecraftClient.getInstance().textRenderer;
			net.veloclient.velo.client.gui.title.MenuKit.text(context, tr, label, x + (w - net.veloclient.velo.client.gui.title.MenuKit.width(tr, label)) / 2, y + (h - 8) / 2, textColor);
		} else {
			context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, label, x + w / 2, y + (h - 8) / 2, textColor);
		}
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
