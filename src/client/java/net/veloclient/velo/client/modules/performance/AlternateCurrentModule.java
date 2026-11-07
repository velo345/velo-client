package net.veloclient.velo.client.modules.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Alternate Current: asks the Velo launcher to add the mod of that name (MIT, by Space Walker), a faster redstone dust
 * implementation. It runs in the world's server, so it only works in your own singleplayer or hosted worlds and does
 * nothing on multiplayer servers. Off by default: it changes the order dust updates its neighbours in, which machines
 * that rely on vanilla's order can notice. Same launcher hand-over as {@link C2meModule}.
 */
public final class AlternateCurrentModule extends AbstractModule {

	public static final String KEY = "alternate-current";

	public AlternateCurrentModule() {
		super("alternate-current", "Alternate Current (Singleplayer only)",
				"Singleplayer only: faster redstone dust in your own worlds, no effect on servers. Machines that rely on vanilla's "
						+ "dust update order can behave differently. Applies on your next start from the Velo launcher.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		C2meModule.writeFlag(KEY, true);
	}

	@Override
	public void onDisable() {
		C2meModule.writeFlag(KEY, false);
	}

	/** Whether Alternate Current is actually running in this session. */
	public static boolean loaded() {
		return FabricLoader.getInstance().isModLoaded("alternate-current");
	}
}
