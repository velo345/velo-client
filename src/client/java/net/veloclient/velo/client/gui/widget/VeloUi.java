package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Small immediate-mode helpers shared by the Friends/Waypoints screens and the notification
 * popups: hit-testable rectangles, pill buttons, string trimming/wrapping. Those screens draw most
 * of their rows themselves (rows change every second with presence/messages, so rebuilding widget
 * trees each update would just churn) and only use real widgets for text input.
 */
public final class VeloUi {

	private VeloUi() {
	}

	/** A clickable area remembered from the last frame, with what clicking it does. */
	public record Hit(int x, int y, int width, int height, Runnable action) {
		public boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}

	public static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	public static int withAlpha(int argb, int alpha) {
		return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
	}

	/** Theme text color faded for secondary lines. */
	public static int muted() {
		return withAlpha(ThemeManager.active().text(), 0x99);
	}

	/**
	 * A rounded pill button; returns its {@link Hit} so the caller can register it. {@code tone}:
	 * 0 = neutral, 1 = accent, 2 = positive (green), 3 = danger (red).
	 */
	public static Hit pill(DrawContext context, int x, int y, int width, int height, String label, int tone,
			int mouseX, int mouseY, Runnable action) {
		Theme theme = ThemeManager.active();
		boolean hovered = inside(mouseX, mouseY, x, y, width, height);
		int radius = Math.min(height / 2, 6);
		int base = switch (tone) {
			case 1 -> theme.accentStart() | 0xFF000000;
			case 2 -> 0xFF2E9E5B;
			case 3 -> 0xFFC24242;
			default -> VeloStyle.card();
		};
		int top = hovered ? VeloAnim.lerpArgb(base, 0xFFFFFFFF, tone == 0 ? 0.06f : 0.14f) : base;
		int bottom = tone == 0 ? top : VeloAnim.lerpArgb(top, 0xFF000000, 0.12f);
		VeloDraw.fillRoundedGradient(context, x, y, width, height, radius, top, bottom);
		if (tone == 0) {
			VeloDraw.strokeRounded(context, x, y, width, height, radius,
					hovered ? withAlpha(theme.accentStart(), 0xC0) : VeloStyle.border());
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String shown = trim(label, width - 6);
		context.drawTextWithShadow(textRenderer, shown, x + (width - textRenderer.getWidth(shown)) / 2,
				y + (height - 8) / 2, tone == 0 ? theme.text() : 0xFFFFFFFF);
		return new Hit(x, y, width, height, action);
	}

	/** Opens a web page in the player's browser (checkout, ads) - plain OS command, works on every version. */
	public static void openUrl(String url) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			String[] command = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
					: os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
			new ProcessBuilder(command).start();
		} catch (Exception e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't open {}", url, e);
		}
	}

	public static int textWidth(String text) {
		return MinecraftClient.getInstance().textRenderer.getWidth(text);
	}

	/** Cuts {@code text} to fit {@code maxWidth} pixels, adding "..." when shortened. */
	public static String trim(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		if (textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && textRenderer.getWidth(cut + "...") > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "...";
	}

	/**
	 * Like {@link #trim} but for text drawn through a styled font ({@code styler} applies the
	 * font), so the measured width matches what's actually drawn. Returns the styled result.
	 */
	public static net.minecraft.text.Text trimStyled(net.minecraft.text.Text original, int maxWidth,
			java.util.function.Function<net.minecraft.text.Text, net.minecraft.text.Text> styler) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		net.minecraft.text.Text styled = styler.apply(original);
		if (textRenderer.getWidth(styled) <= maxWidth) {
			return styled;
		}
		String plain = original.getString();
		while (plain.length() > 1) {
			plain = plain.substring(0, plain.length() - 1);
			styled = styler.apply(net.minecraft.text.Text.literal(plain + "..."));
			if (textRenderer.getWidth(styled) <= maxWidth) {
				return styled;
			}
		}
		return styler.apply(net.minecraft.text.Text.literal("..."));
	}

	/** Word-wraps plain text into lines no wider than {@code maxWidth}; overlong words are hard-split. */
	public static List<String> wrap(String text, int maxWidth) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		List<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			for (String word : paragraph.split(" ")) {
				while (textRenderer.getWidth(word) > maxWidth && word.length() > 1) {
					int cut = word.length();
					while (cut > 1 && textRenderer.getWidth(word.substring(0, cut)) > maxWidth) {
						cut--;
					}
					if (!line.isEmpty()) {
						lines.add(line.toString());
						line.setLength(0);
					}
					lines.add(word.substring(0, cut));
					word = word.substring(cut);
				}
				String candidate = line.isEmpty() ? word : line + " " + word;
				if (textRenderer.getWidth(candidate) > maxWidth && !line.isEmpty()) {
					lines.add(line.toString());
					line.setLength(0);
					line.append(word);
				} else {
					line.setLength(0);
					line.append(candidate);
				}
			}
			lines.add(line.toString());
		}
		return lines;
	}

	/** "just now", "5m ago", "3h ago", "2d ago". */
	public static String ago(long epochMillis) {
		if (epochMillis <= 0) {
			return "a while ago";
		}
		long seconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
		if (seconds < 60) {
			return "just now";
		}
		if (seconds < 3600) {
			return seconds / 60 + "m ago";
		}
		if (seconds < 86400) {
			return seconds / 3600 + "h ago";
		}
		return seconds / 86400 + "d ago";
	}

	/** Clock time for chat bubbles ("14:05"). */
	public static String clock(long epochMillis) {
		return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
				.format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()));
	}
}
