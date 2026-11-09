package net.veloclient.library;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finished answers by key (normally the request URL), bounded by total bytes. A cached answer costs the caller nothing
 * (no rate limit) and the machine almost nothing - most traffic on image APIs is the same URL again.
 */
final class Cache {

	record Entry(int status, String type, byte[] body, long expires, int maxAge) {
	}

	private final long maxBytes;
	private long bytes;
	private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(256, 0.75f, true);

	Cache(long maxBytes) {
		this.maxBytes = maxBytes;
	}

	synchronized Entry get(String key) {
		Entry e = map.get(key);
		if (e == null) {
			return null;
		}
		if (e.expires() < System.currentTimeMillis()) {
			remove(key);
			return null;
		}
		return e;
	}

	synchronized void put(String key, int status, String type, byte[] body, int seconds) {
		if (body.length > maxBytes / 16 || seconds <= 0) {
			return;
		}
		remove(key);
		map.put(key, new Entry(status, type, body, System.currentTimeMillis() + seconds * 1000L, seconds));
		bytes += body.length;
		var it = map.entrySet().iterator();
		while (bytes > maxBytes && it.hasNext()) {
			Map.Entry<String, Entry> oldest = it.next();
			bytes -= oldest.getValue().body().length;
			it.remove();
		}
	}

	synchronized void remove(String key) {
		Entry old = map.remove(key);
		if (old != null) {
			bytes -= old.body().length;
		}
	}

	synchronized void removePrefix(String prefix) {
		var it = map.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (e.getKey().startsWith(prefix)) {
				bytes -= e.getValue().body().length;
				it.remove();
			}
		}
	}

	/** A small, process-wide memo for computed objects (profiles, parsed data) with a time limit. */
	static final class Memo<V> {
		private record Item<V>(V value, long expires) {
		}

		private final int max;
		private final LinkedHashMap<String, Item<V>> map;

		Memo(int max) {
			this.max = max;
			this.map = new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Item<V>> eldest) {
					return size() > Memo.this.max;
				}
			};
		}

		synchronized V get(String key) {
			Item<V> i = map.get(key);
			if (i == null || i.expires() < System.currentTimeMillis()) {
				return null;
			}
			return i.value();
		}

		synchronized void put(String key, V value, long millis) {
			map.put(key, new Item<>(value, System.currentTimeMillis() + millis));
		}
	}
}
