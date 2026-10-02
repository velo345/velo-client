package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * Design tokens shared by every Velo widget, all derived from the active {@link Theme} so custom
 * themes keep working: layered surfaces (window, card, raised), hairline borders, accent tints,
 * and the press/hover motion helpers. Keeping them in one place is what makes the menus read as
 * one consistent design instead of each widget picking its own grays.
 */
public final class VeloStyle {

	public static final int RADIUS_WINDOW = 10;
	public static final int RADIUS_CARD = 8;
	public static final int RADIUS_CONTROL = 6;

	private VeloStyle() {
	}

	public static Theme theme() {
		return ThemeManager.active();
	}

	/** Opaque version of the theme surface - the base everything else is mixed from. */
	public static int base() {
		return theme().surface() | 0xFF000000;
	}

	/** Window body: the themed surface at its configured opacity. */
	public static int window() {
		return theme().surfaceWithOpacity();
	}

	/** A card sitting on the window (tiles, rows, inputs). */
	public static int card() {
		return over(VeloAnim.lerpArgb(base(), 0xFFFFFFFF, 0.045f));
	}

	public static int cardHover() {
		return over(VeloAnim.lerpArgb(base(), 0xFFFFFFFF, 0.085f));
	}

	/** Recessed areas: input fields, toggle tracks, slider tracks. */
	public static int sunken() {
		return over(VeloAnim.lerpArgb(base(), 0xFF000000, 0.35f));
	}

	/** Hairline border on cards/inputs. */
	public static int border() {
		return VeloUi.withAlpha(theme().text(), 0x1C);
	}

	public static int borderStrong() {
		return VeloUi.withAlpha(theme().text(), 0x30);
	}

	public static int text() {
		return theme().text() | 0xFF000000;
	}

	public static int textMuted() {
		return VeloUi.withAlpha(theme().text(), 0x9C);
	}

	public static int textFaint() {
		return VeloUi.withAlpha(theme().text(), 0x66);
	}

	public static int accent() {
		return theme().accentStart() | 0xFF000000;
	}

	public static int accentEnd() {
		return theme().accentEnd() | 0xFF000000;
	}

	public static int accentHover() {
		return VeloAnim.lerpArgb(accent(), 0xFFFFFFFF, 0.14f);
	}

	/** Accent mixed into the card color - selected rows, enabled tiles. */
	public static int accentSoft(float amount) {
		return VeloAnim.lerpArgb(card(), accent(), amount);
	}

	public static int accentGlow(int alpha) {
		return VeloUi.withAlpha(accent(), alpha);
	}

	/** Keeps the window's own translucency for elements laid on top of it. */
	private static int over(int opaque) {
		int alpha = Math.max(0xD8, (theme().surfaceWithOpacity() >>> 24) & 0xFF);
		return VeloUi.withAlpha(opaque, alpha);
	}

	/** The shared text-input look: recessed rounded box, brighter border on hover, accent ring when focused. */
	public static void drawInputField(DrawContext context, int x, int y, int width, int height, boolean focused, boolean hovered) {
		int radius = Math.min(RADIUS_CONTROL, height / 2);
		VeloDraw.fillRounded(context, x, y, width, height, radius, sunken());
		int border = focused ? accent() : hovered ? borderStrong() : border();
		VeloDraw.strokeRounded(context, x, y, width, height, radius, border);
		if (focused) {
			VeloDraw.strokeRounded(context, x - 1f, y - 1f, width + 2f, height + 2f, radius + 1f, 1f, accentGlow(0x40));
		}
	}

	/**
	 * Scale for a short "press" pulse: 1 at rest, dips to ~0.95 right after a click and springs back
	 * over ~180ms. {@code pressedAtNanos} is the click time, or 0 when never pressed.
	 */
	public static float pressScale(long pressedAtNanos) {
		if (pressedAtNanos == 0) {
			return 1f;
		}
		float t = (System.nanoTime() - pressedAtNanos) / 1_000_000_000f / 0.18f;
		if (t >= 1f) {
			return 1f;
		}
		return 1f - 0.05f * (float) Math.sin(Math.PI * t) * (1f - t * 0.35f);
	}

	/** Scales everything drawn until {@link #popScale} around the given center. */
	public static void pushScale(DrawContext context, float centerX, float centerY, float scale) {
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(centerX, centerY);
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate(-centerX, -centerY);
	}

	public static void popScale(DrawContext context) {
		context.getMatrices().popMatrix();
	}

	/** Seconds since the previous frame for a widget, given its last timestamp (0 on the first frame). */
	public static float frameDelta(long lastNanos, long now) {
		return lastNanos <= 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
	}
}
