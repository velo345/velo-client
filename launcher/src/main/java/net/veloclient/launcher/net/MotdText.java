package net.veloclient.launcher.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses a server list ping's MOTD - a Minecraft chat component, not a plain
 * string - into styled {@link Segment}s (color + bold/italic/underline/
 * strikethrough) instead of flattening it to plain text, so the launcher can
 * render it looking like it actually does in the vanilla multiplayer screen
 * rather than as a wall of unstyled text.
 *
 * <p>Handles both the modern per-component {@code color}/{@code bold}/...
 * fields (with normal parent-to-child style inheritance) and legacy
 * {@code §}-formatting codes embedded directly in a component's own text -
 * plenty of servers (especially ones just echoing a raw {@code motd.txt}/
 * {@code server.properties} string) still use the latter, sometimes both at
 * once.
 */
public final class MotdText {

	public record Segment(String text, int argbColor, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {
	}

	private record Style(int color, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {
		static final Style DEFAULT = new Style(0xFFFFFFFF, false, false, false, false);
	}

	private static final Map<String, Integer> NAMED_COLORS = Map.ofEntries(
			Map.entry("black", 0xFF000000), Map.entry("dark_blue", 0xFF0000AA), Map.entry("dark_green", 0xFF00AA00),
			Map.entry("dark_aqua", 0xFF00AAAA), Map.entry("dark_red", 0xFFAA0000), Map.entry("dark_purple", 0xFFAA00AA),
			Map.entry("gold", 0xFFFFAA00), Map.entry("gray", 0xFFAAAAAA), Map.entry("dark_gray", 0xFF555555),
			Map.entry("blue", 0xFF5555FF), Map.entry("green", 0xFF55FF55), Map.entry("aqua", 0xFF55FFFF),
			Map.entry("red", 0xFFFF5555), Map.entry("light_purple", 0xFFFF55FF), Map.entry("yellow", 0xFFFFFF55),
			Map.entry("white", 0xFFFFFFFF));

	private static final String LEGACY_CODE_CHARS = "0123456789abcdef";

	private MotdText() {
	}

	public static List<Segment> parse(JsonElement description) {
		List<Segment> out = new ArrayList<>();
		if (description != null && !description.isJsonNull()) {
			walk(description, Style.DEFAULT, out);
		}
		return out;
	}

	public static String plainText(List<Segment> segments) {
		StringBuilder sb = new StringBuilder();
		for (Segment s : segments) {
			sb.append(s.text());
		}
		return sb.toString().strip();
	}

	private static void walk(JsonElement element, Style inherited, List<Segment> out) {
		if (element == null || element.isJsonNull()) {
			return;
		}
		if (element.isJsonPrimitive()) {
			appendLegacyText(element.getAsString(), inherited, out);
			return;
		}
		if (element.isJsonArray()) {
			// Siblings in an "extra" array each inherit from the parent
			// component that owns the array, not from one another.
			for (JsonElement child : element.getAsJsonArray()) {
				walk(child, inherited, out);
			}
			return;
		}
		JsonObject obj = element.getAsJsonObject();
		Style style = resolveStyle(obj, inherited);
		if (obj.has("text")) {
			appendLegacyText(obj.get("text").getAsString(), style, out);
		}
		if (obj.has("extra")) {
			walk(obj.get("extra"), style, out);
		}
	}

	private static Style resolveStyle(JsonObject obj, Style inherited) {
		int color = obj.has("color") ? parseColor(obj.get("color").getAsString(), inherited.color()) : inherited.color();
		boolean bold = obj.has("bold") ? obj.get("bold").getAsBoolean() : inherited.bold();
		boolean italic = obj.has("italic") ? obj.get("italic").getAsBoolean() : inherited.italic();
		boolean underlined = obj.has("underlined") ? obj.get("underlined").getAsBoolean() : inherited.underlined();
		boolean strikethrough = obj.has("strikethrough") ? obj.get("strikethrough").getAsBoolean() : inherited.strikethrough();
		return new Style(color, bold, italic, underlined, strikethrough);
	}

	private static int parseColor(String value, int fallback) {
		if (value.startsWith("#") && value.length() == 7) {
			try {
				return 0xFF000000 | Integer.parseInt(value.substring(1), 16);
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		return NAMED_COLORS.getOrDefault(value, fallback);
	}

	/** Scans {@code text} for embedded {@code §}-codes, splitting into segments whenever the running style actually changes. */
	private static void appendLegacyText(String text, Style baseStyle, List<Segment> out) {
		Style current = baseStyle;
		StringBuilder buffer = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§' && i + 1 < text.length()) {
				char code = Character.toLowerCase(text.charAt(i + 1));
				Style next = applyLegacyCode(current, baseStyle, code);
				if (next != null) {
					flush(buffer, current, out);
					current = next;
					i++;
					continue;
				}
			}
			buffer.append(c);
		}
		flush(buffer, current, out);
	}

	private static Style applyLegacyCode(Style current, Style base, char code) {
		int colorIndex = LEGACY_CODE_CHARS.indexOf(code);
		if (colorIndex >= 0) {
			// A color code resets bold/italic/underline/strikethrough too, matching vanilla.
			return new Style(legacyColorAt(colorIndex), false, false, false, false);
		}
		return switch (code) {
			case 'l' -> new Style(current.color(), true, current.italic(), current.underlined(), current.strikethrough());
			case 'o' -> new Style(current.color(), current.bold(), true, current.underlined(), current.strikethrough());
			case 'n' -> new Style(current.color(), current.bold(), current.italic(), true, current.strikethrough());
			case 'm' -> new Style(current.color(), current.bold(), current.italic(), current.underlined(), true);
			case 'r' -> base;
			default -> null; // 'k' (obfuscated) and anything unrecognized - leave text/style as-is.
		};
	}

	private static int legacyColorAt(int index) {
		String[] order = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
				"dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
		return NAMED_COLORS.get(order[index]);
	}

	private static void flush(StringBuilder buffer, Style style, List<Segment> out) {
		if (buffer.isEmpty()) {
			return;
		}
		out.add(new Segment(buffer.toString(), style.color(), style.bold(), style.italic(), style.underlined(), style.strikethrough()));
		buffer.setLength(0);
	}
}
