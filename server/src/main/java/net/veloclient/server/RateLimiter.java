package net.veloclient.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limits per (player, action) - keeps one player from flooding others with friend
 * requests, messages or waypoint shares, or hammering the server with status/presence updates.
 * Each action can have several windows (e.g. a short burst limit and a longer hourly cap); a call
 * only counts if every window still has room. In memory only: a restart forgives everyone, which
 * is fine for abuse limits.
 */
final class RateLimiter {

	/** At most {@code max} actions per {@code windowMillis}. */
	record Window(int max, long windowMillis) {
	}

	private final Map<String, Deque<Long>> history = new ConcurrentHashMap<>();

	/**
	 * Records one {@code action} for {@code uuid} and returns 0, or - when any window is full -
	 * records nothing and returns how many milliseconds until it would be allowed.
	 */
	long tryAcquire(String uuid, String action, Window... windows) {
		long longest = 0;
		for (Window window : windows) {
			longest = Math.max(longest, window.windowMillis());
		}
		Deque<Long> times = history.computeIfAbsent(uuid + "/" + action, k -> new ArrayDeque<>());
		synchronized (times) {
			long now = System.currentTimeMillis();
			while (!times.isEmpty() && times.peekFirst() <= now - longest) {
				times.removeFirst();
			}
			long wait = 0;
			for (Window window : windows) {
				int inWindow = 0;
				Long oldestInWindow = null;
				for (Long time : times) {
					if (time > now - window.windowMillis()) {
						inWindow++;
						if (oldestInWindow == null) {
							oldestInWindow = time;
						}
					}
				}
				if (inWindow >= window.max() && oldestInWindow != null) {
					wait = Math.max(wait, oldestInWindow + window.windowMillis() - now);
				}
			}
			if (wait > 0) {
				return wait;
			}
			times.addLast(now);
			return 0;
		}
	}

	/** Drops empty histories so idle players don't keep entries forever. */
	void sweep(long olderThanMillis) {
		long cutoff = System.currentTimeMillis() - olderThanMillis;
		history.entrySet().removeIf(e -> {
			synchronized (e.getValue()) {
				return e.getValue().isEmpty() || e.getValue().peekLast() < cutoff;
			}
		});
	}

	static String describeWait(long millis) {
		long seconds = Math.max(1, (millis + 999) / 1000);
		if (seconds < 60) {
			return seconds + "s";
		}
		return (seconds + 59) / 60 + " min";
	}
}
