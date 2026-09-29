package net.veloclient.velo.client.modules.hud;

/**
 * One container found by {@link StorageFinderModule} while scanning chunks the client already
 * has loaded - dimension plus block position plus a short kind label ("Chest", "Barrel", ...).
 * Persisted so a container found in an earlier session still shows up even if its chunk isn't
 * currently loaded.
 */
public record StorageEntry(String dimension, String kind, int x, int y, int z) {

	String key() {
		return dimension + "@" + x + "," + y + "," + z;
	}
}
