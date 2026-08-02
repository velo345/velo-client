package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
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
	private long lastNanos = -1;

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
			onPress.accept(this);
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		boolean hovered = this.active && isHovered();
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;
		hoverProgress = VeloAnim.step(hoverProgress, hovered ? 1f : 0f, dt);

		boolean tinted = primary || selected;
		int base = tinted ? theme.accentStart() : theme.surfaceWithOpacity();
		int hover = tinted ? lighten(theme.accentStart(), 0.15f) : VeloAnim.lerpArgb(theme.surfaceWithOpacity(), 0xFFFFFFFF, 0.08f);
		int bg = VeloAnim.lerpArgb(base, hover, hoverProgress);
		if (!this.active) {
			bg = VeloAnim.lerpArgb(bg, 0xFF000000, 0.5f);
		}

		context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
		int borderColor = tinted ? 0x00000000 : VeloAnim.lerpArgb(0x00FFFFFF & theme.text() | 0x22000000, theme.accentStart(), hoverProgress);
		if (!tinted) {
			VeloDraw.strokeRect(context, getX(), getY(), getWidth(), getHeight(), borderColor);
		}

		int textColor = tinted ? 0xFFFFFFFF : theme.text();
		if (!this.active) {
			textColor = 0xFF888888;
		}
		context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(),
				getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, textColor);
	}

	private static int lighten(int argb, float amount) {
		return VeloAnim.lerpArgb(argb, 0xFFFFFFFF, amount);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
