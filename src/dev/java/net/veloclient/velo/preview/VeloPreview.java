package net.veloclient.velo.preview;

import net.veloclient.velo.client.addon.VeloAddon;
import net.veloclient.velo.module.ModuleRegistry;

/**
 * Dev build only: registers the features that aren't released yet (VeloClientMod.loadAddons loads this class by name;
 * release jars don't contain it). Hooks inside existing code check {@link net.veloclient.velo.client.Preview#ON} instead.
 */
public final class VeloPreview implements VeloAddon {

	@Override
	public void onVeloInitialize() {
		ModuleRegistry.register(new net.veloclient.velo.preview.tweaks.RideableOpacityModule());
		net.veloclient.velo.preview.guide.GuideTrigger.register();
	}
}
