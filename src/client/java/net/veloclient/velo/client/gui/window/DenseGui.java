package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.velo.client.util.ClientCompat;

/**
 * Velo windows render one or two GUI scales finer than the rest of the game when the GUI scale is
 * big (Auto on 1440p is 4: a Velo window would get only 640x360 units and look like chunky
 * buttons) - the launcher's density instead. Everything stays integer-crisp: the window is drawn
 * at an effective scale {@code e < guiScale} (fonts pick their {@code _e} atlas), the screen gets
 * the matching larger size in {@link VeloWindow#init()}, mouse positions are divided by
 * {@link #factor()} (DenseGuiMouseMixin) and the whole screen render is scaled by its inverse
 * (DenseGuiScreenMixin). The HUD under the window is untouched.
 */
public final class DenseGui {

	/** The smallest virtual size a Velo window space should still have. */
	private static final int MIN_WIDTH = 1000;
	private static final int MIN_HEIGHT = 560;
	/** Below this (at scale 2) a window would get cramped: then no dense mode at all. */
	private static final int FLOOR_WIDTH = 640;
	private static final int FLOOR_HEIGHT = 360;

	private DenseGui() {
	}

	/** The scale Velo windows render at: the largest one below the GUI scale that still leaves room, else the GUI scale. */
	public static int effectiveScale() {
		int scale = ClientCompat.guiScale();
		if (scale < 3) {
			return scale;
		}
		int pixelsW = MinecraftClient.getInstance().getWindow().getScaledWidth() * scale;
		int pixelsH = MinecraftClient.getInstance().getWindow().getScaledHeight() * scale;
		for (int e = scale - 1; e >= 2; e--) {
			if (pixelsW / e >= MIN_WIDTH && pixelsH / e >= MIN_HEIGHT) {
				return e;
			}
		}
		// Not even scale 2 reaches the launcher's room: use it anyway unless the window is tiny.
		return pixelsW / 2 >= FLOOR_WIDTH && pixelsH / 2 >= FLOOR_HEIGHT ? 2 : scale;
	}

	/** GUI units per window unit (> 1 when dense). */
	public static float factor() {
		return ClientCompat.guiScale() / (float) effectiveScale();
	}

	/** True while a Velo window is open and drawn denser than the GUI scale. */
	public static boolean active() {
		Screen screen = ClientCompat.currentScreen();
		return screen instanceof VeloWindow && effectiveScale() < ClientCompat.guiScale();
	}

	public static boolean appliesTo(Object screen) {
		return screen instanceof VeloWindow && effectiveScale() < ClientCompat.guiScale();
	}
}
