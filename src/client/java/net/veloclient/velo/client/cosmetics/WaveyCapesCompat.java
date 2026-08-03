package net.veloclient.velo.client.cosmetics;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Detects the separately-installed <a href="https://modrinth.com/mod/wavey-capes">Wavey Capes</a>
 * mod (id {@code waveycapes}), so Velo's own cape rendering can step aside
 * and let it take over. Wavey Capes has no public integration API and its
 * license requires the author's permission to redistribute with a "Client",
 * so this is a soft runtime dependency only - Velo never bundles or compiles
 * against it, and everything here degrades to Velo's own {@link
 * net.veloclient.velo.client.cosmetics.physics.ClothSimulator} physics when
 * it isn't present.
 *
 * <p>Wavey Capes' own cape renderer falls back to rendering whatever texture
 * the player's real (vanilla-resolved) cape is, with its own wave physics,
 * whenever no other registered "cape provider" mod claims the player first -
 * confirmed by reading its actual {@code CapeNodeCollector}/{@code
 * VanillaCapeRenderer} source. So when Wavey Capes is present, Velo skips
 * registering its own {@link net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}
 * layer (avoiding double rendering) and instead reports the equipped Velo
 * cape as the local player's vanilla cape texture (see the local-player skin
 * override mixin), letting Wavey Capes animate it.
 */
public final class WaveyCapesCompat {

	private static final String MOD_ID = "waveycapes";
	private static Boolean loaded;

	private WaveyCapesCompat() {
	}

	public static boolean isLoaded() {
		if (loaded == null) {
			loaded = FabricLoader.getInstance().isModLoaded(MOD_ID);
		}
		return loaded;
	}
}
