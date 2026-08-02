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

	private static final int TRACK_WIDTH = 34;
	private static final int TRACK_HEIGHT = 16;

	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private float knobProgress;
	private boolean initialized;
	private long lastNanos;

	public VeloToggle(int x, int y, int labelWidth, Text label, BooleanSupplier getter, Consumer<Boolean> setter) {
		super(x, y, labelWidth + 10 + TRACK_WIDTH, TRACK_HEIGHT + 4, label);
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
		float dt = (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;
		knobProgress = VeloAnim.step(knobProgress, on ? 1f : 0f, dt);

		int trackX = getX();
		int trackY = getY() + (getHeight() - TRACK_HEIGHT) / 2;

		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(),
				getX() + TRACK_WIDTH + 10, getY() + (getHeight() - 8) / 2, theme.text());

		int off = 0xFF3A3230;
		int trackColor = VeloAnim.lerpArgb(off, theme.accentStart(), knobProgress);
		VeloDraw.fillRounded(context, trackX, trackY, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2, trackColor);

		int knobSize = TRACK_HEIGHT - 4;
		int knobX = trackX + 2 + Math.round((TRACK_WIDTH - knobSize - 4) * knobProgress);
		int knobY = trackY + 2;
		VeloDraw.fillRounded(context, knobX, knobY, knobSize, knobSize, knobSize / 2, 0xFFFFFFFF);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
		builder.put(NarrationPart.HINT, getter.getAsBoolean() ? "on" : "off");
	}
}
