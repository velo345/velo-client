package net.veloclient.velo.client.network;

import net.veloclient.velo.config.ConfigManager;

/**
 * Which Velo Client server (see {@code /server}) this client talks to for
 * cross-player badge/cape sync. Defaults to Velo Client's own official
 * server ({@link #DEFAULT_SERVER_URL}) so this works out of the box with no
 * setup step - overridable by hand-editing {@code network.json} (design
 * spec section 8's convention, same as {@code cosmetics-cape.json} etc.) for
 * anyone who wants to point at a different/self-hosted one instead (see
 * server/README.md).
 */
public record VeloNetworkConfig(String serverUrl) {

	private static final String MODULE_ID = "network";
	public static final String DEFAULT_SERVER_URL = "https://api.velo-client.com";
	/** Where the official server used to live - a {@code network.json} still naming it follows the move. */
	private static final java.util.Set<String> LEGACY_SERVER_URLS = java.util.Set.of("https://client.asteriasmp.net", "http://client.asteriasmp.net");

	public static VeloNetworkConfig load() {
		VeloNetworkConfig config = ConfigManager.load(MODULE_ID, VeloNetworkConfig.class, new VeloNetworkConfig(DEFAULT_SERVER_URL));
		if (config.isConfigured() && LEGACY_SERVER_URLS.contains(config.normalizedUrl())) {
			return new VeloNetworkConfig(DEFAULT_SERVER_URL);
		}
		return config;
	}

	/** Null/blank (e.g. a hand-edited {@code network.json} with an empty {@code serverUrl}) opts out entirely - the network module simply stays idle. */
	public boolean isConfigured() {
		return serverUrl != null && !serverUrl.isBlank();
	}

	public String normalizedUrl() {
		String trimmed = serverUrl.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}
}
