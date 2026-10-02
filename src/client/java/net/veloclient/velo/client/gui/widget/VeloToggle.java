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

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A modern pill-shaped on/off switch with an animated sliding knob, used everywhere a module is toggled. */
public final class VeloToggle extends ClickableWidget {

	private static final int TRACK_WIDTH = 28;
	private static final int TRACK_HEIGHT = 15;

	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private float knobProgress;
	private boolean initialized;
	private long lastNanos;
	private float hover;

	/** A full-width settings row: label on the left, switch on the right; the whole row is clickable. */
	public VeloToggle(int x, int y, int width, Text label, BooleanSupplier getter, Consumer<Boolean> setter) {
		super(x, y, width, TRACK_HEIGHT + 6, label);
		this.getter = getter;
		this.setter = setter;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		boolean newValue = !getter.getAsBoolean();
		setter.accept(newValue);
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, newValue ? 1.1f : 0.9f));
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		boolean on = getter.getAsBoolean();
		long now = System.nanoTime();
		if (!initialized) {
			knobProgress = on ? 1f : 0f;
			initialized = true;
			lastNanos = now;
		}
		float dt = Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		knobProgress = VeloAnim.step(knobProgress, on ? 1f : 0f, dt);

		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);
		if (hover > 0.01f && getWidth() > TRACK_WIDTH) {
			VeloDraw.fillRounded(context, getX() - 4, getY(), getWidth() + 8, getHeight(), 6, VeloUi.withAlpha(theme.text(), Math.round(0x0E * hover)));
		}
		if (!getMessage().getString().isEmpty()) {
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
					VeloUi.trimStyled(getMessage(), getWidth() - TRACK_WIDTH - 12, t -> t),
					getX(), getY() + (getHeight() - 8) / 2, theme.text());
		}
		drawSwitch(context, getX() + getWidth() - TRACK_WIDTH, getY() + (getHeight() - TRACK_HEIGHT) / 2f, TRACK_WIDTH, TRACK_HEIGHT, knobProgress, hover);
	}

	/**
	 * The shared switch look (also used inside module tiles): a recessed track that fills with
	 * the accent gradient as it turns on, and a white knob gliding with sub-pixel precision.
	 */
	public static void drawSwitch(DrawContext context, float x, float y, float width, float height, float progress, float hover) {
		Theme theme = ThemeManager.active();
		float radius = height / 2f;
		int off = VeloAnim.lerpArgb(VeloStyle.sunken(), VeloUi.withAlpha(theme.text(), 0x40), hover * 0.4f);
		VeloDraw.fillRounded(context, x, y, width, height, radius, off);
		VeloDraw.strokeRounded(context, x, y, width, height, radius, 1f, VeloUi.withAlpha(theme.text(), Math.round(0x22 * (1f - progress))));
		if (progress > 0.01f) {
			VeloDraw.fillRounded(context, x, y, width, height, radius, VeloUi.withAlpha(theme.accentStart(), Math.round(255 * progress)));
		}
		float inset = Math.max(1.5f, height * 0.16f);
		float knob = height - inset * 2;
		float knobX = x + inset + (width - knob - inset * 2) * progress;
		VeloDraw.fillRounded(context, knobX, y + inset + 0.6f, knob, knob, knob / 2f, 0x40000000);
		int knobColor = VeloAnim.lerpArgb(0xFFD8D8DE, 0xFFFFFFFF, Math.max(progress, hover));
		VeloDraw.fillRounded(context, knobX, y + inset, knob, knob, knob / 2f, knobColor);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
		builder.put(NarrationPart.HINT, getter.getAsBoolean() ? "on" : "off");
	}
}
