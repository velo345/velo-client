package net.veloclient.velo.client.addon;

/**
 * Entrypoint for optional add-on mods that extend Velo Client (e.g. the separately-installed
 * "Velo Client Addons" jar with the experimental singleplayer/minigame modules). Declare it in the
 * add-on's {@code fabric.mod.json} under the {@code "velo-client:addon"} entrypoint key.
 *
 * <p>Velo Client calls every add-on right after registering its own built-in modules and before
 * the active profile (enabled state, settings, HUD layout) is loaded - so add-on modules get their
 * saved state restored exactly like built-in ones, which a plain Fabric {@code client}
 * entrypoint can't guarantee (mod entrypoint order isn't fixed).
 */
public interface VeloAddon {

	String ENTRYPOINT = "velo-client:addon";

	/** Register modules with {@code ModuleRegistry.register(...)} and any hooks (see {@link ScrollHandlers}). */
	void onVeloInitialize();
}
