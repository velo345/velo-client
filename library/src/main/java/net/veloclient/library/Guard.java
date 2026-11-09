package net.veloclient.library;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Everything that keeps the Library from hurting anyone - the Velo server first. The Library is its own process (own
 * JVM, CPU and memory caps in systemd, lower priority than velo-server), and on top of that:
 * <ul>
 * <li>a kill switch: {@code <data>/disabled} (whole API) or {@code <data>/disabled-<category>} turns it off within
 * seconds without a restart - every call answers 503;</li>
 * <li>per-caller limits per minute, by tier (anonymous IP / free key / Velo+ key) and category;</li>
 * <li>a global cap on how many expensive jobs run at once per category - over it, 503 instead of queueing;</li>
 * <li>load shedding: when the machine is busy, heavy work is refused first, then everything.</li>
 * </ul>
 */
final class Guard {

	enum Tier {
		ANON, KEY, PLUS
	}

	/** Who is calling: an IP, or a key (with its owner and tier). */
	record Identity(String id, Tier tier, String ownerUuid) {
	}

	/** Categories (halved 2026-11-09): limits per minute for ANON / KEY / PLUS, and how many may run at once (0 = unlimited). */
	enum Category {
		REQUEST(30, 300, 1500, 0),
		/** item icons: tiny, pre-rendered, mostly served from cache - pages show hundreds at once */
		ICON(300, 1500, 5000, 0),
		RENDER(15, 100, 500, 6),
		/** seed map tiles + structures: a map view loads a handful at a time, cached for a day */
		MAP(60, 300, 1000, 3),
		PING(5, 30, 150, 12),
		HEAVY(2, 10, 30, 2),
		WRITE(3, 15, 60, 4);

		final int[] perMinute;
		final int concurrent;

		Category(int anon, int key, int plus, int concurrent) {
			this.perMinute = new int[] {anon, key, plus};
			this.concurrent = concurrent;
		}
	}

	private final Path dataDir;
	private final Map<String, double[]> buckets = new ConcurrentHashMap<>();
	private final Map<Category, Semaphore> running = new ConcurrentHashMap<>();
	private volatile long checkedAt;
	private volatile boolean disabled;
	private volatile java.util.Set<String> disabledCategories = java.util.Set.of();
	private final int cores = Runtime.getRuntime().availableProcessors();

	Guard(Path dataDir) {
		this.dataDir = dataDir;
		for (Category c : Category.values()) {
			if (c.concurrent > 0) {
				running.put(c, new Semaphore(c.concurrent));
			}
		}
	}

	/** 503 when the API (or this category) is switched off or the machine is overloaded. */
	void checkOpen(Category category) {
		refreshSwitches();
		if (disabled || System.getenv("LIBRARY_DISABLED") != null) {
			throw new Http.Fail(503, "The Velo Library API is paused right now - try again later", 120);
		}
		if (disabledCategories.contains(category.name().toLowerCase(java.util.Locale.ROOT))) {
			throw new Http.Fail(503, "This part of the Library is paused right now - try again later", 120);
		}
		double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage() / Math.max(1, cores);
		if (load > 3.0 || (load > 1.6 && category != Category.REQUEST)) {
			throw new Http.Fail(503, "The Library is busy - try again in a minute", 60);
		}
	}

	private void refreshSwitches() {
		long now = System.currentTimeMillis();
		if (now - checkedAt < 3000) {
			return;
		}
		checkedAt = now;
		disabled = Files.exists(dataDir.resolve("disabled"));
		java.util.Set<String> off = new java.util.HashSet<>();
		for (Category c : Category.values()) {
			String n = c.name().toLowerCase(java.util.Locale.ROOT);
			if (Files.exists(dataDir.resolve("disabled-" + n))) {
				off.add(n);
			}
		}
		disabledCategories = off;
	}

	/** Counts one call for this identity; 429 with Retry-After when the minute's budget is used up. */
	void charge(Identity who, Category category) {
		int limit = category.perMinute[who.tier().ordinal()];
		double ratePerMs = limit / 60_000.0;
		double[] b = buckets.computeIfAbsent(who.id() + "|" + category, k -> new double[] {limit, System.currentTimeMillis()});
		synchronized (b) {
			long now = System.currentTimeMillis();
			b[0] = Math.min(limit, b[0] + (now - b[1]) * ratePerMs);
			b[1] = now;
			if (b[0] < 1) {
				long wait = (long) Math.ceil((1 - b[0]) / ratePerMs / 1000);
				throw new Http.Fail(429, "Rate limit reached (" + limit + " per minute for " + category.name().toLowerCase(java.util.Locale.ROOT)
						+ (who.tier() == Tier.ANON ? ") - a free key from velo-client.com/library/api raises it" : ")"), Math.max(1, wait));
			}
			b[0] -= 1;
		}
	}

	/** Remaining calls this minute (for the X-RateLimit headers). */
	int remaining(Identity who, Category category) {
		double[] b = buckets.get(who.id() + "|" + category);
		return b == null ? category.perMinute[who.tier().ordinal()] : (int) b[0];
	}

	interface Job<T> {
		T run() throws Exception;
	}

	/** Runs an expensive job only if a slot for its category is free within a moment - never builds a queue. */
	<T> T slot(Category category, Job<T> job) throws Exception {
		Semaphore s = running.get(category);
		if (s == null) {
			return job.run();
		}
		if (!s.tryAcquire(400, TimeUnit.MILLISECONDS)) {
			throw new Http.Fail(503, "The Library is busy - try again in a few seconds", 5);
		}
		try {
			return job.run();
		} finally {
			s.release();
		}
	}

	/** Old buckets out, so the map can't grow forever under a flood of addresses. */
	void sweep() {
		long now = System.currentTimeMillis();
		buckets.entrySet().removeIf(e -> now - e.getValue()[1] > 180_000);
	}
}
