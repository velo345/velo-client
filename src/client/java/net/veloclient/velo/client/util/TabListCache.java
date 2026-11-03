package net.veloclient.velo.client.util;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes holding Tab cheap on big servers. Vanilla rebuilds the whole player list every frame - sorts every player,
 * builds every row's name (team prefix, colours, rank) and measures every name's width again - and Velo adds Name
 * Styles and the badge check per row on top. None of that changes between two frames, so it's kept here:
 * <ul>
 * <li>the sorted list for {@link #LIST_MS} (someone joining shows up a quarter of a second later at most);</li>
 * <li>each player's finished row name until the server changes their display name, or {@link #NAME_MS} at most (team
 * prefixes have no "changed" signal); Name Style animations keep running - they're drawn, not baked in;</li>
 * <li>the width of each of those names, and the badge decision for it (same text object = same answer).</li>
 * </ul>
 * Plain {@code Object}s, so the same code serves every Minecraft version.
 */
public final class TabListCache {

	private static final long LIST_MS = 250;
	private static final long NAME_MS = 500;

	private static List<?> entries;
	private static long entriesAt;

	private record Name(Object displayName, Object text, long at) {
	}

	private static final Map<Object, Name> NAMES = new WeakHashMap<>();
	private static final Map<Object, Integer> WIDTHS = new IdentityHashMap<>();
	private static final Map<Object, Object> ROWS = new IdentityHashMap<>();
	private static long tidiedAt;

	private TabListCache() {
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	/** The sorted list from a moment ago, or null to let vanilla build it. */
	public static List<?> entries() {
		return entries != null && now() - entriesAt < LIST_MS ? entries : null;
	}

	public static void storeEntries(List<?> list) {
		entries = list;
		entriesAt = now();
	}

	/** The row name built earlier for {@code entry}, if it's still valid for this display name. */
	public static Object name(Object entry, Object displayName) {
		Name cached = NAMES.get(entry);
		if (cached == null || cached.displayName() != displayName || now() - cached.at() > NAME_MS) {
			return null;
		}
		return cached.text();
	}

	public static void storeName(Object entry, Object displayName, Object text) {
		NAMES.put(entry, new Name(displayName, text, now()));
	}

	/** A width measured before for this very text object, or -1. */
	public static int width(Object text) {
		tidy();
		Integer w = WIDTHS.get(text);
		return w == null ? -1 : w;
	}

	public static void storeWidth(Object text, int width) {
		WIDTHS.put(text, width);
	}

	/** Anything remembered for this row text (the badge decision), or null. */
	public static Object row(Object text) {
		tidy();
		return ROWS.get(text);
	}

	public static void storeRow(Object text, Object value) {
		ROWS.put(text, value);
	}

	/** Identity maps only stay small: the row names they key on are replaced every {@link #NAME_MS} anyway. */
	private static void tidy() {
		long now = now();
		if (now - tidiedAt > 2000 || WIDTHS.size() > 1024 || ROWS.size() > 1024) {
			tidiedAt = now;
			WIDTHS.clear();
			ROWS.clear();
		}
	}
}
