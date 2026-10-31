package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.wear.WearTint;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A bodywear colour pick: "its own colours", the {@link WearTint#PRESETS quick colours} and a "+" that opens the full
 * colour picker. Works in {@link WearTint} codes. Wraps onto more rows when narrow - {@link #heightFor} says how tall.
 */
public final class VeloColorSwatches extends ClickableWidget {

	private static final int SIZE = 14;
	private static final int GAP = 4;
	private static final int LABEL = 12;

	private final IntSupplier getter;
	private final IntConsumer setter;

	public VeloColorSwatches(int x, int y, int width, IntSupplier getter, IntConsumer setter) {
		super(x, y, width, heightFor(width), Text.literal("Colour"));
		this.getter = getter;
		this.setter = setter;
	}

	/** Cells: its own, the presets, custom. */
	private static int cells() {
		return WearTint.PRESETS.length + 2;
	}

	private static int perRow(int width) {
		return Math.max(1, (width + GAP) / (SIZE + GAP));
	}

	public static int heightFor(int width) {
		int rows = (cells() + perRow(width) - 1) / perRow(width);
		return LABEL + rows * (SIZE + GAP) - GAP;
	}

	private int cellX(int i) {
		return getX() + (i % perRow(getWidth())) * (SIZE + GAP);
	}

	private int cellY(int i) {
		return getY() + LABEL + (i / perRow(getWidth())) * (SIZE + GAP);
	}

	private int cellAt(double mx, double my) {
		for (int i = 0; i < cells(); i++) {
			if (mx >= cellX(i) && mx < cellX(i) + SIZE && my >= cellY(i) && my < cellY(i) + SIZE) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		int i = cellAt(click.x(), click.y());
		if (i == 0) {
			setter.accept(0);
		} else if (i > 0 && i <= WearTint.PRESETS.length) {
			setter.accept(WearTint.ofRgb(WearTint.PRESETS[i - 1]));
		} else if (i == cells() - 1) {
			var client = MinecraftClient.getInstance();
			int current = WearTint.rgb(getter.getAsInt());
			client.setScreen(new net.veloclient.velo.client.gui.VeloColorPickerScreen(net.veloclient.velo.client.util.ClientCompat.currentScreen(), "Colour",
					0xFF000000 | (current < 0 ? 0xFFFFFF : current), false, argb -> setter.accept(WearTint.ofRgb(argb))));
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int code = getter.getAsInt();
		int current = WearTint.rgb(code);
		int hovered = isHovered() ? cellAt(mouseX, mouseY) : -1;
		String name = hovered == 0 ? "Its own colours" : hovered > 0 && hovered <= WearTint.PRESETS.length ? WearTint.PRESET_NAMES[hovered - 1]
				: hovered == cells() - 1 ? "Pick any colour..." : current < 0 ? "Its own colours" : String.format(java.util.Locale.ROOT, "#%06X", current);
		context.drawTextWithShadow(textRenderer, "Colour", getX(), getY(), VeloStyle.textMuted());
		context.drawTextWithShadow(textRenderer, name, getX() + getWidth() - textRenderer.getWidth(name), getY(), VeloStyle.text());
		boolean custom = current >= 0;
		for (int i = 0; i < cells(); i++) {
			int x = cellX(i);
			int y = cellY(i);
			boolean selected;
			if (i == 0) {
				selected = current < 0;
				VeloDraw.fillRounded(context, x, y, SIZE, SIZE, 4, 0xFF2A2A30);
				// a split swatch: "as it comes"
				VeloDraw.fillRounded(context, x + 3, y + 3, SIZE - 6, SIZE - 6, 2, 0xFF8C8C94);
				context.fill(x + 3, y + SIZE / 2, x + SIZE - 3, y + SIZE - 3, 0xFFD9D9DE);
			} else if (i <= WearTint.PRESETS.length) {
				int rgb = WearTint.PRESETS[i - 1];
				selected = custom && current == rgb;
				custom &= !selected;
				VeloDraw.fillRounded(context, x, y, SIZE, SIZE, 4, 0xFF000000 | rgb);
			} else {
				selected = custom;
				VeloDraw.fillRounded(context, x, y, SIZE, SIZE, 4, custom ? 0xFF000000 | current : 0xFF2A2A30);
				int plus = custom ? (WearTint.rgb(code) & 0xFF) > 0xA0 ? 0xFF101014 : 0xFFFFFFFF : 0xFFFFFFFF;
				context.fill(x + 4, y + SIZE / 2 - 1, x + SIZE - 4, y + SIZE / 2 + 1, plus);
				context.fill(x + SIZE / 2 - 1, y + 4, x + SIZE / 2 + 1, y + SIZE - 4, plus);
			}
			if (selected || i == hovered) {
				VeloDraw.strokeRounded(context, x - 2, y - 2, SIZE + 4, SIZE + 4, 5, selected ? 0xFFFFFFFF : 0x80FFFFFF);
			}
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
