package net.veloclient.velo.client.privacy;

import java.util.BitSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti Coords Leak's text side: finds the numbers in a line of text that are (or look like) coordinates and turns them
 * into Minecraft's obfuscated "magic" glyphs - same width, so layouts don't move. Every piece of text the game draws
 * passes through here (AntiCoordsFontMixin on the font itself), so it covers chat, F3, the HUD, signs, name tags and any
 * mod's screens and overlays - Litematica, minimaps, waypoint lists.
 *
 * <p>A number counts as a coordinate when it
 * <ul>
 * <li>follows an axis label - {@code X: 120}, {@code z=-40}, {@code Y 64};</li>
 * <li>is one of three numbers in a row with one of them 100 or more - {@code 120 64 -40}, {@code 1.5 / 64.0 / -3200.2};</li>
 * <li>is one of two big numbers in a row - {@code 1200 -3400}, {@code 1200, -3400} (both 100 or more);</li>
 * <li>sits in a line about places - "coords", "position", "chunk", "region", "waypoint", "spawn"... (chunk and region
 * numbers are block coordinates / 16 and / 512, so small ones count there too);</li>
 * <li>stands in a coordinate editor - Litematica's area editor and other malilib screens, minimaps and waypoint screens,
 * structure blocks - where each axis is its own text field;</li>
 * <li>or is close to where you are (X / Z, or its nether / overworld equivalent).</li>
 * </ul>
 * Never: numbers with a unit after them ("240 fps", "12 ms", "75%", "512 MB"), hardware and version names ("RTX 3060",
 * "Java 21"), health ("20/20"), times and versions ("1.21.11"), and numbers glued to a word (player names).
 */
