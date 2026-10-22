package net.veloclient.velo.client.namestyle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.config.VeloPaths;
//? if <26.1 {
import net.minecraft.text.StyleSpriteSource;
//?} else {
/*import net.minecraft.network.chat.FontDescription;
*///?}

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velo+ Name Styles in the game: whose name looks how, and drawing it.
 *
 * <p><b>Tagging.</b> Where a name appears (a chat line when it arrives, a tab list row, a name tag) each of its letters
 * gets a marker "font" {@code velo-client:ns/<place>/<font>/<letter>/<length>} and the player name as insertion (vanilla
 * puts the name there too, for shift-click). {@code VeloFonts} draws (and measures) a marker in the style's chosen font,
 * so widths always match; a marked letter that is never restyled just keeps its original colour.
 *
 * <p><b>Drawing.</b> Every glyph goes through {@link #restyle} ({@code NameStyleGlyphMixin}); a marked one gets its colour
 * for this frame from {@link NameStyleEffects}, so names animate without re-tagging anything.
 *
 * <p><b>Your own style</b> lives in {@code config/name-style.json}, shared with the launcher's Locker: the game rereads
 * it when it changes, so editing it in the launcher shows up in game straight away (and goes out with the next
 * heartbeat). Other players' styles come from the Velo Network online list; what of them you see is up to you
 * (Velo Network settings).
 */
public final class NameStyles {

	/** Where a name is drawn. */
	public static final int CHAT = 0;
	public static final int TAB = 1;
	public static final int NAME_TAG = 2;
	/** The Locker's live previews (this and up): each shows the style it was given, not the saved one. */
	public static final int PREVIEW = 3;
	private static final int PREVIEW_SLOTS = 96;
	private static final String[] PLACE_IDS = {"c", "t", "n"};
	private static final int MAX_LETTERS = 24;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long START = System.nanoTime();

	/** The file both apps share. {@code style} is the wire form, {@code enabled} whether it's on. */
	public record Saved(boolean enabled, String style) {
	}

	/** What of other players' styles you see. */
	public record Viewer(boolean others, boolean chat, boolean tab, boolean nameTags, boolean animate) {
	}

	private static volatile NameStyle own = NameStyle.defaults();
	private static volatile boolean ownEnabled;
	private static long fileStamp = -1;
	private static long nextFileCheck;
	private static volatile Viewer viewer;
	private static volatile boolean plusCached;
	private static long nextPlusCheck;
	private static final Map<String, NameStyle> DECODED = new ConcurrentHashMap<>();
	private static final Map<Identifier, int[]> MARKER_OF = new ConcurrentHashMap<>();
	private static final Map<String, Object> MARKERS = new ConcurrentHashMap<>();
	private static final NameStyle[] PREVIEWS = new NameStyle[PREVIEW_SLOTS];

	private NameStyles() {
	}

	// ---- Your own style ----

	private static Path file() {
		return VeloPaths.config().resolve("name-style.json");
	}

	/** Rereads {@code name-style.json} if it changed (the launcher may have edited it); cheap, checked at most twice a second. */
	private static void refreshOwn() {
		long now = System.currentTimeMillis();
		if (now < nextFileCheck) {
			return;
		}
		nextFileCheck = now + 500;
		try {
			Path file = file();
			long stamp = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
			if (stamp == fileStamp) {
				return;
			}
			fileStamp = stamp;
			if (stamp == 0) {
				ownEnabled = false;
				return;
			}
			Saved saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
			NameStyle parsed = saved == null ? null : NameStyle.decode(saved.style());
			own = parsed == null ? NameStyle.defaults() : parsed;
			ownEnabled = saved != null && saved.enabled() && parsed != null;
			// Changed in the launcher's Locker: other players see it right away too.
			net.veloclient.velo.client.network.VeloServerClient.syncSoon();
		} catch (Exception e) {
			// Half-written by the launcher - read again next time.
			fileStamp = -1;
		}
	}

	public static NameStyle own() {
		refreshOwn();
		return own;
	}

	public static boolean ownEnabled() {
		refreshOwn();
		return ownEnabled;
	}

	/** Saves your style (the launcher sees it too). */
	public static void save(NameStyle style, boolean enabled) {
		own = style;
		ownEnabled = enabled;
		try {
			Files.createDirectories(file().getParent());
			Path temp = file().resolveSibling("name-style.json.tmp");
			Files.writeString(temp, GSON.toJson(new Saved(enabled, style.encode())), StandardCharsets.UTF_8);
			Files.move(temp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			fileStamp = Files.getLastModifiedTime(file()).toMillis();
		} catch (Exception e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't save your name style", e);
		}
		net.veloclient.velo.client.network.VeloServerClient.syncSoon();
		// The look you have on keeps its own name style.
		net.veloclient.velo.client.cosmetics.Loadouts.changed();
	}

	/** Whether you're a Velo+ member (online answer, else the signed proof on disk); rechecked every 30 seconds. */
	public static boolean plus() {
		if (System.getProperty("velo.screenshotTour") != null) {
			return true; // dev-only screenshot tour: show the Velo+ screens as a member sees them
		}
		long now = System.currentTimeMillis();
		if (now >= nextPlusCheck) {
			nextPlusCheck = now + 30_000;
			plusCached = net.veloclient.velo.client.network.StoreClient.plus() || net.veloclient.velo.client.plus.PlusAccess.allowed();
		}
		return plusCached;
	}

	/** What the heartbeat sends: your style if it's on and you're Velo+, else null. */
	public static String networkValue() {
		return ownEnabled() && plus() ? own().encode() : null;
	}

	// ---- What you see of others ----

	public static Viewer viewer() {
		Viewer v = viewer;
		if (v == null) {
			v = net.veloclient.velo.config.ConfigManager.load("name-style-viewer", Viewer.class, new Viewer(true, true, true, true, true));
			viewer = v;
		}
		return v;
	}

	public static void setViewer(Viewer value) {
		viewer = value;
		net.veloclient.velo.config.ConfigManager.save("name-style-viewer", value);
	}

	private static String selfName() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			return client.player.getGameProfile().name();
		}
		//? if <26.1 {
		return client.getSession().getUsername();
		//?} else {
		/*return client.getUser().getName();
		*///?}
	}

	/** The style {@code name} shows at {@code place} to you, or null. */
	public static NameStyle styleFor(String name, int place) {
		if (place >= PREVIEW) {
			return PREVIEWS[place - PREVIEW];
		}
		if (name == null) {
			return null;
		}
		if (name.equalsIgnoreCase(selfName())) {
			if (!ownEnabled() || !plus()) {
				return null;
			}
			NameStyle style = own();
			return shownAt(style, place) ? style : null;
		}
		Viewer v = viewer();
		if (!v.others() || place == CHAT && !v.chat() || place == TAB && !v.tab() || place == NAME_TAG && !v.nameTags()) {
			return null;
		}
		String wire = net.veloclient.velo.client.network.VeloUserRegistry.nameStyleForName(name);
		if (wire == null) {
			return null;
		}
		NameStyle style = DECODED.computeIfAbsent(wire, w -> Optional.ofNullable(NameStyle.decode(w)).orElse(NameStyle.defaults().withPlaces(false, false, false)));
		return shownAt(style, place) ? style : null;
	}

	private static boolean shownAt(NameStyle style, int place) {
		return place == CHAT ? style.chat() : place == TAB ? style.tab() : style.nameTag();
	}

	/** Every name that has a style in chat right now (yours and online Velo+ players'). */
	private static List<String> chatNames() {
		List<String> names = new ArrayList<>();
		String self = selfName();
		if (styleFor(self, CHAT) != null) {
			names.add(self);
		}
		if (viewer().others() && viewer().chat()) {
			for (String name : net.veloclient.velo.client.network.VeloUserRegistry.styledNames()) {
				if (!name.equalsIgnoreCase(self) && styleFor(name, CHAT) != null) {
					names.add(name);
				}
			}
		}
		return names;
	}

	// ---- Tagging ----

	/** A chat line: every styled name in it gets marked. */
	public static Text tagChat(Text message) {
		if (message == null) {
			return null;
		}
		List<String> names = chatNames();
		if (names.isEmpty()) {
			return message;
		}
		String plain = visible(message.getString()).toLowerCase(java.util.Locale.ROOT);
		boolean any = false;
		for (String name : names) {
			if (plain.contains(name.toLowerCase(java.util.Locale.ROOT))) {
				any = true;
				break;
			}
		}
		return any ? tag(message, CHAT, names) : message;
	}

	/** A tab list row or name tag for the player called {@code name}. */
	public static Text tagName(Text text, String name, int place) {
		if (text == null || name == null || styleFor(name, place) == null
				|| !visible(text.getString()).toLowerCase(java.util.Locale.ROOT).contains(name.toLowerCase(java.util.Locale.ROOT))) {
			return text;
		}
		return tag(text, place, List.of(name));
	}

	/** {@code raw} without legacy formatting codes ("\u00a7a" etc.) - what is actually drawn. */
	private static String visible(String raw) {
		if (raw.indexOf('\u00a7') < 0) {
			return raw;
		}
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\u00a7' && i + 1 < raw.length()) {
				i++;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * Rebuilds {@code text} with the names marked; everything else keeps its style (colours, hover, click).
	 *
	 * <p>Names are found in the visible text, so a server that colours a name per letter (gradients: one component
	 * per letter) or puts legacy codes between its letters still matches. The name's own letters drop whatever colour
	 * or formatting the server gave them - the name style replaces it - but keep hover/click.
	 */
	private static Text tag(Text text, int place, List<String> names) {
		List<Style> styles = new ArrayList<>();
		List<String> parts = new ArrayList<>();
		text.visit((style, part) -> {
			styles.add(style);
			parts.add(part);
			return Optional.empty();
		}, Style.EMPTY);
		StringBuilder shown = new StringBuilder();
		for (String part : parts) {
			shown.append(visible(part));
		}
		String plain = shown.toString();
		List<int[]> matches = new ArrayList<>();
		int search = 0;
		while (search < plain.length()) {
			int best = -1;
			int bestName = -1;
			for (int n = 0; n < names.size(); n++) {
				int at = find(plain, names.get(n), search);
				if (at >= 0 && (best < 0 || at < best)) {
					best = at;
					bestName = n;
				}
			}
			if (best < 0) {
				break;
			}
			matches.add(new int[] {best, best + names.get(bestName).length(), bestName});
			search = best + names.get(bestName).length();
		}
		if (matches.isEmpty()) {
			return text;
		}
		MutableText out = Text.empty();
		StringBuilder pending = new StringBuilder();
		int v = 0;
		int next = 0;
		int skipUntil = 0;
		for (int p = 0; p < parts.size(); p++) {
			String part = parts.get(p);
			Style style = styles.get(p);
			// Legacy codes only last to the end of their own component; after a name inside one they're put back.
			String codes = "";
			boolean restoreCodes = false;
			for (int i = 0; i < part.length(); i++) {
				char c = part.charAt(i);
				if (c == '\u00a7' && i + 1 < part.length()) {
					char code = Character.toLowerCase(part.charAt(++i));
					codes = code == 'r' ? "" : (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') ? "\u00a7" + code
							: code >= 'k' && code <= 'o' ? codes + "\u00a7" + code : codes;
					if (v >= skipUntil) {
						pending.append('\u00a7').append(code);
					}
					continue;
				}
				if (v < skipUntil) {
					v++;
					restoreCodes = true;
					continue;
				}
				if (next < matches.size() && v == matches.get(next)[0]) {
					int[] match = matches.get(next++);
					flush(out, pending, style);
					appendName(out, names.get(match[2]), Style.EMPTY.withClickEvent(style.getClickEvent()).withHoverEvent(style.getHoverEvent()), place);
					skipUntil = match[1];
					v++;
					restoreCodes = true;
					continue;
				}
				if (restoreCodes) {
					pending.append(codes);
					restoreCodes = false;
				}
				pending.append(c);
				v++;
			}
			flush(out, pending, style);
		}
		return out;
	}

	private static void flush(MutableText out, StringBuilder pending, Style style) {
		if (!pending.isEmpty()) {
			out.append(Text.literal(pending.toString()).setStyle(style));
			pending.setLength(0);
		}
	}

	/** {@code name} at or after {@code from} as a whole word (any case), or -1. */
	private static int find(String part, String name, int from) {
		String haystack = part.toLowerCase(java.util.Locale.ROOT);
		String needle = name.toLowerCase(java.util.Locale.ROOT);
		int at = haystack.indexOf(needle, from);
		while (at >= 0) {
			boolean before = at == 0 || !wordChar(part.charAt(at - 1));
			int end = at + name.length();
			boolean after = end >= part.length() || !wordChar(part.charAt(end));
			if (before && after) {
				return at;
			}
			at = haystack.indexOf(needle, at + 1);
		}
		return -1;
	}

	private static boolean wordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static void appendName(MutableText out, String name, Style base, int place) {
		NameStyle style = styleFor(name, place);
		int font = style == null ? 0 : style.font();
		String icon = style == null ? "" : style.iconText();
		String letters = (icon.isEmpty() ? "" : icon + " ") + name;
		int length = Math.min(MAX_LETTERS, letters.length());
		Style marked = base.withInsertion(name);
		if (style != null) {
			if (style.bold()) {
				marked = marked.withBold(true);
			}
			if (style.italic()) {
				marked = marked.withItalic(true);
			}
			if (style.underline()) {
				//? if <26.1 {
				marked = marked.withUnderline(true);
				//?} else {
				/*marked = marked.withUnderlined(true);
				*///?}
			}
		}
		for (int i = 0; i < letters.length(); i++) {
			Style letter = i < length ? marked.withFont(marker(place, font, i, length)) : marked;
			out.append(Text.literal(String.valueOf(letters.charAt(i))).setStyle(letter));
		}
	}

	// ---- Drawing ----

	/** Called for every glyph drawn: a marked letter of a styled name gets this frame's colour; anything else is returned as is. */
	public static Style restyle(Style style) {
		int[] marker = markerOf(style);
		if (marker == null) {
			return style;
		}
		NameStyle nameStyle = styleFor(style.getInsertion(), marker[0]);
		if (nameStyle == null) {
			return style;
		}
		double seconds = viewer().animate() || marker[0] >= PREVIEW || isSelf(style.getInsertion()) ? (System.nanoTime() - START) / 1e9 : 0;
		int rgb = NameStyleEffects.color(nameStyle, marker[2], marker[3], seconds);
		Style out = style.withColor(rgb);
		if (nameStyle.glow()) {
			// A soft halo in the letter's own colour (translucent), not a hard dark copy. In chat and the tab list the text
			// is small: a strong coloured copy one pixel off smears the letters, so it's kept faint there.
			int alpha = marker[0] == CHAT || marker[0] == TAB ? 0x48000000 : 0x8C000000;
			out = out.withShadowColor(alpha | NameStyleEffects.glow(rgb));
		}
		if (NameStyleEffects.scrambled(nameStyle, marker[2], seconds)) {
			out = out.withObfuscated(true);
		}
		return out;
	}

	private static boolean isSelf(String name) {
		return name != null && name.equalsIgnoreCase(selfName());
	}

	/** {place, font, letter, length} for a marked style, else null. */
	private static int[] markerOf(Style style) {
		//? if <26.1 {
		if (!(style.getFont() instanceof StyleSpriteSource.Font font)) {
			return null;
		}
		//?} else {
		/*if (!(style.getFont() instanceof FontDescription.Resource font)) {
			return null;
		}
		*///?}
		Identifier id = font.id();
		if (!"velo-client".equals(id.getNamespace()) || !id.getPath().startsWith("ns/")) {
			return null;
		}
		return MARKER_OF.computeIfAbsent(id, NameStyles::parseMarker);
	}

	private static int[] parseMarker(Identifier id) {
		String[] parts = id.getPath().split("/");
		int place = switch (parts[1]) {
			case "t" -> TAB;
			case "n" -> NAME_TAG;
			case "c" -> CHAT;
			default -> PREVIEW + Integer.parseInt(parts[1].substring(1));
		};
		return new int[] {place, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4])};
	}

	/**
	 * For {@code VeloFonts}: the font id a marker path ({@code ns/<place>/<font>/...}) is drawn in, or null if
	 * {@code path} isn't a marker. {@code "minecraft:default"} means the plain default font.
	 */
	public static boolean isNameTagMarker(String path) {
		return path.startsWith("ns/n/");
	}

	public static String markerFont(String path) {
		if (!path.startsWith("ns/")) {
			return null;
		}
		int[] marker = MARKER_OF.computeIfAbsent(Identifier.of("velo-client", path), NameStyles::parseMarker);
		return NameStyle.FONTS[Math.max(0, Math.min(NameStyle.FONTS.length - 1, marker[1]))][1];
	}

	//? if <26.1 {
	private static StyleSpriteSource marker(int place, int font, int letter, int length) {
		String path = "ns/" + placeId(place) + "/" + font + "/" + letter + "/" + length;
		return (StyleSpriteSource) MARKERS.computeIfAbsent(path, p -> new StyleSpriteSource.Font(Identifier.of("velo-client", p)));
	}
	//?} else {
	/*private static FontDescription marker(int place, int font, int letter, int length) {
		String path = "ns/" + placeId(place) + "/" + font + "/" + letter + "/" + length;
		return (FontDescription) MARKERS.computeIfAbsent(path, p -> new FontDescription.Resource(Identifier.of("velo-client", p)));
	}
	*///?}

	private static String placeId(int place) {
		return place >= PREVIEW ? "p" + (place - PREVIEW) : PLACE_IDS[place];
	}

	/**
	 * For the Locker's live previews: {@code name} drawn with {@code style}. Each preview on screen uses its own
	 * {@code slot} (0-95) so they can all show different styles at once; call it every frame (styles change live).
	 */
	public static Text preview(String name, NameStyle style, Style base, int slot) {
		PREVIEWS[slot] = style;
		MutableText out = Text.empty();
		appendName(out, name, base, PREVIEW + slot);
		return out;
	}
}
