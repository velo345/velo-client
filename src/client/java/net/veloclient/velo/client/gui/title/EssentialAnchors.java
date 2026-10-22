package net.veloclient.velo.client.gui.title;

/**
 * Where to park vanilla's invisible menu buttons so Essential's character and side menu land in the free space
 * right of Velo's menu, on every screen size.
 *
 * <p>Essential 1.4 (PauseMenuDisplay / LeftSideBar, decompiled) lays out from the live bounds of the "top group"
 * (singleplayer + multiplayer on the title screen, Back to Game in the pause menu):
 * <ul>
 * <li>character + Wardrobe (120 wide): {@code x = min(top.left / 2 - 60, top.left - 137)} - centred between the screen's
 * left edge and the top group, which is why an anchor in the middle of the free space put it on top of our menu;</li>
 * <li>side menu (104 wide, or icons only when that doesn't fit): {@code x = max((top.right + W) / 2 - 64, top.right + 28)},
 * pinned 5 px from the right edge.</li>
 * </ul>
 * So the anchor goes to {@code 2 * playerLeft + 120} - often past the right edge, which is fine: it's invisible, and
 * the side menu then sits as icons at the right edge. Essential also forces a GUI scale with at least 404 px width on
 * these two screens, so that's the smallest case.
 *
 * <p>The character and its Wardrobe button live in a container covering only the LEFT HALF of the screen
 * (PauseMenuDisplay: width 50%), and Elementa only hovers/clicks inside a parent's bounds - placed past the middle
 * they still draw but are dead. So the character must end left of {@code width / 2}.
 */
public final class EssentialAnchors {

	static final int PLAYER_W = 120;
	static final int FULL_MENU_W = 104;
	static final int COMPACT_MENU_W = 24;
	static final int GAP = 10;
	static final int ANCHOR_W = 2;
	/** Far enough left that Essential's character is drawn off-screen. */
	static final int OFF_LEFT = -10000;

	/**
	 * @param topX       x of the anchor whose left edge Essential's character follows
	 * @param menuX      x of the anchor whose right edge Essential's side menu follows (title only: multiplayer; the
	 *                   pause menu has a single top button, so this equals {@code topX})
	 * @param playerShown whether the character is on-screen
	 */
	public record Plan(int topX, int menuX, boolean playerShown) {
	}

	private EssentialAnchors() {
	}

	/**
	 * @param freeLeft first free x right of Velo's own menu
	 * @param canSplit true when there are two top anchors (title screen) - lets the character be moved off-screen
	 *                 while the side menu stays put, for screens too narrow for both
	 * @return the plan, or null if neither the character nor (when it can't be split) the menu fit
	 */
	public static Plan plan(int width, int freeLeft, boolean canSplit) {
		int playerLeft = freeLeft + GAP;
		int top = 2 * playerLeft + PLAYER_W;
		if (playerLeft + PLAYER_W + GAP <= menuLeft(width, top + ANCHOR_W) && playerLeft + PLAYER_W <= width / 2) {
			return new Plan(top, top, true);
		}
		if (canSplit) {
			// Character off-screen; side menu right of our menu (full width if there's room for it).
			int menuAnchor = Math.max(0, freeLeft + GAP - 28 - ANCHOR_W);
			return new Plan(OFF_LEFT, menuAnchor, false);
		}
		return null;
	}

	/** Right edge of the character for a plan made with this {@code freeLeft}. */
	public static int playerRight(int freeLeft) {
		return freeLeft + GAP + PLAYER_W;
	}

	/** Essential's side-menu x for a top group ending at {@code topRight} (PauseMenuDisplay.getRightSideMenuX + compact check). */
	public static int menuLeft(int width, int topRight) {
		int full = Math.max((topRight + width) / 2 - 64, topRight + 28);
		boolean compact = full + FULL_MENU_W + 5 >= width;
		if (!compact) {
			return Math.min(full, width - 5 - FULL_MENU_W);
		}
		return Math.min(Math.max(width - 13 - COMPACT_MENU_W, topRight + 24), width - 5 - COMPACT_MENU_W);
	}

	/** Essential's character x for a top group starting at {@code topLeft} (LeftSideBar). */
	static int playerLeft(int topLeft) {
		return Math.min(topLeft / 2 - PLAYER_W / 2, topLeft - 17 - PLAYER_W);
	}
}
