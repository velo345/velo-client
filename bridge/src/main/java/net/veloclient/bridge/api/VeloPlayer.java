package net.veloclient.bridge.api;

import java.util.Set;
import java.util.UUID;

/**
 * A player using Velo Client: the Velo version, the Minecraft version, and which Velo modules they have switched on
 * right now (e.g. "freelook", "zoom") - kept up to date while they play.
 */
public record VeloPlayer(UUID uuid, String name, String veloVersion, String minecraftVersion, Set<String> activeModules, long joinedAt) {

	public boolean hasModule(String moduleId) {
		return activeModules.contains(moduleId);
	}
}