public final class CoordScrambler {

	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:[.,]\\d+)?(?![\\w])");
	// The axis letter on its own: not part of a word or a name ("Steve_X 1234" isn't a label).
	private static final Pattern AXIS_BEFORE = Pattern.compile("(?i)(?:^|[^a-z0-9_])(?:x|y|z)\\s*[:=]?\\s*[\\[(]?\\s*$");
	/** What may stand between the numbers of one coordinate: spaces, or a comma / slash / semicolon with spaces. */
	private static final Pattern SEPARATOR = Pattern.compile("\\s*[,/;|]\\s*|\\s+");
	private static final String[] PLACE_WORDS = {"coord", "xyz", "position", "pos:", "pos ", "location", "loc:", "chunk", "region",
			"origin", "waypoint", "teleport", "/tp", "block:", "corner", "placement", "pos1", "pos2", "spawn", "home", "death", "died at",
			"portal", "selection"};
	/** A unit after the number: it's a measurement, not a position. */
	private static final java.util.Set<String> UNITS = java.util.Set.of("fps", "ms", "mspt", "tps", "%", "mb", "gb", "kb", "mib", "gib", "kib",
			"b", "mhz", "ghz", "hz", "px", "cps", "bpm", "kbps", "mbps", "s", "sec", "min", "h", "m/s", "b/s", "°", "chunks", "entities",
			"players", "online", "ping", "fov", "x");
	/** A word before the number that makes it a model, version or count ("RTX 3060", "Java 21", "Level 30"). */
	private static final java.util.Set<String> NAME_WORDS = java.util.Set.of("rtx", "gtx", "rx", "gt", "geforce", "radeon", "arc", "quadro",
			"ryzen", "core", "xeon", "athlon", "intel", "amd", "nvidia", "java", "jdk", "jre", "version", "ver", "v", "build", "fabric", "sodium",
			"minecraft", "opengl", "vulkan", "driver", "fps", "ping", "tps", "level", "lvl", "page", "mods", "slot", "day", "days", "year",
			"windows", "linux", "kernel", "mesa", "x86", "ram", "memory", "mem", "cpu", "gpu", "allocated");
	/** Screens that are coordinate editors: every larger number there is a position (field by field). */
	private static final String[] COORD_SCREENS = {"fi.dy.masa.", "xaero.", "journeymap.", "com.mamiyaotaru.voxelmap.", "StructureBlock",
			"Waypoint", "waypoint"};

	/** Masks by plain text - the same lines are drawn every frame. Cleared when it grows or the player moves far. */
	private static final Map<String, BitSet> CACHE = new ConcurrentHashMap<>();
	private static final BitSet NONE = new BitSet();
	private static volatile long cachedAt;

	private CoordScrambler() {
	}

	/** Which characters of {@code text} to obfuscate (empty when nothing in it is a coordinate). */
	public static BitSet mask(String text) {
		if (text.isEmpty() || !hasDigit(text)) {
			return NONE;
		}
		long block = playerBlockKey();
		if (CACHE.size() > 4096 || block != cachedAt) {
			CACHE.clear();
			cachedAt = block;
		}
		boolean coordScreen = coordScreen();
		String key = coordScreen ? '\u0001' + text : text;
		BitSet cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		BitSet mask = compute(text, coordScreen);
		CACHE.put(key, mask.isEmpty() ? NONE : mask);
		return mask;
	}

	private static boolean hasDigit(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= '0' && c <= '9') {
				return true;
			}
		}
		return false;
	}

	private static Object lastScreen;
	private static boolean lastScreenIsEditor;

	/** Whether the open screen is a coordinate editor (Litematica, a minimap's waypoints, a structure block...). */
	private static boolean coordScreen() {
		Object screen = net.veloclient.velo.client.util.ClientCompat.currentScreen();
		if (screen != lastScreen) {
			lastScreen = screen;
			lastScreenIsEditor = false;
			if (screen != null) {
				String name = screen.getClass().getName();
				for (String needle : COORD_SCREENS) {
					if (name.contains(needle)) {
						lastScreenIsEditor = true;
						break;
					}
				}
			}
		}
		return lastScreenIsEditor;
	}

	private static BitSet compute(String text, boolean coordScreen) {
		BitSet mask = new BitSet();
		java.util.List<int[]> numbers = new java.util.ArrayList<>();
		Matcher m = NUMBER.matcher(text);
		while (m.find()) {
			numbers.add(new int[] {m.start(), m.end()});
		}
		if (numbers.isEmpty()) {
			return mask;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		boolean placeLine = false;
		for (String word : PLACE_WORDS) {
			if (lower.contains(word)) {
				placeLine = true;
				break;
			}
		}
		double[] player = playerXZ();
		// Runs of numbers with only separators between them.
		int runStart = 0;
		for (int i = 0; i <= numbers.size(); i++) {
			boolean breaks = i == numbers.size() || i > runStart
					&& !SEPARATOR.matcher(text.substring(numbers.get(i - 1)[1], numbers.get(i)[0])).matches();
			if (!breaks) {
				continue;
			}
			int count = i - runStart;
			double biggest = 0;
			for (int j = runStart; j < i; j++) {
				biggest = Math.max(biggest, Math.abs(value(text, numbers.get(j))));
			}
			// Positions around spawn give nothing away; small triples are usually something else ("8 4 2").
			boolean run3 = count >= 3 && biggest >= 100;
			boolean run2 = count == 2 && Math.abs(value(text, numbers.get(runStart))) >= 100 && Math.abs(value(text, numbers.get(runStart + 1))) >= 100
					&& !text.substring(numbers.get(runStart)[1], numbers.get(runStart + 1)[0]).contains("/");
			for (int j = runStart; j < i; j++) {
				int[] n = numbers.get(j);
				double v = Math.abs(value(text, n));
				boolean axis = axisBefore(text, n[0]);
				if (!axis && (unitAfter(text, n[1]) || nameBefore(lower, n[0]))) {
					continue;
				}
				if (axis || run3 || run2 || placeLine && v >= 8 || coordScreen && v >= 50 || nearPlayer(v, player)) {
					mask.set(n[0], n[1]);
				}
			}
			runStart = i;
		}
		return mask;
	}

	private static boolean unitAfter(String text, int end) {
		int i = end;
		while (i < text.length() && text.charAt(i) == ' ') {
			i++;
		}
		int from = i;
		while (i < text.length() && (Character.isLetter(text.charAt(i)) || "%°/".indexOf(text.charAt(i)) >= 0)) {
			i++;
		}
		return i > from && UNITS.contains(text.substring(from, i).toLowerCase(Locale.ROOT));
	}

	private static boolean nameBefore(String lower, int start) {
		int i = start - 1;
		while (i >= 0 && (lower.charAt(i) == ' ' || lower.charAt(i) == ':')) {
			i--;
		}
		int end = i + 1;
		while (i >= 0 && Character.isLetterOrDigit(lower.charAt(i))) {
			i--;
		}
		return end > i + 1 && NAME_WORDS.contains(lower.substring(i + 1, end));
	}

	private static boolean axisBefore(String text, int start) {
		int from = Math.max(0, start - 6);
		Matcher m = AXIS_BEFORE.matcher(text.substring(from, start));
		if (!m.find()) {
			return false;
		}
		// "1920 x 1080": an x right after a number is "times", not an axis.
		int i = from + m.start() - 1;
		while (i >= 0 && text.charAt(i) == ' ') {
			i--;
		}
		int letter = from + m.start() + (Character.isLetter(text.charAt(from + m.start())) ? 0 : 1);
		return !(i >= 0 && Character.isDigit(text.charAt(i)) && Character.toLowerCase(text.charAt(letter)) == 'x');
	}

	private static double value(String text, int[] n) {
		try {
			return Double.parseDouble(text.substring(n[0], n[1]).replace(',', '.'));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Near your X or Z (or its nether / overworld equivalent) - catches a number standing alone that no other rule sees.
	 * {@code v} is the number's size. Within 2 blocks under 150, within 24 above (a schematic or waypoint right by you);
	 * numbers under 50 never count. Kept tight: wider ranges hid FPS counters and GPU names ("RTX 3060") whenever you
	 * stood at a matching X.
	 */
	private static boolean nearPlayer(double v, double[] player) {
		if (player == null || v < 50) {
			return false;
		}
		double range = v >= 150 ? 24 : 2;
		for (double p : player) {
			double a = Math.abs(p);
			if (a >= 50 && (Math.abs(v - a) <= range || Math.abs(v - a / 8) <= range || a >= 400 && Math.abs(v - a * 8) <= range * 8)) {
				return true;
			}
		}
		return false;
	}

	private static double[] playerXZ() {
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (player == null) {
			return null;
		}
		return new double[] {player.getX(), player.getZ()};
	}

	/** Changes every 16 blocks you move, so "near you" masks don't go stale. */
	private static long playerBlockKey() {
		double[] p = playerXZ();
		return p == null ? 0 : ((long) Math.floor(p[0] / 16) << 32) ^ (long) Math.floor(p[1] / 16);
	}

	// ---- strings with § codes ----

	/**
	 * {@code s} with its coordinates wrapped in §k (obfuscated). After each one the colour and formats that were active
	 * come back (§r would otherwise drop them).
	 */
	public static String scramble(String s) {
		if (s == null || s.isEmpty() || !hasDigit(s)) {
			return s;
		}
		// Plain text without § codes, and where each plain character sits in s.
		StringBuilder plain = new StringBuilder(s.length());
		int[] at = new int[s.length()];
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				i++;
				continue;
			}
			at[plain.length()] = i;
			plain.append(c);
		}
		BitSet mask = mask(plain.toString());
		if (mask.isEmpty()) {
			return s;
		}
		BitSet hide = new BitSet();
		for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
			hide.set(at[i]);
		}
		StringBuilder out = new StringBuilder(s.length() + 16);
		char color = 0;
		StringBuilder formats = new StringBuilder();
		boolean inside = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				char code = Character.toLowerCase(s.charAt(i + 1));
				if (code == 'r') {
					color = 0;
					formats.setLength(0);
				} else if ("0123456789abcdef".indexOf(code) >= 0) {
					color = code;
					formats.setLength(0);
				} else if ("klmno".indexOf(code) >= 0) {
					formats.append('§').append(code);
				}
				out.append(c).append(s.charAt(i + 1));
				i++;
				if (inside) {
					out.append("§k");
				}
				continue;
			}
			boolean masked = hide.get(i);
			if (masked && !inside) {
				out.append("§k");
				inside = true;
			} else if (!masked && inside) {
				out.append("§r");
				if (color != 0) {
					out.append('§').append(color);
				}
				out.append(formats);
				inside = false;
			}
			out.append(c);
		}
		return out.toString();
	}

	// ---- styled text ----

	//? if <26.1 {
	/** The same for styled text (what GUIs and mods mostly draw): masked characters get the obfuscated style. */
	public static net.minecraft.text.OrderedText scramble(net.minecraft.text.OrderedText text) {
		if (text == null) {
			return null;
		}
		StringBuilder plain = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			plain.appendCodePoint(codePoint);
			return true;
		});
		String string = plain.toString();
		BitSet mask = mask(string);
		if (mask.isEmpty()) {
			return text;
		}
		return visitor -> {
			int[] offset = {0};
			return text.accept((index, style, codePoint) -> {
				int at = offset[0];
				offset[0] += Character.charCount(codePoint);
				return visitor.accept(index, mask.get(at) ? style.withObfuscated(true) : style, codePoint);
			});
		};
	}
	//?} else {
	/*public static net.minecraft.util.FormattedCharSequence scramble(net.minecraft.util.FormattedCharSequence text) {
		if (text == null) {
			return null;
		}
		StringBuilder plain = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			plain.appendCodePoint(codePoint);
			return true;
		});
		String string = plain.toString();
		BitSet mask = mask(string);
		if (mask.isEmpty()) {
			return text;
		}
		return sink -> {
			int[] offset = {0};
			return text.accept((index, style, codePoint) -> {
				int at = offset[0];
				offset[0] += Character.charCount(codePoint);
				return sink.accept(index, mask.get(at) ? style.withObfuscated(true) : style, codePoint);
			});
		};
	}
	*///?}
}
