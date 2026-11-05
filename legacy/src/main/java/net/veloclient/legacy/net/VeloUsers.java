package net.veloclient.legacy.net;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Who's online with Velo right now (from {@code /v1/online}) and what cloak they wear - read by every render hook. */
public final class VeloUsers {

	public record Entry(String username, String capeId, boolean plus) {
	}

	private static volatile Map<String, Entry> byUuid = Collections.emptyMap();
	/** Offline-mode servers hand out name-based UUIDs, so names are matched too. */
	private static volatile Map<String, Entry> byName = Collections.emptyMap();

	private VeloUsers() {
	}

	static void replaceAll(Map<String, Entry> entries) {
		Map<String, Entry> names = new HashMap<>();
		for (Entry entry : entries.values()) {
			names.put(entry.username().toLowerCase(Locale.ROOT), entry);
		}
		byUuid = Collections.unmodifiableMap(new HashMap<>(entries));
		byName = Collections.unmodifiableMap(names);
	}

	static void clear() {
		byUuid = Collections.emptyMap();
		byName = Collections.emptyMap();
	}

	public static Entry get(UUID uuid, String name) {
		Map<String, Entry> u = byUuid;
		if (u.isEmpty()) {
			return null;
		}
		Entry entry = uuid == null ? null : u.get(dashless(uuid));
		if (entry == null && name != null) {
			entry = byName.get(name.toLowerCase(Locale.ROOT));
		}
		return entry;
	}

	public static boolean isVelo(UUID uuid, String name) {
		return get(uuid, name) != null;
	}

	private static String dashless(UUID uuid) {
		return String.format("%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
	}
}
