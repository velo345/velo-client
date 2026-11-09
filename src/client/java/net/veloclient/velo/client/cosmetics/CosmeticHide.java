package net.veloclient.velo.client.cosmetics;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Hides parts of a player's look (Velo Rewind's per-player options in a replay): "cape", "cosmetics" (wings, bodywear,
 * aura), "pets", "nametag", "glow". The cosmetic lookups ask {@link #hidden}; nothing is hidden unless something set
 * {@link #test}.
 */
public final class CosmeticHide {

	/** (dashed lower-case uuid, part) -> hidden. */
	public static volatile BiPredicate<String, String> test = (uuid, part) -> false;

	private CosmeticHide() {
	}

	public static boolean hidden(UUID uuid, String part) {
		return uuid != null && test.test(uuid.toString(), part);
	}

	public static boolean hidden(String uuid, String part) {
		if (uuid == null) {
			return false;
		}
		String u = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
		if (u.length() == 32) {
			u = u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20);
		}
		return test.test(u, part);
	}
}
