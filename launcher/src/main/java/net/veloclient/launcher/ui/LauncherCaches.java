package net.veloclient.launcher.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Size-bounded image/data caches for the launcher. They used to be plain maps that only ever
 * grew - browsing mods, the store and news kept every icon, banner and decoded cape animation
 * alive for the whole session. Each cache is now least-recently-used with a cap, and
 * {@link #trimAll()} empties them all when the launcher goes idle (minimized / game running).
 */
public final class LauncherCaches {

	private static final List<Map<?, ?>> ALL = Collections.synchronizedList(new ArrayList<>());

	private LauncherCaches() {
	}

	/** A thread-safe LRU map holding at most {@code max} entries. */
	public static <K, V> Map<K, V> lru(int max) {
		Map<K, V> map = Collections.synchronizedMap(new LinkedHashMap<>(Math.min(max, 64), 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > max;
			}
		});
		ALL.add(map);
		return map;
	}

	/** Drops every cached image/blob (they reload on demand). */
	public static void trimAll() {
		synchronized (ALL) {
			for (Map<?, ?> map : ALL) {
				map.clear();
			}
		}
	}
}
