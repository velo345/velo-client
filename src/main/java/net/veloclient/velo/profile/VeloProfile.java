package net.veloclient.velo.profile;

import java.util.Map;

/**
 * A named, switchable bundle of every module's enabled state and settings
 * plus the HUD layout (design spec section 5/8) - independent from the
 * per-server {@link ServerProfile} auto-loading concept, this is the
 * manually create/choose/rename/delete profile switcher.
 */
public record VeloProfile(String name, Map<String, ModuleStateStore.ModuleSnapshot> moduleStates,
		Map<String, HudLayoutEntry> hudLayout) {
}
