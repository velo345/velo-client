package net.veloclient.velo.profile;

import java.util.List;
import java.util.Map;

/**
 * A saved snapshot of module enabled-states matched against a server address
 * (design spec section 5/8), e.g. "Survival SMP" auto-loading on
 * {@code example-smp.net} with server-testing modules on, vs. "Hypixel Safe
 * Mode" auto-loading on {@code hypixel.net} with only Always Safe modules.
 *
 * @param name              display name shown in the profile switcher
 * @param addressPatterns   glob-ish suffixes matched against the connected server address,
 *                          e.g. "example-smp.net" matches "play.example-smp.net"
 * @param safeMode          whether the Safe Mode master switch is forced on for this profile
 * @param moduleStates      module id -> enabled, overriding defaults for modules present in the map
 */
public record ServerProfile(String name, List<String> addressPatterns, boolean safeMode,
		Map<String, Boolean> moduleStates) {

	public boolean matches(String serverAddress) {
		if (serverAddress == null) {
			return false;
		}
		String host = serverAddress.toLowerCase();
		for (String pattern : addressPatterns) {
			if (host.equals(pattern.toLowerCase()) || host.endsWith("." + pattern.toLowerCase())) {
				return true;
			}
		}
		return false;
	}
}
