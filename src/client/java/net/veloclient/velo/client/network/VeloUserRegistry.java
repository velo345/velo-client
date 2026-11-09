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

	/**
	 * {@code nameStyle}: a Velo+ member's name style in its wire form (see {@code NameStyle}), or null; {@code wear}: their
	 * bodywear, "id[:colour],..." (see {@code WearManager}), or null.
	 */
	public record Entry(String username, String capeId, String wingsId, String auraId, boolean plus, String nameStyle, String wear, boolean og) {
		public Entry(String username, String capeId) {
			this(username, capeId, null, null, false, null, null, false);
		}
	}

	// Keyed by dashless-lowercase UUID (matches the server's own canonical
	// form - see VeloServerApp#normalizeUuid). Usernames are kept
	// case-preserved (as Mojang/the server returned them) in a parallel set,
	// since the tab-list/chat badge mixins only ever have the rendered name
	// text to match against, not a UUID (see VeloBadge#isKnownVeloName).
	private static final Map<String, Entry> BY_UUID = new ConcurrentHashMap<>();
	private static volatile Set<String> usernames = Set.of();
	// Lowercased username -> entry. Offline-mode/cracked servers hand every player a name-derived
	// UUID instead of their real one, so a UUID-only lookup never matched anyone there.
	private static volatile Map<String, Entry> byLowerName = Map.of();

	// Velo players who are on another client right now: their last in-game look from the server (/v1/cosmetics/of), so
	// their cloak, wings, aura and bodywear still show - like Lunar and Dawn do. No badge: that's only for who's online.
	private static final Map<String, Entry> OFFLINE = new ConcurrentHashMap<>();
	private static final Map<String, Long> ASKED_AT = new ConcurrentHashMap<>();
	private static final Set<String> WANTED = ConcurrentHashMap.newKeySet();
	private static final long ASK_AGAIN_MILLIS = 5 * 60_000L;

	private VeloUserRegistry() {
	}

	/** Someone else's last look while they aren't online with Velo; asks the server for it (batched) when not known yet. */
	private static Entry offline(UUID uuid) {
		if (uuid == null) {
			return null;
		}
		String key = dashless(uuid);
		Entry known = OFFLINE.get(key);
		if (known == null && !BY_UUID.containsKey(key)) {
			Long at = ASKED_AT.get(key);
			if (at == null || System.currentTimeMillis() - at > ASK_AGAIN_MILLIS) {
				WANTED.add(key);
			}
		}
		return known;
	}

	/** Up to {@code max} players whose look should be asked for now (network thread). */
	public static java.util.List<String> takeWanted(int max) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (var it = WANTED.iterator(); it.hasNext() && out.size() < max; ) {
			out.add(it.next());
			it.remove();
		}
		long now = System.currentTimeMillis();
		for (String key : out) {
			ASKED_AT.put(key, now);
		}
		return out;
	}

	/** The server's answer for {@code asked}: their looks (players without one are forgotten). */
	public static void putOffline(java.util.Collection<String> asked, Map<String, Entry> found) {
		for (String key : asked) {
			if (!found.containsKey(key)) {
				OFFLINE.remove(key);
			}
		}
		OFFLINE.putAll(found);
		if (ASKED_AT.size() > 5000) {
			ASKED_AT.clear();
			OFFLINE.clear();
		}
	}

	/** Wholesale-replaces the online set from a fresh {@code /v1/online} response. */
	public static void replaceAll(Map<String, Entry> entries) {
		BY_UUID.clear();
		BY_UUID.putAll(entries);
		usernames = entries.values().stream()
				.map(Entry::username)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		Map<String, Entry> lower = new java.util.HashMap<>();
		for (Entry entry : entries.values()) {
			lower.put(entry.username().toLowerCase(java.util.Locale.ROOT), entry);
		}
		byLowerName = Map.copyOf(lower);
		styledNames = entries.values().stream().filter(e -> e.nameStyle() != null).map(Entry::username).toList();
	}

	public static void clear() {
		BY_UUID.clear();
		usernames = Set.of();
		byLowerName = Map.of();
		styledNames = java.util.List.of();
	}

	/** Online players who have a name style (their names as the server has them). */
	private static volatile java.util.List<String> styledNames = java.util.List.of();

	public static java.util.List<String> styledNames() {
		return styledNames;
	}

	/** The name style (wire form) of the player called {@code name}, or null. */
	public static String nameStyleForName(String name) {
		Entry entry = entryForName(name);
		return entry == null ? null : entry.nameStyle();
	}

	public static boolean isOnline(UUID uuid) {
		return uuid != null && !BY_UUID.isEmpty() && BY_UUID.containsKey(dashless(uuid));
	}

	/** UUID match first, then the username (see {@link #byLowerName}). */
	public static boolean isOnline(UUID uuid, String username) {
		return isOnline(uuid) || entryForName(username) != null;
	}

	/** Whether {@code name} (any case) is an online Velo user - used by the tab list/chat badge. */
	public static boolean isOnlineName(String name) {
		return entryForName(name) != null;
	}

	private static Entry entryForName(String username) {
		Map<String, Entry> names = byLowerName;
		return username == null || names.isEmpty() ? null : names.get(username.toLowerCase(java.util.Locale.ROOT));
	}

	/** Usernames of every player currently online with Velo Client on the configured server - see {@link net.veloclient.velo.client.gui.VeloBadge#isKnownVeloName}. */
	public static Set<String> onlineUsernames() {
		return usernames;
	}

	/** The Store cape catalog id this player has equipped, if any and if they're online. */
	public static String capeIdFor(UUID uuid) {
		Entry entry = uuid == null || BY_UUID.isEmpty() ? null : BY_UUID.get(dashless(uuid));
		if (entry == null) {
			entry = offline(uuid);
		}
		return entry == null ? null : entry.capeId();
	}

	public static String capeIdFor(UUID uuid, String username) {
		if (net.veloclient.velo.client.cosmetics.CosmeticHide.hidden(uuid, "cape")) {
			return null;
		}
		String byUuid = capeIdFor(uuid);
		if (byUuid != null) {
			return byUuid;
		}
		Entry entry = entryForName(username);
		return entry == null ? null : entry.capeId();
	}

	/** The 3D wings this player has equipped (a bundled cosmetic id), if any and if they're online. */
	public static String wingsIdFor(UUID uuid, String username) {
		if (net.veloclient.velo.client.cosmetics.CosmeticHide.hidden(uuid, "cosmetics")) {
			return null;
		}
		Entry entry = uuid == null || BY_UUID.isEmpty() ? null : BY_UUID.get(dashless(uuid));
		if (entry == null) {
			entry = entryForName(username);
		}
		if (entry == null) {
			entry = offline(uuid);
		}
		return entry == null ? null : entry.wingsId();
	}

	/** Whether this player (UUID first, then name) is a Velo+ member - they get the badge with the plus. */
	public static boolean isPlus(UUID uuid, String username) {
		Entry entry = uuid == null || BY_UUID.isEmpty() ? null : BY_UUID.get(dashless(uuid));
		if (entry == null) {
			entry = entryForName(username);
		}
		return entry != null && entry.plus();
	}

	/** Whether this player (UUID first, then name) shows the OG badge. */
	public static boolean isOg(UUID uuid, String username) {
		Entry entry = uuid == null || BY_UUID.isEmpty() ? null : BY_UUID.get(dashless(uuid));
		if (entry == null) {
			entry = entryForName(username);
		}
		return entry != null && entry.og();
	}

	/** Whether the player called {@code name} shows the OG badge. */
	public static boolean isOgName(String name) {
		Entry entry = entryForName(name);
		return entry != null && entry.og();
	}

	/** Whether the player called {@code name} is a Velo+ member. */
	public static boolean isPlusName(String name) {
		Entry entry = entryForName(name);
		return entry != null && entry.plus();
	}

	/** The bodywear this player shows ("id[:colour],..."), if any and if they're online. */
	public static String wearFor(UUID uuid, String username) {
		if (net.veloclient.velo.client.cosmetics.CosmeticHide.hidden(uuid, "cosmetics")) {
			return null;
		}
		Entry entry = uuid == null || BY_UUID.isEmpty() ? null : BY_UUID.get(dashless(uuid));
		if (entry == null) {
			entry = entryForName(username);
		}
		if (entry == null) {
			entry = offline(uuid);
		}
		return entry == null ? null : entry.wear();
	}

	/** The aura this player has equipped ("id;settings"), if any and if they're online. */
	public static String auraIdFor(UUID uuid, String username) {
		if (net.veloclient.velo.client.cosmetics.CosmeticHide.hidden(uuid, "cosmetics")) {
			return null;
		}
		Entry entry = uuid == null || BY_UUID.isEmpty() ? null : BY_UUID.get(dashless(uuid));
		if (entry == null) {
			entry = entryForName(username);
		}
		if (entry == null) {
			entry = offline(uuid);
		}
		return entry == null ? null : entry.auraId();
	}

	// Called per rendered player per frame - formats the two longs directly instead of
	// toString() + replace(), which allocated two strings each time.
	private static String dashless(UUID uuid) {
		return java.util.HexFormat.of().toHexDigits(uuid.getMostSignificantBits())
				+ java.util.HexFormat.of().toHexDigits(uuid.getLeastSignificantBits());
	}
}
