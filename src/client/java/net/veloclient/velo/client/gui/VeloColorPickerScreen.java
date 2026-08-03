package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.awt.Color;
import java.util.function.IntConsumer;

/**
 * A real HSV color picker (saturation/value square + hue strip + alpha
 * slider + hex input), not raw ARGB number sliders - opened from a "Color:
 * [swatch]" button wherever a module or the theme editor exposes a
 * recolorable value. Every change calls {@code onChange} immediately (live
 * preview), so there's no separate "Apply" step; "Done" just closes it.
 */
public final class VeloColorPickerScreen extends VeloWindow {

	private static final int SQUARE_SIZE = 140;
	private static final int HUE_STRIP_WIDTH = 16;
	private static final int STRIP_GAP = 10;

	private final IntConsumer onChange;
	private final boolean includeAlpha;
	private float hue;
	private float saturation;
	private float brightness;
	private int alpha;
	private TextFieldWidget hexBox;
	private boolean draggingSquare;
	private boolean draggingHue;

	public VeloColorPickerScreen(Screen parent, String label, int initialArgb, boolean includeAlpha, IntConsumer onChange) {
		super(Text.literal(label), 220, includeAlpha ? 292 : 268);
		returnTo(parent);
		this.onChange = onChange;
		this.includeAlpha = includeAlpha;
		this.alpha = (initialArgb >>> 24) & 0xFF;
		float[] hsb = Color.RGBtoHSB((initialArgb >> 16) & 0xFF, (initialArgb >> 8) & 0xFF, initialArgb & 0xFF, null);
		hue = hsb[0];
		saturation = hsb[1];
		brightness = hsb[2];
	}

	private int squareX() {
		return contentX();
	}

	private int squareY() {
		return contentY();
	}

	private int hueStripX() {
		return squareX() + SQUARE_SIZE + STRIP_GAP;
	}

	private int currentArgb() {
		int rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0x00FFFFFF;
		return (includeAlpha ? alpha : 0xFF) << 24 | rgb;
	}

