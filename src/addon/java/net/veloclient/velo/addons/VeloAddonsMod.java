package net.veloclient.velo.addons;

import net.veloclient.velo.client.addon.ScrollHandlers;
import net.veloclient.velo.client.addon.VeloAddon;
import net.veloclient.velo.client.modules.hud.StorageFinderModule;
import net.veloclient.velo.client.modules.qol.AutoEatModule;
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import net.veloclient.velo.client.modules.qol.FlyBoatModule;
import net.veloclient.velo.client.modules.servertools.AutoExcavatorModule;
import net.veloclient.velo.client.modules.servertools.AutoTunnelMinerModule;
import net.veloclient.velo.client.modules.servertools.EnderChestFarmerModule;
import net.veloclient.velo.client.modules.servertools.EntityFinderModule;
import net.veloclient.velo.client.modules.servertools.SoundDebugOverlayModule;
import net.veloclient.velo.module.ModuleRegistry;

/**
 * "Velo Client Addons" - the optional, separately-installed jar holding Velo Client's
 * experimental modules (meant for singleplayer/minigames). Nothing here runs unless this jar is in
 * the mods folder next to Velo Client itself; Velo Client calls this through its
 * {@code velo-client:addon} entrypoint (see {@link VeloAddon}).
 */
public final class VeloAddonsMod implements VeloAddon {

	@Override
	public void onVeloInitialize() {
		// QoL
		ModuleRegistry.register(new CinematicCameraModule());
		ModuleRegistry.register(new FlyBoatModule());
		ModuleRegistry.register(new AutoEatModule());
		// HUD
		ModuleRegistry.register(new StorageFinderModule());
		// Server tools
		ModuleRegistry.register(new EntityFinderModule());
		ModuleRegistry.register(new AutoTunnelMinerModule());
		ModuleRegistry.register(new EnderChestFarmerModule());
		ModuleRegistry.register(new AutoExcavatorModule());
		ModuleRegistry.register(new SoundDebugOverlayModule());

		// Scroll adjusts fly speed instead of the hotbar while these are active (checked in this order).
		ScrollHandlers.register(CinematicCameraModule::isActive, CinematicCameraModule::adjustSpeedOnScroll);
		ScrollHandlers.register(FlyBoatModule::isFlying, FlyBoatModule::adjustSpeedOnScroll);
	}
}
