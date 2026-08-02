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
		double value = getter.getAsDouble();
		double fraction = max > min ? (value - min) / (max - min) : 0;
		fraction = Math.max(0, Math.min(1, fraction));

		int trackY = getY() + getHeight() - 5;
		context.fill(getX(), trackY, getX() + getWidth(), trackY + 3, 0xFF3A3230);
		int filledEnd = getX() + (int) Math.round(getWidth() * fraction);
		context.fill(getX(), trackY, filledEnd, trackY + 3, theme.accentStart());

		int handleSize = 8;
		// Centering the handle on filledEnd let it poke out past the
		// track's own right edge once dragged to the max value (or the
		// left edge at the min) - clamp its travel range so it always
		// stays fully within the widget's own bounds.
		int handleX = Math.max(getX(), Math.min(getX() + getWidth() - handleSize, filledEnd - handleSize / 2));
		VeloDraw.fillRounded(context, handleX, trackY - 3, handleSize, handleSize + 6, 2, isHovered() ? lighten(theme.accentStart()) : theme.accentStart());

		String valueText = labelFormatter != null ? labelFormatter.apply(value) : String.format("%.2f", value);
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, label + ": " + valueText,
				getX(), getY(), theme.text());
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
