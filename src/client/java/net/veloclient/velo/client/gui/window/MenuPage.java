package net.veloclient.velo.client.gui.window;

/**
 * A page of the one-window Velo menu (dev build, {@link net.veloclient.velo.client.Preview#ON}): every page shares the
 * same frame and the icon rail on the left ({@link MenuShell}); switching pages only swaps what's inside.
 */
public interface MenuPage {

	/** The rail entry this page belongs to ("modules", "skins", "friends"...). */
	String railId();

	/** Pages built for a narrow window keep that width, centred in the frame, instead of stretching across it. */
	default int shellMaxContentWidth() {
		return Integer.MAX_VALUE;
	}
}
