package net.veloclient.server;

/**
 * The OG badge: a thank-you for everyone who got Velo in its first two weeks (owner, 2026-11-07). Eligible = the
 * account's first sign-in ({@link StatsService#firstSeen}) is before the cutoff - by default the end of 14 November
 * 2026 in Berlin, two weeks after the public 1.0 launch on 1 November. {@code VELO_OG_CUTOFF} (epoch millis) moves it.
 * Opt-in: the badge only changes for others once the player switches to OG (game heartbeat, see SessionRegistry).
 */
final class OgBadge {

	/** 2026-11-15T00:00+01:00. */
	static final long DEFAULT_CUTOFF = 1_794_697_200_000L;

	private final StatsService stats;
	private final long cutoff;

	OgBadge(StatsService stats, long cutoff) {
		this.stats = stats;
		this.cutoff = cutoff;
	}

	boolean eligible(String uuid) {
		if (uuid == null || stats == null) {
			return false;
		}
		long first = stats.firstSeen(uuid);
		return first > 0 && first < cutoff;
	}

	long cutoff() {
		return cutoff;
	}
}
