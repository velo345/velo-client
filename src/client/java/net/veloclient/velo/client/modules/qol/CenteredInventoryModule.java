package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Centered Inventory: opening the recipe book no longer pushes your inventory to the right - the inventory stays in
 * the middle and the book opens next to it on the left (when the window is wide enough for both). RecipeBookCenterMixin.
 */
public final class CenteredInventoryModule extends AbstractModule {

	private static CenteredInventoryModule instance;

	public CenteredInventoryModule() {
		super("centered-inventory", "Centered Inventory", "Keeps your inventory in the middle of the screen when the recipe book opens.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	public static boolean on() {
		return instance != null && instance.isEnabled();
	}

	/** The book's x offset (vanilla: 86) that puts it just left of a centered container, or -1 if there's no room. */
	public static int bookOffset(int screenWidth, int containerWidth) {
		int containerX = (screenWidth - containerWidth) / 2;
		int bookX = containerX - 147 - 2;
		// The book's tabs hang ~30 px out to its left: without room for them, vanilla's layout (container pushed right) it is.
		if (bookX < 34) {
			return -1;
		}
		return (screenWidth - 147) / 2 - bookX;
	}
}
