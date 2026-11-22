package net.veloclient.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The /v1/online list as a versioned feed. Every game client polls it every 5 s; before, each poll rebuilt and sent the
 * whole list (~220 bytes per player online, every player, every poll). Now the list is rebuilt at most once a second
 * for all callers, every change bumps a version, and a client that sends {@code ?since=<version>} gets only the
 * players whose entry changed plus the uuids that went offline - usually nothing at all.
 *
 * Versions start at this process's start time, so a version from before a server restart is never mistaken for a
 * current one: anything unknown, too old (its removals were already forgotten) or from the future gets the full list.
 */
final class OnlineFeed {

	private static final long REBUILD_MILLIS = 1000;
	/** Removals are remembered this long; a client that was away longer gets the full list again. */
	private static final long TOMBSTONE_MILLIS = 10 * 60 * 1000;

	private final SessionRegistry registry;
	private final long base = System.currentTimeMillis();
	private long version = base;
	/** Deltas are only answered for versions at or after this one (older removals were forgotten). */
	private long oldest = base;
	private long builtAt;
	private Map<String, SessionRegistry.OnlineUser> current = Map.of();
	private final Map<String, Long> changedAt = new HashMap<>();
	private final LinkedHashMap<String, long[]> removedAt = new LinkedHashMap<>(); // uuid -> {version, time}

	OnlineFeed(SessionRegistry registry) {
		this.registry = registry;
	}

	record Full(List<SessionRegistry.OnlineUser> users, long version, long serverTimeMillis) {
	}

	record Delta(List<SessionRegistry.OnlineUser> users, List<String> removed, long version, boolean delta, long serverTimeMillis) {
	}

	/** The full list, or (for a usable {@code since}) just what changed after it. */
	synchronized Object answer(long since) {
		long now = System.currentTimeMillis();
		if (now - builtAt >= REBUILD_MILLIS) {
			rebuild(now);
		}
		if (since < oldest || since > version) {
			return new Full(List.copyOf(current.values()), version, now);
		}
		List<SessionRegistry.OnlineUser> changed = new ArrayList<>();
		if (since < version) {
			for (Map.Entry<String, Long> e : changedAt.entrySet()) {
				if (e.getValue() > since) {
					SessionRegistry.OnlineUser user = current.get(e.getKey());
					if (user != null) {
						changed.add(user);
					}
				}
			}
		}
		List<String> removed = new ArrayList<>();
		if (since < version) {
			for (Map.Entry<String, long[]> e : removedAt.entrySet()) {
				if (e.getValue()[0] > since) {
					removed.add(e.getKey());
				}
			}
		}
		return new Delta(changed, removed, version, true, now);
	}

	private void rebuild(long now) {
		builtAt = now;
		Map<String, SessionRegistry.OnlineUser> next = new LinkedHashMap<>();
		for (SessionRegistry.OnlineUser user : registry.onlineUsers()) {
			if (user.uuid() != null) {
				next.put(user.uuid(), user); // one entry per player, even with two game sessions
			}
		}
		long v = version + 1;
		boolean dirty = false;
		for (Map.Entry<String, SessionRegistry.OnlineUser> e : next.entrySet()) {
			if (!e.getValue().equals(current.get(e.getKey()))) {
				changedAt.put(e.getKey(), v);
				removedAt.remove(e.getKey());
				dirty = true;
			}
		}
		for (String uuid : current.keySet()) {
			if (!next.containsKey(uuid)) {
				changedAt.remove(uuid);
				removedAt.remove(uuid);
				removedAt.put(uuid, new long[] {v, now});
				dirty = true;
			}
		}
		if (dirty) {
			version = v;
		}
		current = next;
		// forget old removals; clients older than the newest forgotten one start over with the full list
		for (Iterator<long[]> it = removedAt.values().iterator(); it.hasNext(); ) {
			long[] r = it.next();
			if (now - r[1] < TOMBSTONE_MILLIS) {
				break; // insertion order = removal order
			}
			oldest = Math.max(oldest, r[0]);
			it.remove();
		}
	}
}
