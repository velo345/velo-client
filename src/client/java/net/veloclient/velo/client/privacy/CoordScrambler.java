package net.veloclient.velo.client.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti Coords Leak's text side: finds the numbers in a line of text that are (or look like) coordinates and hides them -
 * as Minecraft's obfuscated "magic" glyphs (same width, so layouts don't move), or, with {@link #fake}, as believable
 * fake coordinates. Every piece of text the game draws passes through here (AntiCoordsFontMixin on the font itself), so
 * it covers chat, F3, the HUD, signs, name tags and any mod's screens and overlays - Litematica, minimaps, waypoints.
 *
 * <p>A number counts as a coordinate when it
 * <ul>
 * <li>follows an axis label - {@code X: 120}, {@code z=-40}, {@code Y 64};</li>
 * <li>is one of three numbers in a row with one of them 100 or more - {@code 120 64 -40}, {@code 1.5 / 64.0 / -3200.2};</li>
 * <li>is one of two big numbers in a row - {@code 1200 -3400}, {@code 1200, -3400} (both 100 or more);</li>
 * <li>sits in a line that's clearly about a place - "coords", "position", "waypoint", "chunk", "region", "died at",
 * "/tp"... (chunk and region numbers are block coordinates / 16 and / 512);</li>
 * <li>stands in a coordinate editor - Litematica, minimaps' waypoint screens, structure blocks;</li>
 * <li>or matches where you are almost exactly (300 or more, within a block and a half - a coordinate shown alone).</li>
 * </ul>
 * Never: the scoreboard sidebar and the tab list (deaths, kills, playtime, money - see AntiCoordsExemptMixin), numbers
 * with a unit or a stat word after them ("240 fps", "12 kills", "3 hours"), hardware and version names ("RTX 3060"),
 * health ("20/20"), times and versions ("1.21.11"), and numbers glued to a word (player names).
 */
public final class CoordScrambler {

	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:[.,]\\d+)?(?![\\w])");
	// A number glued to a lone axis letter: "x20", "z-340", "Y64" - a mod wrote coordinates like that and they slipped through
	// (NUMBER wants no letter right before the digits). Not "1920x1080" (a digit before the x) or "max20" (part of a word).
	private static final Pattern GLUED = Pattern.compile("(?i)(?<![a-z0-9_.])[xyz](-?\\d+(?:[.,]\\d+)?)(?![\\w])");
	// The axis letter on its own: not part of a word or a name ("Steve_X 1234" isn't a label).
	private static final Pattern AXIS_BEFORE = Pattern.compile("(?i)(?:^|[^a-z0-9_])(x|y|z)\\s*[:=]?\\s*[\\[(]?\\s*$");
	/** What may stand between the numbers of one coordinate: spaces, or a comma / slash / semicolon with spaces. */
	private static final Pattern SEPARATOR = Pattern.compile("\\s*[,/;|]\\s*|\\s+");
	/** Words that make a line about a place - whole words only, nothing that also names a stat ("deaths", "homes"). */
	private static final Pattern PLACE_LINE = Pattern.compile(
			"(?i)\\b(coords?|coordinates?|xyz|position|pos[12]?|location|loc|chunk|region|origin|waypoints?|teleport(ed|ing)?|corner|"
					+ "placement|selection|died at|last death at|death point)\\b|/tp\\b|/tppos\\b");
	private static final Pattern CHUNK_WORD = Pattern.compile("(?i)\\bchunks?\\b");
	private static final Pattern REGION_WORD = Pattern.compile("(?i)\\bregions?\\b|r\\.-?\\d+\\.-?\\d+\\.mca");
	/** A unit or stat word after the number: it's a measurement or a count, not a position. */
	private static final java.util.Set<String> UNITS = java.util.Set.of("fps", "ms", "mspt", "tps", "%", "mb", "gb", "kb", "mib", "gib", "kib",
			"b", "mhz", "ghz", "hz", "px", "cps", "bpm", "kbps", "mbps", "s", "sec", "secs", "second", "seconds", "min", "mins", "minute", "minutes",
			"h", "hr", "hrs", "hour", "hours", "d", "day", "days", "w", "week", "weeks", "m/s", "b/s", "°", "chunks", "entities", "players",
			"online", "ping", "fov", "x", "kills", "kill", "deaths", "death", "wins", "win", "losses", "games", "coins", "coin", "gems", "points",
			"pts", "xp", "exp", "levels", "lvl", "blocks", "items", "votes", "credits", "tokens", "shards", "souls", "mobs", "k", "m", "bn", "$",
			"€", "hp", "dmg", "damage", "health", "armor", "kdr", "streak", "rank", "place", "elo", "mmr", "tickets", "keys");
	/** A word before the number that makes it a model, version, count or stat ("RTX 3060", "Level 30", "Deaths: 120"). */
	private static final java.util.Set<String> NAME_WORDS = java.util.Set.of("rtx", "gtx", "rx", "gt", "geforce", "radeon", "arc", "quadro",
			"ryzen", "core", "xeon", "athlon", "intel", "amd", "nvidia", "java", "jdk", "jre", "version", "ver", "v", "build", "fabric", "sodium",
			"minecraft", "opengl", "vulkan", "driver", "fps", "ping", "tps", "level", "lvl", "page", "mods", "slot", "day", "days", "year",
			"windows", "linux", "kernel", "mesa", "x86", "ram", "memory", "mem", "cpu", "gpu", "allocated", "kills", "kill", "deaths", "death",
			"wins", "losses", "playtime", "played", "time", "balance", "money", "coins", "gems", "points", "score", "rank", "elo", "kdr",
			"streak", "health", "hp", "damage", "durability", "uses", "amount", "count", "price", "cost", "sell", "buy", "worth", "bal",
			"tokens", "credits", "votes", "online", "players", "id", "#", "season", "wave", "round", "floor", "stage", "tier", "lv", "xp");
	/** Screens that are coordinate editors: every larger number there is a position (field by field). */
	private static final String[] COORD_SCREENS = {"fi.dy.masa.", "xaero.", "journeymap.", "com.mamiyaotaru.voxelmap.", "StructureBlock",
			"Waypoint", "waypoint"};

	/** One coordinate found in a line: where it is, which axis it stands for and its scale (0 blocks, 4 chunks, 9 regions). */
	private record Hit(int start, int end, char axis, int shift) {
	}

	private static final List<Hit> NONE = List.of();
	/** Results by plain text - the same lines are drawn every frame. Cleared when it grows or the player moves far. */
	private static final Map<String, List<Hit>> CACHE = new ConcurrentHashMap<>();
	private static volatile long cachedAt;

	/** Lines the scoreboard / tab list handed to the GUI lately (plain text -> when), never hidden. */
	private static final Map<String, Long> EXEMPT = new ConcurrentHashMap<>();
	/** Above 0 while the scoreboard sidebar or the tab list is being drawn (render thread). */
	public static int exemptDepth;

	/** Show believable fake coordinates instead of scrambled glyphs. */
	public static volatile boolean fake;
	/** This session's fake offsets: thousands of blocks, on region borders so chunk and region numbers stay whole. */
	private static final long OFFSET_X;
	private static final long OFFSET_Y;
	private static final long OFFSET_Z;

	static {
		java.util.Random random = new java.security.SecureRandom();
		OFFSET_X = (random.nextBoolean() ? 1 : -1) * 512L * (6 + random.nextInt(14));
		OFFSET_Z = (random.nextBoolean() ? 1 : -1) * 512L * (6 + random.nextInt(14));
		OFFSET_Y = (random.nextBoolean() ? 1 : -1) * (9 + random.nextInt(18));
	}

	private CoordScrambler() {
	}

	/** Remembers a line the scoreboard or tab list is drawing this frame. */
	public static void exempt(String line) {
		if (line.isEmpty() || !hasDigit(line)) {
			return;
		}
		if (line.indexOf('§') >= 0) {
			line = line.replaceAll("§.", "");
		}
		long now = System.nanoTime();
		if (EXEMPT.size() > 512) {
			EXEMPT.entrySet().removeIf(e -> now - e.getValue() > 1_000_000_000L);
		}
		EXEMPT.put(line, now);
	}

	private static boolean exempted(String line) {
		Long at = EXEMPT.get(line);
		return at != null && System.nanoTime() - at < 2_000_000_000L;
	}

	/** The coordinates in {@code text} (empty when there are none). */
	private static List<Hit> hits(String text) {
		if (text.isEmpty() || !hasDigit(text) || exempted(text)) {
			return NONE;
		}
		long block = playerBlockKey();
		if (CACHE.size() > 4096 || block != cachedAt) {
			CACHE.clear();
			cachedAt = block;
		}
		boolean coordScreen = coordScreen();
		String key = coordScreen ? '\u0001' + text : text;
		List<Hit> cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		List<Hit> found = compute(text, coordScreen);
		CACHE.put(key, found.isEmpty() ? NONE : found);
		return found;
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

	private static List<Hit> compute(String text, boolean coordScreen) {
		List<int[]> numbers = new ArrayList<>();
		Matcher m = NUMBER.matcher(text);
		while (m.find()) {
			numbers.add(new int[] {m.start(), m.end()});
		}
		java.util.Set<Integer> glued = new java.util.HashSet<>();
		Matcher g = GLUED.matcher(text);
		while (g.find()) {
			numbers.add(new int[] {g.start(1), g.end(1)});
			glued.add(g.start(1));
		}
		if (!glued.isEmpty()) {
			numbers.sort(java.util.Comparator.comparingInt(n -> n[0]));
		}
		if (numbers.isEmpty()) {
			return NONE;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		boolean placeLine = PLACE_LINE.matcher(text).find();
		int lineShift = REGION_WORD.matcher(text).find() ? 9 : CHUNK_WORD.matcher(text).find() ? 4 : 0;
		double[] player = playerXZ();
		List<Hit> hits = new ArrayList<>();
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
			boolean run3 = count == 3 && biggest >= 100;
			boolean run2 = count == 2 && Math.abs(value(text, numbers.get(runStart))) >= 100 && Math.abs(value(text, numbers.get(runStart + 1))) >= 100
					&& !text.substring(numbers.get(runStart)[1], numbers.get(runStart + 1)[0]).contains("/");
			for (int j = runStart; j < i; j++) {
				int[] n = numbers.get(j);
				double v = Math.abs(value(text, n));
				char axis = axisBefore(text, n[0]);
				if (glued.contains(n[0]) && v < 10) {
					continue;   // "x2", "x5": a multiplier, not a position
				}
				if (axis == 0 && (unitAfter(text, n[1]) || nameBefore(lower, n[0]))) {
					continue;
				}
				char near = nearPlayer(v, player);
				boolean hide = axis != 0 || run3 || run2 || placeLine && v >= 8 || coordScreen && v >= 50 || near != 0;
				if (!hide) {
					continue;
				}
				// Which axis it stands for: its label, its place in a run (x y z / x z), or the one it's close to.
				char forAxis = axis != 0 ? axis : run3 ? "xyz".charAt(j - runStart) : run2 ? (j == runStart ? 'x' : 'z') : near != 0 ? near : 'x';
				hits.add(new Hit(n[0], n[1], forAxis, lineShift));
			}
			runStart = i;
		}
		return hits;
	}

	private static boolean unitAfter(String text, int end) {
		int i = end;
		while (i < text.length() && text.charAt(i) == ' ') {
			i++;
		}
		int from = i;
		while (i < text.length() && (Character.isLetter(text.charAt(i)) || "%°/$€".indexOf(text.charAt(i)) >= 0)) {
			i++;
		}
		return i > from && UNITS.contains(text.substring(from, i).toLowerCase(Locale.ROOT));
	}

	private static boolean nameBefore(String lower, int start) {
		int i = start - 1;
		while (i >= 0 && (lower.charAt(i) == ' ' || lower.charAt(i) == ':' || lower.charAt(i) == '=')) {
			i--;
		}
		if (i >= 0 && "$€#".indexOf(lower.charAt(i)) >= 0) {
			return true;
		}
		int end = i + 1;
		while (i >= 0 && Character.isLetterOrDigit(lower.charAt(i))) {
			i--;
		}
		return end > i + 1 && NAME_WORDS.contains(lower.substring(i + 1, end));
	}

	/** The axis label right before a number ('x', 'y', 'z'), or 0. */
	private static char axisBefore(String text, int start) {
		int from = Math.max(0, start - 6);
		Matcher m = AXIS_BEFORE.matcher(text.substring(from, start));
		if (!m.find()) {
			return 0;
		}
		char letter = Character.toLowerCase(m.group(1).charAt(0));
		// "1920 x 1080": an x right after a number is "times", not an axis.
		int i = from + m.start(1) - 1;
		while (i >= 0 && text.charAt(i) == ' ') {
			i--;
		}
		return i >= 0 && Character.isDigit(text.charAt(i)) && letter == 'x' ? 0 : letter;
	}

	private static double value(String text, int[] n) {
		try {
			return Double.parseDouble(text.substring(n[0], n[1]).replace(',', '.'));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * A number standing alone that is your X or Z almost exactly (or its nether / overworld equivalent): 'x' / 'z', or 0.
	 * Only 300 and up, within a block and a half - wider ranges hid counters, prices and item numbers whenever they
	 * happened to be close to where you stood.
	 */
	private static char nearPlayer(double v, double[] player) {
		if (player == null || v < 300) {
			return 0;
		}
		for (int k = 0; k < 2; k++) {
			double a = Math.abs(player[k]);
			if (a >= 300 && (Math.abs(v - a) <= 1.5 || a >= 2400 && Math.abs(v - a / 8) <= 1.5)) {
				return k == 0 ? 'x' : 'z';
			}
		}
		return 0;
	}

	private static double[] playerXZ() {
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (player == null) {
			return null;
		}
		return new double[] {player.getX(), player.getZ()};
	}

	/** Changes every 16 blocks you move, so "near you" results don't go stale. */
	private static long playerBlockKey() {
		double[] p = playerXZ();
		return p == null ? 0 : ((long) Math.floor(p[0] / 16) << 32) ^ (long) Math.floor(p[1] / 16);
	}

	// ---- fake values ----

	/**
	 * The fake for the number {@code original} (as written): the real value moved by this session's offset for its axis
	 * (scaled for chunks and regions), written the same way - sign, decimals, comma or point. The offset never changes,
	 * so the fake follows you as you move and stays consistent everywhere it shows.
	 */
	private static String fakeFor(String original, Hit hit) {
		boolean comma = original.indexOf(',') >= 0;
		String normal = original.replace(',', '.');
		int decimals = normal.indexOf('.') < 0 ? 0 : normal.length() - normal.indexOf('.') - 1;
		double v;
		try {
			v = Double.parseDouble(normal);
		} catch (NumberFormatException e) {
			return original;
		}
		long offset = hit.axis() == 'y' ? OFFSET_Y : hit.axis() == 'z' ? OFFSET_Z : OFFSET_X;
		if (hit.axis() != 'y') {
			offset >>= hit.shift();
		}
		double out = v + offset;
		if (hit.axis() == 'y') {
			// Keep heights believable.
			out = Math.max(-63, Math.min(318, out));
		}
		String formatted = decimals == 0 ? Long.toString(Math.round(out)) : String.format(Locale.ROOT, "%." + decimals + "f", out);
		return comma ? formatted.replace('.', ',') : formatted;
	}

	// ---- strings with § codes ----

	/** {@code s} with its coordinates hidden: wrapped in §k (the colour and formats come back after), or faked. */
	public static String scramble(String s) {
		if (s == null || s.isEmpty() || !hasDigit(s)) {
			return s;
		}
		// Most strings (HUD values) have no § codes: look them up as they are, without building the plain copy.
		if (s.indexOf('§') < 0 && hits(s).isEmpty()) {
			return s;
		}
		// Plain text without § codes, and where each plain character sits in s.
		StringBuilder plain = new StringBuilder(s.length());
		int[] at = new int[s.length() + 1];
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				i++;
				continue;
			}
			at[plain.length()] = i;
			plain.append(c);
		}
		String plainText = plain.toString();
		List<Hit> hits = hits(plainText);
		if (hits.isEmpty()) {
			return s;
		}
		if (fake) {
			// Numbers have no § codes inside them: replace each in place, from the back.
			StringBuilder out = new StringBuilder(s);
			for (int k = hits.size() - 1; k >= 0; k--) {
				Hit hit = hits.get(k);
				int from = at[hit.start()];
				int to = at[hit.end() - 1] + 1;
				out.replace(from, to, fakeFor(plainText.substring(hit.start(), hit.end()), hit));
			}
			return out.toString();
		}
		java.util.BitSet hide = new java.util.BitSet();
		for (Hit hit : hits) {
			for (int i = hit.start(); i < hit.end(); i++) {
				hide.set(at[i]);
			}
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

	/** Plans per text (the fakes only depend on the text and this session's offset), cleared with the hit cache. */
	private static final Map<String, Map<Integer, Object[]>> PLANS = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile long plansAt;

	/** Where each hit starts / what replaces it, by plain-text index (for the styled versions below). */
	private static Map<Integer, Object[]> plan(String plainText, List<Hit> hits) {
		// HUD text is prepared again every frame: building the plan (with String.format) each time cost ~1.5 % of a frame
		if (PLANS.size() > 4096 || plansAt != cachedAt) {
			PLANS.clear();
			plansAt = cachedAt;
		}
		String key = coordScreen() ? '\u0001' + plainText : plainText; // the same split as the hit cache
		Map<Integer, Object[]> known = PLANS.get(key);
		if (known != null) {
			return known;
		}
		Map<Integer, Object[]> made = buildPlan(plainText, hits);
		PLANS.put(key, made);
		return made;
	}

	private static Map<Integer, Object[]> buildPlan(String plainText, List<Hit> hits) {
		Map<Integer, Object[]> plan = new java.util.HashMap<>();
		for (Hit hit : hits) {
			plan.put(hit.start(), new Object[] {hit.end(), fakeFor(plainText.substring(hit.start(), hit.end()), hit)});
		}
		return plan;
	}

	// ---- styled text ----

	//? if <26.1 {
	/** Plain text of a styled line. */
	public static String plain(net.minecraft.text.OrderedText text) {
		StringBuilder plain = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			plain.appendCodePoint(codePoint);
			return true;
		});
		return plain.toString();
	}

	/** The same for styled text (what GUIs and mods mostly draw): obfuscated style, or the fake digits in its style. */
	public static net.minecraft.text.OrderedText scramble(net.minecraft.text.OrderedText text) {
		if (text == null) {
			return null;
		}
		String string = plain(text);
		List<Hit> hits = hits(string);
		if (hits.isEmpty()) {
			return text;
		}
		if (fake) {
			Map<Integer, Object[]> plan = plan(string, hits);
			return visitor -> {
				int[] offset = {0};
				int[] skipUntil = {-1};
				return text.accept((index, style, codePoint) -> {
					int at = offset[0];
					offset[0] += Character.charCount(codePoint);
					if (at < skipUntil[0]) {
						return true;
					}
					Object[] step = plan.get(at);
					if (step != null) {
						skipUntil[0] = (int) step[0];
						String replacement = (String) step[1];
						for (int k = 0; k < replacement.length(); k++) {
							if (!visitor.accept(index, style, replacement.charAt(k))) {
								return false;
							}
						}
						return true;
					}
					return visitor.accept(index, style, codePoint);
				});
			};
		}
		java.util.BitSet mask = new java.util.BitSet();
		hits.forEach(h -> mask.set(h.start(), h.end()));
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
	/*public static String plain(net.minecraft.util.FormattedCharSequence text) {
		StringBuilder plain = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			plain.appendCodePoint(codePoint);
			return true;
		});
		return plain.toString();
	}

	public static net.minecraft.util.FormattedCharSequence scramble(net.minecraft.util.FormattedCharSequence text) {
		if (text == null) {
			return null;
		}
		String string = plain(text);
		List<Hit> hits = hits(string);
		if (hits.isEmpty()) {
			return text;
		}
		if (fake) {
			Map<Integer, Object[]> plan = plan(string, hits);
			return sink -> {
				int[] offset = {0};
				int[] skipUntil = {-1};
				return text.accept((index, style, codePoint) -> {
					int at = offset[0];
					offset[0] += Character.charCount(codePoint);
					if (at < skipUntil[0]) {
						return true;
					}
					Object[] step = plan.get(at);
					if (step != null) {
						skipUntil[0] = (int) step[0];
						String replacement = (String) step[1];
						for (int k = 0; k < replacement.length(); k++) {
							if (!sink.accept(index, style, replacement.charAt(k))) {
								return false;
							}
						}
						return true;
					}
					return sink.accept(index, style, codePoint);
				});
			};
		}
		java.util.BitSet mask = new java.util.BitSet();
		hits.forEach(h -> mask.set(h.start(), h.end()));
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
