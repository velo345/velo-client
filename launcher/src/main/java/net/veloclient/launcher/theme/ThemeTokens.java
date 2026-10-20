package net.veloclient.launcher.theme;

import java.util.Locale;

/**
 * Turns a {@link LauncherTheme} into the CSS lookup colors the stylesheets use ({@code -velo-*}). The theme only
 * stores a handful of colors; the in-between surface steps, the quieter text tones and the soft accent tints are
 * derived here so every theme (built-in or custom) gets the full set.
 */
public final class ThemeTokens {

	private ThemeTokens() {
	}

	public static String style(LauncherTheme theme) {
		int bg = opaque(theme.background());
		int surface = opaque(theme.surface());
		int accent = opaque(theme.accentStart());
		int text = opaque(theme.text());
		return String.join(" ",
				token("-velo-background", rgba(bg, 1)),
				token("-velo-surface", rgba(surface, 1)),
				token("-velo-surface-2", rgba(lift(surface, 8), 1)),
				token("-velo-surface-3", rgba(lift(surface, 16), 1)),
				token("-velo-glass", rgba(bg, 0.86)),
				token("-velo-accent-start", rgba(accent, 1)),
				token("-velo-accent-end", rgba(opaque(theme.accentEnd()), 1)),
				token("-velo-accent", rgba(accent, 1)),
				token("-velo-accent-hover", rgba(mix(accent, 0xFFFFFF, 0.12), 1)),
				token("-velo-accent-pressed", rgba(mix(accent, 0x000000, 0.14), 1)),
				token("-velo-accent-soft", rgba(accent, 0.12)),
				token("-velo-accent-line", rgba(accent, 0.38)),
				token("-velo-accent-glow", rgba(accent, 0.35)),
				token("-velo-accent-text", rgba(mix(accent, 0xFFFFFF, LauncherTheme.luminance(accent) > 0.45 ? 0.0 : 0.25), 1)),
				token("-velo-on-accent", rgba(opaque(theme.onAccent()), 1)),
				token("-velo-text", rgba(text, 1)),
				token("-velo-text-2", rgba(text, 0.68)),
				token("-velo-text-3", rgba(text, 0.5)),
				token("-velo-hover", rgba(lift(surface, 12), 1)),
				token("-velo-hairline", "rgba(255,255,255,0.07)"),
				token("-velo-hairline-2", "rgba(255,255,255,0.12)"),
				"-fx-background-color: -velo-background;");
	}

	private static String token(String name, String value) {
		return name + ": " + value + ";";
	}

	private static int opaque(int argb) {
		return argb & 0xFFFFFF;
	}

	private static int lift(int rgb, int amount) {
		int r = Math.min(255, ((rgb >> 16) & 0xFF) + amount);
		int g = Math.min(255, ((rgb >> 8) & 0xFF) + amount);
		int b = Math.min(255, (rgb & 0xFF) + amount + 2);
		return (r << 16) | (g << 8) | b;
	}

	private static int mix(int rgb, int other, double t) {
		int r = (int) Math.round(((rgb >> 16) & 0xFF) * (1 - t) + ((other >> 16) & 0xFF) * t);
		int g = (int) Math.round(((rgb >> 8) & 0xFF) * (1 - t) + ((other >> 8) & 0xFF) * t);
		int b = (int) Math.round((rgb & 0xFF) * (1 - t) + (other & 0xFF) * t);
		return (r << 16) | (g << 8) | b;
	}

	private static String rgba(int rgb, double alpha) {
		return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
	}
}
