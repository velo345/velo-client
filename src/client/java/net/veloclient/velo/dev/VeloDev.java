package net.veloclient.velo.dev;

import net.veloclient.velo.client.addon.VeloAddon;
import net.veloclient.velo.module.ModuleRegistry;

/**
 * The features that grew up in the dev build (released with 1.1): pets, World Notes, Storage Memory, Velo Bridge,
 * Discord status and the tweak modules. The core calls this before the profile loads, like an add-on. New unreleased
 * features start in src/dev again ({@code ./build-dev.sh}) and move here when they're ready.
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
		net.veloclient.velo.dev.bridge.VeloBridgeClient.init();
		ModuleRegistry.register(new net.veloclient.velo.dev.discord.DiscordPresenceModule());
		//? if >=26.2 {
		/*ModuleRegistry.register(new net.veloclient.velo.dev.tweaks.ChunkAnimatorModule());
		*///?}
	}
}
