package net.veloclient.velo.client.network;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client-local cache of "who else is currently online with Velo Client,
 * and what cape are they wearing" - refreshed periodically by {@link
 * VeloServerClient#poll()} from the server's {@code /v1/online}. Every
 * badge/cape render hook reads this instead of talking to the network
 * directly, so a slow/failed poll just means slightly stale data, never a
 * blocked frame.
 */
public final class VeloUserRegistry {

	public record Entry(String username, String capeId) {
	}

	// Keyed by dashless-lowercase UUID (matches the server's own canonical
	// form - see VeloServerApp#normalizeUuid). Usernames are kept
	// case-preserved (as Mojang/the server returned them) in a parallel set,
	// since the tab-list/chat badge mixins only ever have the rendered name
	// text to match against, not a UUID (see VeloBadge#isKnownVeloName).
	private static final Map<String, Entry> BY_UUID = new ConcurrentHashMap<>();
	private static volatile Set<String> usernames = Set.of();

	private VeloUserRegistry() {
	}

	/** Wholesale-replaces the online set from a fresh {@code /v1/online} response. */
	public static void replaceAll(Map<String, Entry> entries) {
		BY_UUID.clear();
		BY_UUID.putAll(entries);
		usernames = entries.values().stream()
				.map(Entry::username)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	public static void clear() {
		BY_UUID.clear();
		usernames = Set.of();
	}

	public static boolean isOnline(UUID uuid) {
		return uuid != null && BY_UUID.containsKey(dashless(uuid));
	}

	/** Usernames of every player currently online with Velo Client on the configured server - see {@link net.veloclient.velo.client.gui.VeloBadge#isKnownVeloName}. */
	public static Set<String> onlineUsernames() {
		return usernames;
	}

	/** The Store cape catalog id this player has equipped, if any and if they're online. */
	public static String capeIdFor(UUID uuid) {
		Entry entry = uuid == null ? null : BY_UUID.get(dashless(uuid));
		return entry == null ? null : entry.capeId();
	}

	private static String dashless(UUID uuid) {
		return uuid.toString().replace("-", "");
	}
}
