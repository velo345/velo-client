package net.veloclient.velo.client.modules.utility;

import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Connects to whichever Velo Client server is configured in {@code
 * network.json} (see {@code VeloNetworkConfig}, server/README.md) - Velo
 * Client's own official server by default - so this client can see the
 * "which client is this player using" badge and equipped Store cape on
 * *other* online Velo Client users, not just itself. On by default; still a
 * genuine no-op if {@code network.json} is hand-edited to blank out {@code
 * serverUrl}.
 */
public final class VeloNetworkModule extends AbstractModule {

	public VeloNetworkModule() {
		super("velo-network", "Velo Network",
				"See other online Velo Client users' badge and equipped cape - talks to the server configured in network.json (see server/README.md).",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
	}

	@Override
	public void onEnable() {
		VeloServerClient.start();
	}

	@Override
	public void onDisable() {
		VeloServerClient.stop();
	}
}
