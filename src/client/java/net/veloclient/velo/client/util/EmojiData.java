package net.veloclient.velo.client.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The emoji the chat font knows (assets/velo-client/emoji/shortcodes.json, built by tools/emoji/build_emoji.py):
 * Discord-style {@code :shortcode:} names, which code points have a glyph, and the suggestion search. Plain Java,
 * no Minecraft types - the version-specific text work lives in {@link EmojiStyler}.
 */
public final class EmojiData {

	/** {@code symbol}: a plain text symbol (★ ♥ →) - drawn in the normal font, not as an emoji picture. */
	public record Suggestion(String name, String emoji, boolean symbol) {
		public Suggestion(String name, String emoji) {
			this(name, emoji, false);
		}
	}

	/** Text symbols worth typing in chat (they render in Minecraft's own font on every server). */
	private static final String[][] SYMBOLS = {
			{"star", "★"}, {"star_outline", "☆"}, {"sparkle", "✦"}, {"heart", "❤"}, {"heart_small", "♥"}, {"check", "✔"},
			{"cross", "✖"}, {"arrow_right", "→"}, {"arrow_left", "←"}, {"arrow_up", "↑"}, {"arrow_down", "↓"}, {"double_arrow", "»"},
			{"swords", "⚔"}, {"skull", "☠"}, {"lightning", "⚡"}, {"crown", "♛"}, {"diamond", "♦"}, {"spade", "♠"},
			{"club", "♣"}, {"music", "♪"}, {"notes", "♫"}, {"sun", "☀"}, {"cloud", "☁"}, {"snow", "❄"}, {"flower", "✿"},
			{"warning", "⚠"}, {"infinity", "∞"}, {"bullet", "•"}, {"dot", "·"}, {"play", "▶"}, {"square", "■"}, {"circle", "●"},
			{"smile", "☺"}, {"peace", "☮"}, {"yinyang", "☯"}, {"gear", "⚙"}, {"pencil", "✎"}, {"scissors", "✂"}, {"plane", "✈"},
			{"mail", "✉"}, {"phone", "☎"}, {"coffee", "☕"}, {"hourglass", "⌛"}, {"flag", "⚑"}, {"male", "♂"}, {"female", "♀"},
			{"degree", "°"}, {"copyright", "©"}, {"tm", "™"}, {"section", "§"}, {"plusminus", "±"}, {"times", "×"}, {"divide", "÷"},
			{"approx", "≈"}, {"not_equal", "≠"}, {"euro", "€"}, {"pound", "£"}, {"yen", "¥"}, {"shrug", "¯\\_(ツ)_/¯"},
			{"tableflip", "(╯°□°)╯︵ ┻━┻"}, {"lenny", "( ͡° ͜ʖ ͡°)"}};

	/** What a bare ":" shows: symbols first, then popular emoji. */
	public static List<Suggestion> starters(int limit) {
		load();
		List<Suggestion> out = new ArrayList<>();
		for (String[] s : SYMBOLS) {
			if (out.size() < limit * 2 / 3) {
				out.add(new Suggestion(s[0], s[1], true));
			}
		}
		for (String name : popular) {
			String emoji = byName.get(name);
			if (emoji != null && out.size() < limit) {
				out.add(new Suggestion(name, emoji));
			}
		}
		return out;
	}

	private static final Pattern SHORTCODE = Pattern.compile(":([a-z0-9_+\\-]{1,32}):");
	private static volatile Map<String, String> byName;
	private static volatile Set<Integer> codePoints;
	private static volatile List<String> popular;

	private EmojiData() {
	}

	private static void load() {
		if (byName != null) {
			return;
		}
		Map<String, String> names = new LinkedHashMap<>();
		Set<Integer> cps = new HashSet<>();
		List<String> order = new ArrayList<>();
		try (InputStream in = EmojiData.class.getResourceAsStream("/assets/velo-client/emoji/shortcodes.json")) {
			if (in != null) {
				JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : root.entrySet()) {
					if (e.getKey().equals("_order")) {
						e.getValue().getAsJsonArray().forEach(x -> order.add(x.getAsString()));
						continue;
					}
					String emoji = e.getValue().getAsString();
					names.put(e.getKey(), emoji);
					cps.add(emoji.codePointAt(0));
				}
			}
		} catch (Exception e) {
			// No emoji then - chat works as before.
		}
		popular = order;
		codePoints = cps;
		byName = names;
	}

	/** Whether the emoji font has a glyph for this code point. */
	public static boolean isEmoji(int codePoint) {
		load();
		return codePoints.contains(codePoint);
	}

	public static String byName(String name) {
		load();
		return byName.get(name.toLowerCase(Locale.ROOT));
	}

	/** {@code :smile:} -> the emoji, for every shortcode we know; unknown ones stay as typed. */
	public static String replaceShortcodes(String text) {
		if (text == null || text.indexOf(':') < 0) {
			return text;
		}
		load();
		Matcher m = SHORTCODE.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String emoji = byName.get(m.group(1).toLowerCase(Locale.ROOT));
			m.appendReplacement(out, Matcher.quoteReplacement(emoji != null ? emoji : m.group()));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** Up to {@code limit} shortcodes starting with (then containing) {@code query}, popular ones first. */
	public static List<Suggestion> search(String query, int limit) {
		load();
		String q = query.toLowerCase(Locale.ROOT);
		List<Suggestion> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Map<String, String> emojiSeen = new HashMap<>();
		java.util.function.Consumer<String> add = name -> {
			String emoji = byName.get(name);
			if (emoji != null && out.size() < limit && seen.add(name) && emojiSeen.putIfAbsent(emoji, name) == null) {
				out.add(new Suggestion(name, emoji));
			}
		};
		for (String[] sym : SYMBOLS) {
			if (sym[0].startsWith(q) && out.size() < limit && seen.add("sym:" + sym[0])) {
				out.add(new Suggestion(sym[0], sym[1], true));
			}
		}
		for (String name : popular) {
			if (name.startsWith(q)) {
				add.accept(name);
			}
		}
		for (String name : byName.keySet()) {
			if (name.startsWith(q)) {
				add.accept(name);
			}
		}
		for (String name : byName.keySet()) {
			if (name.contains(q)) {
				add.accept(name);
			}
		}
		return out;
	}

	/** The {@code :query} being typed right before the cursor (at least 2 letters), or null. */
	public static String typedQuery(String text, int cursor) {
		if (text == null || cursor <= 0 || cursor > text.length() || text.startsWith("/")) {
			return null;
		}
		int colon = text.lastIndexOf(':', cursor - 1);
		if (colon < 0) {
			return null;
		}
		if (colon > 0 && !Character.isWhitespace(text.charAt(colon - 1))) {
			return null;
		}
		String q = text.substring(colon + 1, cursor);
		return q.matches("[a-zA-Z0-9_+\\-]{0,32}") ? q : null;
	}
}
