package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A settings row: label on the left, the current value as a small chip on the right (a key name,
 * a choice with a chevron, a color swatch). Clicking anywhere on the row acts - replaces the old
 * "Label: value  ▸" full-width buttons, which read as a wall of identical buttons.
 */
public final class VeloValueRow extends ClickableWidget {

	public enum Kind { KEY, CHOICE, COLOR, LINK }

	private final Kind kind;
	private final Supplier<String> value;
	private final IntSupplier swatch;
	private final Consumer<VeloValueRow> onPress;
	private boolean listening;
	private float hover;
	private long lastNanos;
	private long pressedAt;

	public VeloValueRow(int x, int y, int width, int height, Text label, Kind kind, Supplier<String> value,
			IntSupplier swatch, Consumer<VeloValueRow> onPress) {
		super(x, y, width, height, label);
		this.kind = kind;
		this.value = value;
		this.swatch = swatch;
		this.onPress = onPress;
	}

	/** Shows the row in its "waiting for a key press" state (pulsing accent chip). */
	public VeloValueRow listening(boolean listening) {
		this.listening = listening;
		return this;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		pressedAt = System.nanoTime();
		onPress.accept(this);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);

		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		if (hover > 0.01f) {
			VeloDraw.fillRounded(context, x - 4, y, w + 8, h, 6, VeloUi.withAlpha(theme.text(), Math.round(0x0E * hover)));
		}

		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String shown = listening ? "Press a key..." : value.get();
		int chipTextWidth = textRenderer.getWidth(shown);
		int extra = switch (kind) {
			case CHOICE, LINK -> 10;
			case COLOR -> 14;
			default -> 0;
		};
		int chipWidth = Math.min(w / 2, chipTextWidth + 14 + extra);
		int chipHeight = Math.min(h - 4, 16);
		int chipX = x + w - chipWidth;
		int chipY = y + (h - chipHeight) / 2;

		context.drawTextWithShadow(textRenderer, VeloUi.trimStyled(getMessage(), w - chipWidth - 10, t -> t),
				x, y + (h - 8) / 2, VeloStyle.text());

		VeloStyle.pushScale(context, chipX + chipWidth / 2f, chipY + chipHeight / 2f, VeloStyle.pressScale(pressedAt));
		int chipBg;
		int chipBorder;
		if (listening) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(now / 1.6e8);
			chipBg = VeloUi.withAlpha(theme.accentStart(), Math.round(0x40 + 0x30 * pulse));
			chipBorder = theme.accentStart() | 0xFF000000;
		} else {
			chipBg = VeloAnim.lerpArgb(VeloStyle.sunken(), VeloStyle.cardHover(), hover);
			chipBorder = VeloAnim.lerpArgb(VeloStyle.border(), VeloUi.withAlpha(theme.accentStart(), 0xB0), hover);
		}
		VeloDraw.fillRounded(context, chipX, chipY, chipWidth, chipHeight, 5, chipBg);
		VeloDraw.strokeRounded(context, chipX, chipY, chipWidth, chipHeight, 5, chipBorder);
		int textX = chipX + 7;
		if (kind == Kind.COLOR && swatch != null) {
			VeloDraw.fillRounded(context, chipX + 5, chipY + 4, chipHeight - 8, chipHeight - 8, 2, swatch.getAsInt() | 0xFF000000);
			VeloDraw.strokeRounded(context, chipX + 5, chipY + 4, chipHeight - 8, chipHeight - 8, 2, 0x40FFFFFF);
			textX += chipHeight - 6;
		}
		String fitted = VeloUi.trim(shown, chipWidth - 14 - extra);
		context.drawTextWithShadow(textRenderer, fitted, textX, chipY + (chipHeight - 8) / 2, VeloStyle.text());
		if (kind == Kind.CHOICE || kind == Kind.LINK) {
			VeloDraw.chevron(context, chipX + chipWidth - 8.5f, chipY + chipHeight / 2f, 5f, 1.2f,
					kind == Kind.CHOICE ? 1 : 0, VeloStyle.textMuted());
		}
		VeloStyle.popScale(context);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
		builder.put(NarrationPart.HINT, value.get());
	}
}
