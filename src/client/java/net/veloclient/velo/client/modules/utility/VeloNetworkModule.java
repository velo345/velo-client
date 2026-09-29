package net.veloclient.velo.client.modules.utility;

import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Connects to whichever Velo Client server is configured in {@code
 * network.json} (see {@code VeloNetworkConfig}, server/README.md) - Velo
 * Client's own official server by default - so this client can see the
 * "which client is this player using" badge and equipped cape on *other*
 * online Velo Client users, not just itself - Store capes by catalog id, and
 * (with "Share Custom Cape" on) your own imported cape, uploaded once to that
 * server. On by default; still a genuine no-op if {@code network.json} is
 * hand-edited to blank out {@code serverUrl}.
 */
public final class VeloNetworkModule extends AbstractModule implements Configurable {

	public VeloNetworkModule() {
		super("velo-network", "Velo Network",
				"See other online Velo Client users' badge and cape, and share yours (including your own custom cape) with them.",
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

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.ToggleField("Share Custom Cape",
				() -> VeloServerClient.shareCustomCapes, v -> VeloServerClient.shareCustomCapes = v));
	}
}
