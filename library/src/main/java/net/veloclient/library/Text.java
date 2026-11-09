package net.veloclient.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minecraft text: chat components / legacy § codes into styled runs, and those runs out as MiniMessage, legacy text,
 * HTML, or drawn with Minecraft's own bitmap font.
 */
final class Text {

	record Run(String text, int rgb, boolean bold, boolean italic, boolean underline, boolean strike, boolean obfuscated) {
	}

	static final String[] NAMES = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue",
			"green", "aqua", "red", "light_purple", "yellow", "white"};
	static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF,
			0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private Text() {
	}

	/** A status-ping description (string or component) into runs; default colour grey like the server list. */
	static List<Run> fromComponent(JsonElement e) {
		List<Run> out = new ArrayList<>();
		walk(e, new Run("", 0xAAAAAA, false, false, false, false, false), out);
		List<Run> legacyExpanded = new ArrayList<>();
		for (Run r : out) {
			if (r.text().indexOf('§') >= 0) {
				legacyExpanded.addAll(fromLegacy(r.text(), r));
			} else {
				legacyExpanded.add(r);
			}
		}
		return legacyExpanded;
	}

	private static void walk(JsonElement e, Run style, List<Run> out) {
		if (e == null || e.isJsonNull()) {
			return;
		}
		if (e.isJsonPrimitive()) {
			out.add(new Run(e.getAsString(), style.rgb(), style.bold(), style.italic(), style.underline(), style.strike(), style.obfuscated()));
			return;
		}
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			// the first element's style is inherited by the rest (vanilla behaviour)
			Run base = style;
			for (int i = 0; i < a.size(); i++) {
				if (i == 0 && a.get(0).isJsonObject()) {
					base = styleOf(a.get(0).getAsJsonObject(), style);
				}
				walk(a.get(i), i == 0 ? style : base, out);
			}
			return;
		}
		JsonObject o = e.getAsJsonObject();
		Run s = styleOf(o, style);
		if (o.has("text")) {
			out.add(new Run(o.get("text").getAsString(), s.rgb(), s.bold(), s.italic(), s.underline(), s.strike(), s.obfuscated()));
		} else if (o.has("translate")) {
			out.add(new Run(o.get("translate").getAsString(), s.rgb(), s.bold(), s.italic(), s.underline(), s.strike(), s.obfuscated()));
		}
		if (o.has("extra")) {
			for (JsonElement x : o.getAsJsonArray("extra")) {
				walk(x, s, out);
			}
		}
	}

	private static Run styleOf(JsonObject o, Run parent) {
		int rgb = parent.rgb();
		if (o.has("color")) {
			rgb = color(o.get("color").getAsString(), rgb);
		}
		return new Run("", rgb, flag(o, "bold", parent.bold()), flag(o, "italic", parent.italic()), flag(o, "underlined", parent.underline()),
				flag(o, "strikethrough", parent.strike()), flag(o, "obfuscated", parent.obfuscated()));
	}

	private static boolean flag(JsonObject o, String k, boolean fallback) {
		return o.has(k) ? o.get(k).getAsBoolean() : fallback;
	}

	static int color(String c, int fallback) {
		if (c.startsWith("#") && c.length() == 7) {
			try {
				return Integer.parseInt(c.substring(1), 16);
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		for (int i = 0; i < NAMES.length; i++) {
			if (NAMES[i].equals(c)) {
				return COLORS[i];
			}
		}
		return fallback;
	}

	/** § codes (including §x§R§R§G§G§B§B hex) into runs. */
	static List<Run> fromLegacy(String s, Run start) {
		List<Run> out = new ArrayList<>();
		int rgb = start.rgb();
		boolean b = start.bold(), it = start.italic(), u = start.underline(), st = start.strike(), ob = start.obfuscated();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '§' && i + 1 < s.length()) {
				char code = Character.toLowerCase(s.charAt(i + 1));
				int idx = "0123456789abcdef".indexOf(code);
				if (cur.length() > 0) {
					out.add(new Run(cur.toString(), rgb, b, it, u, st, ob));
					cur.setLength(0);
				}
				i++;
				if (code == 'x' && i + 12 < s.length() + 1) {
					StringBuilder hex = new StringBuilder();
					for (int k = 0; k < 6 && i + 2 < s.length(); k++) {
						i += 2;
						hex.append(s.charAt(i));
					}
					try {
						rgb = Integer.parseInt(hex.toString(), 16);
					} catch (NumberFormatException ignored) {
						// keep colour
					}
					b = it = u = st = ob = false;
				} else if (idx >= 0) {
					rgb = COLORS[idx];
					b = it = u = st = ob = false;
				} else {
					switch (code) {
						case 'l' -> b = true;
						case 'o' -> it = true;
						case 'n' -> u = true;
						case 'm' -> st = true;
						case 'k' -> ob = true;
						case 'r' -> {
							rgb = start.rgb();
							b = it = u = st = ob = false;
						}
						default -> {
						}
					}
				}
				continue;
			}
			cur.append(ch);
		}
		if (cur.length() > 0) {
			out.add(new Run(cur.toString(), rgb, b, it, u, st, ob));
		}
		return out;
	}

	static String toMiniMessage(List<Run> runs) {
		StringBuilder sb = new StringBuilder();
		Run prev = null;
		for (Run r : runs) {
			if (r.text().isEmpty()) {
				continue;
			}
			if (prev == null || prev.rgb() != r.rgb() || prev.bold() != r.bold() || prev.italic() != r.italic() || prev.underline() != r.underline()
					|| prev.strike() != r.strike() || prev.obfuscated() != r.obfuscated()) {
				if (prev != null) {
					sb.append("<reset>");
				}
				sb.append('<').append(colorName(r.rgb())).append('>');
				if (r.bold()) {
					sb.append("<bold>");
				}
				if (r.italic()) {
					sb.append("<italic>");
				}
				if (r.underline()) {
					sb.append("<underlined>");
				}
				if (r.strike()) {
					sb.append("<strikethrough>");
				}
				if (r.obfuscated()) {
					sb.append("<obfuscated>");
				}
			}
			sb.append(r.text().replace("<", "\\<").replace("\n", "<newline>"));
			prev = r;
		}
		return sb.toString();
	}

	private static String colorName(int rgb) {
		for (int i = 0; i < COLORS.length; i++) {
			if (COLORS[i] == rgb) {
				return NAMES[i];
			}
		}
		return String.format("#%06x", rgb);
	}

	static String toLegacy(List<Run> runs) {
		StringBuilder sb = new StringBuilder();
		for (Run r : runs) {
			if (r.text().isEmpty()) {
				continue;
			}
			int idx = -1;
			for (int i = 0; i < COLORS.length; i++) {
				if (COLORS[i] == r.rgb()) {
					idx = i;
				}
			}
			if (idx >= 0) {
				sb.append('§').append("0123456789abcdef".charAt(idx));
			} else {
				String hex = String.format("%06x", r.rgb());
				sb.append("§x");
				for (char c : hex.toCharArray()) {
					sb.append('§').append(c);
				}
			}
			if (r.bold()) {
				sb.append("§l");
			}
			if (r.italic()) {
				sb.append("§o");
			}
			if (r.underline()) {
				sb.append("§n");
			}
			if (r.strike()) {
				sb.append("§m");
			}
			if (r.obfuscated()) {
				sb.append("§k");
			}
			sb.append(r.text());
		}
		return sb.toString();
	}

	static String toPlain(List<Run> runs) {
		StringBuilder sb = new StringBuilder();
		for (Run r : runs) {
			sb.append(r.text());
		}
		return sb.toString();
	}

	static String toHtml(List<Run> runs) {
		StringBuilder sb = new StringBuilder();
		for (Run r : runs) {
			if (r.text().isEmpty()) {
				continue;
			}
			sb.append("<span style=\"color:#").append(String.format("%06x", r.rgb())).append(r.bold() ? ";font-weight:700" : "")
					.append(r.italic() ? ";font-style:italic" : "").append(r.underline() || r.strike() ? ";text-decoration:" + (r.underline() ? "underline " : "")
							+ (r.strike() ? "line-through" : "") : "").append("\">")
					.append(r.text().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")).append("</span>");
		}
		return sb.toString();
	}

	// ---- Minecraft's bitmap font ----

	/** ascii.png glyphs (16x16 grid, 8 px cells) with their real widths; other characters fall back to Inter. */
	static final class McFont {
		private final BufferedImage atlas;
		private final int cell;
		private final int[] widths = new int[256];
		private final Font fallback;

		McFont(BufferedImage atlas, Font fallback) {
			this.atlas = atlas;
			this.fallback = fallback;
			this.cell = atlas == null ? 8 : atlas.getWidth() / 16;
			for (int c = 0; c < 256; c++) {
				widths[c] = c == ' ' ? 4 * cell / 8 : measure(c);
			}
		}

		private int measure(int c) {
			if (atlas == null) {
				return cell;
			}
			int gx = (c % 16) * cell, gy = (c / 16) * cell;
			for (int x = cell - 1; x >= 0; x--) {
				for (int y = 0; y < cell; y++) {
					if ((atlas.getRGB(gx + x, gy + y) >>> 24) > 0) {
						return x + 1 + cell / 8;
					}
				}
			}
			return 0;
		}

		/** Width in font pixels (8 px glyph cells) of the runs. */
		int width(List<Run> runs) {
			int w = 0;
			for (Run r : runs) {
				for (int i = 0; i < r.text().length(); i++) {
					char ch = r.text().charAt(i);
					w += (ch >= 32 && ch < 127 && atlas != null ? widths[ch] * 8 / cell : 6) + (r.bold() ? 1 : 0);
				}
			}
			return w;
		}

		/** Draws at (x, y) with a pixel scale; shadow like the game. Returns the width drawn in screen pixels. */
		int draw(Graphics2D g, List<Run> runs, int x, int y, int scale, boolean shadow) {
			int start = x;
			for (Run r : runs) {
				for (int i = 0; i < r.text().length(); i++) {
					char ch = r.text().charAt(i);
					if (r.obfuscated() && ch != ' ') {
						ch = (char) ('a' + (int) (Math.random() * 26));
					}
					int adv;
					if (ch >= 32 && ch < 127 && atlas != null) {
						adv = widths[ch] * 8 / cell;
						if (shadow) {
							glyph(g, ch, x + scale, y + scale, scale, shadowOf(r.rgb()), r);
						}
						glyph(g, ch, x, y, scale, r.rgb(), r);
					} else {
						g.setFont(fallback.deriveFont((float) (8 * scale)));
						String s = String.valueOf(ch);
						adv = Math.max(4, (int) Math.ceil(g.getFontMetrics().stringWidth(s) / (double) scale));
						if (shadow) {
							g.setColor(new Color(shadowOf(r.rgb())));
							g.drawString(s, x + scale, y + 7 * scale + scale);
						}
						g.setColor(new Color(r.rgb()));
						g.drawString(s, x, y + 7 * scale);
					}
					int w = (adv + (r.bold() ? 1 : 0)) * scale;
					if (r.underline() || r.strike()) {
						g.setColor(new Color(r.rgb()));
						if (r.underline()) {
							g.fillRect(x - scale, y + 8 * scale, w + scale, scale);
						}
						if (r.strike()) {
							g.fillRect(x - scale, y + 3 * scale + scale / 2, w + scale, scale);
						}
					}
					x += w;
				}
			}
			return x - start;
		}

		private static int shadowOf(int rgb) {
			return ((rgb >> 2) & 0x3f3f3f);
		}

		private void glyph(Graphics2D g, char ch, int x, int y, int scale, int rgb, Run r) {
			int gx = (ch % 16) * cell, gy = (ch / 16) * cell;
			int px = Math.max(1, scale * 8 / cell);
			g.setColor(new Color(rgb));
			for (int yy = 0; yy < cell; yy++) {
				for (int xx = 0; xx < cell; xx++) {
					if ((atlas.getRGB(gx + xx, gy + yy) >>> 24) > 100) {
						int dx = x + xx * px + (r.italic() ? (cell - yy) * px / 4 : 0);
						int dy = y + yy * px;
						g.fillRect(dx, dy, px, px);
						if (r.bold()) {
							g.fillRect(dx + px, dy, px, px);
						}
					}
				}
			}
		}
	}

	/** MiniMessage subset (colours, hex, gradient, decorations, reset, newline) into runs - for banners and the API. */
	static List<Run> fromMiniMessage(String src) {
		List<Run> out = new ArrayList<>();
		int rgb = 0xAAAAAA;
		boolean b = false, it = false, u = false, st = false, ob = false;
		int[] grad = null;
		List<int[]> gradChars = null;
		StringBuilder gradText = null;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(/?)([a-zA-Z_#:0-9]+)>").matcher(src);
		int last = 0;
		while (true) {
			boolean found = m.find();
			String chunk = src.substring(last, found ? m.start() : src.length()).replace("\\<", "<");
			if (!chunk.isEmpty()) {
				if (grad != null) {
					gradText.append(chunk);
					for (int i = 0; i < chunk.length(); i++) {
						gradChars.add(new int[] {b ? 1 : 0, it ? 1 : 0, u ? 1 : 0, st ? 1 : 0});
					}
				} else {
					out.add(new Run(chunk, rgb, b, it, u, st, ob));
				}
			}
			if (!found) {
				break;
			}
			last = m.end();
			boolean close = !m.group(1).isEmpty();
			String tag = m.group(2).toLowerCase(java.util.Locale.ROOT);
			if (tag.startsWith("gradient") || tag.startsWith("rainbow")) {
				if (close && grad != null) {
					emitGradient(out, gradText.toString(), grad, gradChars);
					grad = null;
				} else if (!close) {
					String[] p = tag.split(":");
					if (tag.startsWith("rainbow")) {
						grad = new int[] {0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF};
					} else {
						List<Integer> cs = new ArrayList<>();
						for (int i = 1; i < p.length; i++) {
							cs.add(color(p[i], 0xFFFFFF));
						}
						if (cs.isEmpty()) {
							cs.add(0xFFFFFF);
						}
						if (cs.size() == 1) {
							cs.add(cs.get(0));
						}
						grad = cs.stream().mapToInt(Integer::intValue).toArray();
					}
					gradChars = new ArrayList<>();
					gradText = new StringBuilder();
				}
				continue;
			}
			switch (tag) {
				case "bold", "b" -> b = !close;
				case "italic", "i", "em" -> it = !close;
				case "underlined", "u" -> u = !close;
				case "strikethrough", "st" -> st = !close;
				case "obfuscated", "obf" -> ob = !close;
				case "reset" -> {
					rgb = 0xAAAAAA;
					b = it = u = st = ob = false;
				}
				case "newline", "br" -> out.add(new Run("\n", rgb, b, it, u, st, ob));
				default -> {
					if (close) {
						rgb = 0xAAAAAA;
					} else {
						String c = tag.startsWith("color:") || tag.startsWith("c:") ? tag.substring(tag.indexOf(':') + 1) : tag;
						rgb = color(c, rgb);
					}
				}
			}
		}
		if (grad != null) {
			emitGradient(out, gradText.toString(), grad, gradChars);
		}
		return out;
	}

	private static void emitGradient(List<Run> out, String text, int[] stops, List<int[]> styles) {
		int n = text.length();
		for (int i = 0; i < n; i++) {
			double t = n > 1 ? i / (double) (n - 1) : 0;
			double seg = t * (stops.length - 1);
			int k = Math.min(stops.length - 2, (int) Math.floor(seg));
			double f = seg - k;
			int a = stops[k], c = stops[k + 1];
			int r = (int) Math.round(((a >> 16) & 255) + (((c >> 16) & 255) - ((a >> 16) & 255)) * f);
			int g = (int) Math.round(((a >> 8) & 255) + (((c >> 8) & 255) - ((a >> 8) & 255)) * f);
			int bl = (int) Math.round((a & 255) + ((c & 255) - (a & 255)) * f);
			int[] s = styles.get(i);
			out.add(new Run(String.valueOf(text.charAt(i)), r << 16 | g << 8 | bl, s[0] == 1, s[1] == 1, s[2] == 1, s[3] == 1, false));
		}
	}

	static Map<String, Object> formats(List<Run> runs) {
		return Http.map("plain", toPlain(runs), "minimessage", toMiniMessage(runs), "legacy", toLegacy(runs), "html", toHtml(runs));
	}
}
