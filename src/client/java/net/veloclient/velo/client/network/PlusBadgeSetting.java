package net.veloclient.velo.client.network;

import net.veloclient.velo.config.ConfigManager;

/**
 * Whether the + of Velo+ shows on your badge for other players. On by default; the launcher (Mods tab) and the game
 * (Velo Network settings) share it through {@code ~/.velo-client/config/plus-badge.json}.
 */
public final class PlusBadgeSetting {

	private record Data(boolean show) {
	}

	private PlusBadgeSetting() {
	}

	public static boolean show() {
		return ConfigManager.load("plus-badge", Data.class, new Data(true)).show();
	}

	public static void set(boolean show) {
		ConfigManager.save("plus-badge", new Data(show));
	}
}
