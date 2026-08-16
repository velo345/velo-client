package net.veloclient.velo.client.schematics;

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

/** In-game counterpart to the launcher's identically-named class - see its javadoc for the full rationale (exact-normalized-title match only, cached per folder including negatives). */
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
			// Best-effort cache - a failed write just means re-matching next time.
		}
	}

	public static String normalize(String nameOrTitle) {
		String noExtension = nameOrTitle.contains(".") ? nameOrTitle.substring(0, nameOrTitle.lastIndexOf('.')) : nameOrTitle;
		String lower = noExtension.toLowerCase(java.util.Locale.ROOT);
		String spaced = SEPARATORS.matcher(lower).replaceAll(" ");
		String alnumOnly = NON_ALNUM.matcher(spaced).replaceAll("");
		return WHITESPACE.matcher(alnumOnly).replaceAll(" ").strip();
	}

	/** @throws IOException on a network failure - not swallowed, so callers don't cache a transient hiccup as a permanent negative. */
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
