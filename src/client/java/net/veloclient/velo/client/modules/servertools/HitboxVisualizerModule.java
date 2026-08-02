package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Renders entity hitbox outlines - informational only, for plugin/build hitbox debugging, not a
 * combat aid (it draws the exact box the server already uses, nothing wider). Off by default and
 * tagged Check Server Rules since some servers disallow any hitbox rendering (design spec 6.3).
 */
public final class HitboxVisualizerModule extends VanillaDebugToggleModule {

	public HitboxVisualizerModule() {
		super("hitbox-visualizer", "Hitbox Visualizer",
				"Renders entity hitbox outlines for build/plugin testing.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, DebugHudEntries.ENTITY_HITBOXES);
	}
}
