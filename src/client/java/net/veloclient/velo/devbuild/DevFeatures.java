package net.veloclient.velo.devbuild;

import net.veloclient.velo.client.addon.VeloAddon;
import net.veloclient.velo.module.ModuleRegistry;

/** Modules promoted from the dev build (loaded by name from VeloPreview). */
public final class DevFeatures implements VeloAddon {

	@Override
	public void onVeloInitialize() {
		ModuleRegistry.register(new ShieldIndicatorModule());
		ModuleRegistry.register(new net.veloclient.velo.devbuild.slots.NumberedInventoryModule());
		ModuleRegistry.register(new net.veloclient.velo.devbuild.colors.ColorCodesModule());
		//? if >=26.2 {
		/*ModuleRegistry.register(new net.veloclient.velo.devbuild.rewind.RewindModule());
		net.veloclient.velo.devbuild.accounts.AccountSwitcher.register();
		*///?}
	}
}
