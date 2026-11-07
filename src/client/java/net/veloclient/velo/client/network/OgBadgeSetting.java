package net.veloclient.velo.client.network;

import net.veloclient.velo.config.ConfigManager;

/**
 * The OG badge (players from Velo's first two weeks - the server decides who): whether you show it instead of the
 * normal badge (off by default, it's opt-in), whether the server said you may, and whether the launcher already said
 * thank you. Shared with the launcher through {@code ~/.velo-client/config/og-badge.json}.
 */
public final class OgBadgeSetting {

	private record Data(boolean use, boolean eligible, boolean thanked) {
	}

	private OgBadgeSetting() {
	}

	/** Read every frame by the badge: kept for 2 s (the launcher may change the file meanwhile). */
	private static volatile Data cached;
	private static volatile long cachedAt;

	private static Data data() {
		long now = System.currentTimeMillis();
		Data d = cached;
		if (d == null || now - cachedAt > 2000) {
			d = ConfigManager.load("og-badge", Data.class, new Data(false, false, false));
			cached = d;
			cachedAt = now;
		}
		return d;
	}

	private static void save(Data d) {
		ConfigManager.save("og-badge", d);
		cached = d;
		cachedAt = System.currentTimeMillis();
	}

	/** Whether you chose the OG badge (only shown when {@link #eligible()}). */
	public static boolean use() {
		return data().use();
	}

	public static void setUse(boolean use) {
		Data d = data();
		save(new Data(use, d.eligible(), d.thanked()));
	}

	/** What the server said last time: this account may show the OG badge. */
	public static boolean eligible() {
		return data().eligible();
	}

	static void setEligible(boolean eligible) {
		Data d = data();
		if (d.eligible() != eligible) {
			save(new Data(d.use(), eligible, d.thanked()));
		}
	}

	/** Your own badge right now: OG when eligible and chosen. */
	public static boolean showing() {
		Data d = data();
		return d.eligible() && d.use();
	}
}
