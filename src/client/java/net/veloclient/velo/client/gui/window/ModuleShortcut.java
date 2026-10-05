package net.veloclient.velo.client.gui.window;

import net.minecraft.client.gui.screen.Screen;

/** A module that belongs to a bigger feature screen (Capes, Waypoints...) gets a big shortcut button in its settings window. */
public interface ModuleShortcut {

	String shortcutLabel();

	/** Opens the feature screen; {@code parent} is the settings window to come back to. */
	void openShortcut(Screen parent);
}
