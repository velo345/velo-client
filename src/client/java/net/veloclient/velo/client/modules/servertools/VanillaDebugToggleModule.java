package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility;
import net.minecraft.util.Identifier;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Base for modules that are thin on/off switches over one of vanilla's own
 * F3 debug overlays ({@code MinecraftClient#debugHudEntryList}), so we reuse
 * Mojang's own rendering (chunk borders, hitboxes, collision boxes) instead
 * of reimplementing world-space line/box rendering ourselves - identical
 * behavior to pressing F3 and toggling that entry, just independent of
 * whether the F3 panel itself is open.
 */
public abstract class VanillaDebugToggleModule extends AbstractModule {

	private final Identifier entryId;

	protected VanillaDebugToggleModule(String id, String displayName, String description,
			ModuleCategory category, SafetyTag safetyTag, Identifier entryId) {
		super(id, displayName, description, category, safetyTag, false);
		this.entryId = entryId;
	}

	@Override
	public void onEnable() {
		MinecraftClient.getInstance().debugHudEntryList.setEntryVisibility(entryId, DebugHudEntryVisibility.ALWAYS_ON);
	}

	@Override
	public void onDisable() {
		MinecraftClient.getInstance().debugHudEntryList.setEntryVisibility(entryId, DebugHudEntryVisibility.NEVER);
	}
}
