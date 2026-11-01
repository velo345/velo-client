package net.veloclient.velo.dev;

import net.veloclient.velo.client.addon.VeloAddon;
import net.veloclient.velo.module.ModuleRegistry;

/**
 * Entry point of the dev build: features that aren't released yet live in src/dev and are only packed into the client
 * jar of a dev build ({@code ./build-dev.sh}). The core calls this before the profile loads, like an add-on. When a
 * feature is ready it moves into src/client for production.
 */
public final class VeloDev implements VeloAddon {

	@Override
	public void onVeloInitialize() {
		ModuleRegistry.register(new net.veloclient.velo.dev.pets.PetsModule());
		net.veloclient.velo.dev.pets.PetRenderer.register();
		net.veloclient.velo.dev.pets.PetsLockerScreen.register();
		ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.GlintColorizerModule());
		ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.ItemPhysicsModule());
		ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.BetterTabListModule());
		ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.ChatHeadsModule());
		ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.BlockBreakProgressModule());
		ModuleRegistry.register(new net.veloclient.velo.dev.storage.StorageMemoryModule());
		ModuleRegistry.register(new net.veloclient.velo.dev.notes.NotesModule());
		//? if >=26.2 {
		/*ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.ChunkAnimatorModule());
		*///?}
	}
}
