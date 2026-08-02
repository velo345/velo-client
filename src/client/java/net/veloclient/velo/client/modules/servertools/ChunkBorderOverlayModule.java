package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Renders chunk section borders for build/world-gen testing (design spec section 6.3). */
public final class ChunkBorderOverlayModule extends VanillaDebugToggleModule {

	public ChunkBorderOverlayModule() {
		super("chunk-border-overlay", "Chunk Boundary Overlay",
				"Renders chunk and chunk-section borders around you, for build/world-gen testing.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, DebugHudEntries.CHUNK_BORDERS);
	}
}
