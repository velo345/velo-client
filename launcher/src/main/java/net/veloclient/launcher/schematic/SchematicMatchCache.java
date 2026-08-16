package net.veloclient.launcher.schematic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-folder cache of {@link #identify}'s best-effort match between a local
 * schematic file and a PhantomMarket listing, keyed by filename - so a file
 * someone dropped in manually (with only a filename to go on, no hash to
 * look up like the Mods tab's auto-identify does) can still show a real
 * thumbnail/title/link once matched, without re-searching every time the
 * tab reopens. A "no match found" result is cached too (as {@code found:
 * false}), so a file that genuinely isn't on PhantomMarket doesn't get
 * re-queried on every refresh either.
 */
public final class SchematicMatchCache {

	private static final Gson GSON = new GsonBuilder().create();
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
	private static final Pattern SEPARATORS = Pattern.compile("[_\\-.]+");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private SchematicMatchCache() {
	}

	private static Path file(Path folder) {
		return folder.resolve(".velo-schematic-matches.json");
	}

	public static Map<String, SchematicMatch> load(Path folder) {
		Path file = file(folder);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, SchematicMatch> map = GSON.fromJson(reader, new TypeToken<Map<String, SchematicMatch>>() {}.getType());
			return map != null ? map : new HashMap<>();
		} catch (IOException | JsonParseException e) {
			return new HashMap<>();
		}
	}

	public static void save(Path folder, Map<String, SchematicMatch> matches) {
		try {
			Files.createDirectories(folder);
			try (Writer writer = Files.newBufferedWriter(file(folder), StandardCharsets.UTF_8)) {
				GSON.toJson(matches, writer);
			}
		} catch (IOException e) {
			// Best-effort cache - a failed write just means re-matching next time, not a hard failure.
		}
	}

	/** Lowercases, strips the extension, folds {@code _-.} into spaces, drops anything else non-alphanumeric, and collapses whitespace - so "Kelpinator_V6.litematic" and a listing titled "Kelpinator V6" compare equal. */
	public static String normalize(String nameOrTitle) {
		String noExtension = nameOrTitle.contains(".") ? nameOrTitle.substring(0, nameOrTitle.lastIndexOf('.')) : nameOrTitle;
		String lower = noExtension.toLowerCase(java.util.Locale.ROOT);
		String spaced = SEPARATORS.matcher(lower).replaceAll(" ");
		String alnumOnly = NON_ALNUM.matcher(spaced).replaceAll("");
		return WHITESPACE.matcher(alnumOnly).replaceAll(" ").strip();
	}

	/**
	 * Searches PhantomMarket for {@code fileName}'s normalized title and
	 * accepts only an exact normalized match (not "contains") - a loose
	 * match would risk showing the wrong image/title/link on someone's
	 * actual file, which is worse than just showing the filename plainly.
	 *
	 * @throws IOException on a network failure - deliberately not swallowed
	 *                      into a "no match" result, so callers don't cache a
	 *                      transient network hiccup as a permanent negative
	 *                      (see {@link #save}) - only a real, completed search
	 *                      that came back without a match should count as one.
	 */
	public static SchematicMatch identify(String fileName) throws IOException {
		String normalizedTarget = normalize(fileName);
		if (normalizedTarget.isBlank()) {
			return SchematicMatch.none();
		}
		for (PhantomMarketClient.SearchHit hit : PhantomMarketClient.search(normalizedTarget)) {
			if (normalize(hit.title()).equals(normalizedTarget)) {
				return new SchematicMatch(hit.title(), hit.thumbnailUrl(), PhantomMarketClient.postUrl(hit.slug()), true);
			}
		}
		return SchematicMatch.none();
	}
}