	private void notifyChange() {
		onChange.accept(currentArgb());
		if (hexBox != null) {
			hexBox.setText(String.format("#%06X", currentArgb() & 0xFFFFFF));
		}
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int previewY = squareY() + SQUARE_SIZE + 12;

		hexBox = new TextFieldWidget(this.textRenderer, contentX() + 40, previewY, contentWidth() - 40, 18, Text.literal("Hex"));
		hexBox.setDrawsBackground(true);
		hexBox.setMaxLength(7);
		hexBox.setText(String.format("#%06X", currentArgb() & 0xFFFFFF));
		hexBox.setChangedListener(this::applyHex);
		addDrawableChild(hexBox);

		int y = previewY + 26;
		if (includeAlpha) {
			addDrawableChild(new VeloSlider(contentX(), y, contentWidth(), 16, "Alpha", 0, 255,
					() -> alpha, v -> {
						alpha = (int) v;
						notifyChange();
					}, null, v -> Math.round(v / 255f * 100) + "%"));
			y += 26;
		}

		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void applyHex(String text) {
		String hex = text.startsWith("#") ? text.substring(1) : text;
		if (hex.length() != 6) {
			return;
		}
		try {
			int rgb = Integer.parseInt(hex, 16);
			float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
			hue = hsb[0];
			saturation = hsb[1];
			brightness = hsb[2];
			onChange.accept(currentArgb());
		} catch (NumberFormatException ignored) {
			// Still typing a valid hex value - not an error worth surfacing.
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Theme theme = ThemeManager.active();

		// SV square: a horizontal saturation gradient (white -> pure hue),
		// with a vertical brightness gradient (transparent -> black) layered
		// on top - two cheap passes instead of one fill per pixel, and
		// visually identical to a "real" SV square.
		int sx = squareX();
		int sy = squareY();
		int pureHue = Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF;
		for (int col = 0; col < SQUARE_SIZE; col++) {
			float t = col / (float) (SQUARE_SIZE - 1);
			int r = Math.round(255 * (1 - t) + ((pureHue >> 16) & 0xFF) * t);
			int g = Math.round(255 * (1 - t) + ((pureHue >> 8) & 0xFF) * t);
			int b = Math.round(255 * (1 - t) + (pureHue & 0xFF) * t);
			int color = 0xFF000000 | (r << 16) | (g << 8) | b;
			context.fill(sx + col, sy, sx + col + 1, sy + SQUARE_SIZE, color);
		}
		context.fillGradient(sx, sy, sx + SQUARE_SIZE, sy + SQUARE_SIZE, 0x00000000, 0xFF000000);
		VeloDraw.strokeRect(context, sx - 1, sy - 1, SQUARE_SIZE + 2, SQUARE_SIZE + 2, 0xFF000000);

		int cursorX = sx + Math.round(saturation * SQUARE_SIZE);
		int cursorY = sy + Math.round((1 - brightness) * SQUARE_SIZE);
		VeloDraw.strokeRect(context, cursorX - 3, cursorY - 3, 6, 6, 0xFFFFFFFF);
		VeloDraw.strokeRect(context, cursorX - 4, cursorY - 4, 8, 8, 0xFF000000);

		// Hue strip: one row per hue degree bucket across its height.
		int hx = hueStripX();
		for (int row = 0; row < SQUARE_SIZE; row++) {
			float h = row / (float) SQUARE_SIZE;
			int color = 0xFF000000 | (Color.HSBtoRGB(h, 1f, 1f) & 0xFFFFFF);
			context.fill(hx, sy + row, hx + HUE_STRIP_WIDTH, sy + row + 1, color);
		}
		VeloDraw.strokeRect(context, hx - 1, sy - 1, HUE_STRIP_WIDTH + 2, SQUARE_SIZE + 2, 0xFF000000);
		int hueMarkerY = sy + Math.round(hue * SQUARE_SIZE);
		VeloDraw.strokeRect(context, hx - 2, hueMarkerY - 1, HUE_STRIP_WIDTH + 4, 3, 0xFFFFFFFF);

		int previewY = squareY() + SQUARE_SIZE + 12;
		VeloDraw.strokeRect(context, contentX() - 1, previewY - 1, 34, 20, 0xFF000000);
		context.fill(contentX(), previewY, contentX() + 32, previewY + 18, currentArgb());
		context.drawTextWithShadow(this.textRenderer, "Hex", contentX() + 40, previewY - 10, theme.text());
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int mouseX = (int) click.x();
		int mouseY = (int) click.y();
		if (isInside(mouseX, mouseY, squareX(), squareY(), SQUARE_SIZE, SQUARE_SIZE)) {
			draggingSquare = true;
			updateFromSquare(mouseX, mouseY);
			return true;
		}
		if (isInside(mouseX, mouseY, hueStripX(), squareY(), HUE_STRIP_WIDTH, SQUARE_SIZE)) {
			draggingHue = true;
			updateFromHueStrip(mouseY);
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (draggingSquare) {
			updateFromSquare((int) click.x(), (int) click.y());
			return true;
		}
		if (draggingHue) {
			updateFromHueStrip((int) click.y());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		draggingSquare = false;
		draggingHue = false;
		return super.mouseReleased(click);
	}

	private void updateFromSquare(int mouseX, int mouseY) {
		saturation = Math.clamp((mouseX - squareX()) / (float) SQUARE_SIZE, 0f, 1f);
		brightness = 1f - Math.clamp((mouseY - squareY()) / (float) SQUARE_SIZE, 0f, 1f);
		notifyChange();
	}

	private void updateFromHueStrip(int mouseY) {
		hue = Math.clamp((mouseY - squareY()) / (float) SQUARE_SIZE, 0f, 1f);
		notifyChange();
	}

	private static boolean isInside(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}
}
