package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/** A custom-drawn horizontal slider: filled track up to the handle, red accent, live value label. */
public final class VeloSlider extends ClickableWidget {

	private final double min;
	private final double max;
	private final DoubleSupplier getter;
	private final DoubleConsumer setter;
	private final DoubleUnaryOperator formatterStep;
	private final java.util.function.DoubleFunction<String> labelFormatter;
	private final String label;
	private float hover;
	private float shown = -1;
	private long lastNanos;

	public VeloSlider(int x, int y, int width, int height, String label, double min, double max,
			DoubleSupplier getter, DoubleConsumer setter, DoubleUnaryOperator snap, java.util.function.DoubleFunction<String> labelFormatter) {
		super(x, y, width, height, Text.literal(label));
		this.label = label;
		this.min = min;
		this.max = max;
		this.getter = getter;
		this.setter = setter;
		this.formatterStep = snap;
		this.labelFormatter = labelFormatter;
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		applyFromMouseX(click.x());
	}

	@Override
	protected void onDrag(Click click, double offsetX, double offsetY) {
		applyFromMouseX(click.x());
	}

	private void applyFromMouseX(double mouseX) {
		double fraction = (mouseX - getX()) / (double) getWidth();
		fraction = Math.max(0, Math.min(1, fraction));
		double value = min + fraction * (max - min);
		if (formatterStep != null) {
			value = formatterStep.applyAsDouble(value);
		}
		setter.accept(value);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);

		double value = getter.getAsDouble();
		double target = max > min ? (value - min) / (max - min) : 0;
		target = Math.max(0, Math.min(1, target));
		// The fill glides to new values (clicks, resets) instead of jumping.
		shown = shown < 0 ? (float) target : VeloAnim.step(shown, (float) target, dt * 1.6f);

		float knob = 9f + hover * 1.5f;
		float trackX = getX() + knob / 2f;
		float trackWidth = getWidth() - knob;
		float trackY = getY() + getHeight() - 6.5f;
		float trackHeight = 4f;
		VeloDraw.fillRounded(context, getX(), trackY, getWidth(), trackHeight, 2f, VeloStyle.sunken());
		float fillEnd = trackX + trackWidth * shown;
		if (fillEnd - getX() > 1f) {
			VeloDraw.fillRounded(context, getX(), trackY, fillEnd - getX(), trackHeight, 2f, theme.accentStart() | 0xFF000000);
		}
		float knobX = fillEnd - knob / 2f;
		float knobY = trackY + trackHeight / 2f - knob / 2f;
		if (hover > 0.01f) {
			VeloDraw.fillCircle(context, fillEnd, knobY + knob / 2f, knob / 2f + 3f * hover, VeloUi.withAlpha(theme.accentStart(), Math.round(0x40 * hover)));
		}
		VeloDraw.fillCircle(context, fillEnd, knobY + knob / 2f + 0.6f, knob / 2f, 0x50000000);
		VeloDraw.fillCircle(context, fillEnd, knobY + knob / 2f, knob / 2f, 0xFFFFFFFF);
		VeloDraw.fillCircle(context, fillEnd, knobY + knob / 2f, knob / 2f - 2.5f, theme.accentStart() | 0xFF000000);

		String valueText = labelFormatter != null ? labelFormatter.apply(value) : String.format("%.2f", value);
		// Label left (muted), value right - if space is short the label is shortened, never the value.
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int valueWidth = textRenderer.getWidth(valueText);
		String shownLabel = VeloUi.trim(label, Math.max(20, getWidth() - valueWidth - 8));
		context.drawTextWithShadow(textRenderer, shownLabel, getX(), getY(), VeloStyle.textMuted());
		context.drawTextWithShadow(textRenderer, valueText, getX() + getWidth() - valueWidth, getY(), VeloStyle.text());
	}

	private static int lighten(int argb) {
		return VeloAnim.lerpArgb(argb, 0xFFFFFFFF, 0.2f);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
		builder.put(NarrationPart.HINT, String.valueOf(getter.getAsDouble()));
	}
}
