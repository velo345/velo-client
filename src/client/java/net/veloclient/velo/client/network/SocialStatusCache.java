package net.veloclient.velo.client.network;

import net.veloclient.velo.config.ConfigManager;

/**
 * Local copy of "appear offline" in {@code ~/.velo-client/config/social.json} - the same file the
 * launcher writes, so both show the right status before they've reached the server. The server's
 * stored value is the real one (it's what friends see); this only mirrors it.
 */
public final class SocialStatusCache {

	private static final String CONFIG_ID = "social";

	private record Data(boolean invisible) {
	}

	private SocialStatusCache() {
	}

	public static boolean invisible() {
		return ConfigManager.load(CONFIG_ID, Data.class, new Data(false)).invisible();
	}

	static void remember(boolean invisible) {
		if (invisible() != invisible) {
			ConfigManager.save(CONFIG_ID, new Data(invisible));
		}
	}
}
