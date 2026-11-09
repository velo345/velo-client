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
		// Dev build only (src/dev, ./build-dev.sh): features still being tested - release jars don't contain the class.
		try {
			Class.forName("net.veloclient.velo.devbuild.DevFeatures").asSubclass(VeloAddon.class).getDeclaredConstructor().newInstance().onVeloInitialize();
		} catch (ClassNotFoundException releaseBuild) {
			// not a dev build
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			net.veloclient.velo.VeloClient.LOGGER.error("Dev build features failed to initialize", e);
		}
	}
}
