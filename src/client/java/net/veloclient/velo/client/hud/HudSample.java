package net.veloclient.velo.client.hud;

/**
 * "Show sample content": HUD elements that are empty without live data (no target, no effects, no boss...) draw a
 * believable example instead - in the HUD editor, and since 2026-11 in a module's live preview too, so every preview
 * shows what the module does.
 */
public final class HudSample {

	private static int previewing;

	private HudSample() {
	}

	public static boolean active() {
		return previewing > 0 || net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
	}

	/** Around drawing (and measuring) an element for a preview. */
	public static void begin() {
		previewing++;
	}

	public static void end() {
		previewing = Math.max(0, previewing - 1);
	}
}
