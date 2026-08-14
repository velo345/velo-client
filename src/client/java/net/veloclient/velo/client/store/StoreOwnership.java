package net.veloclient.velo.client.store;

import net.veloclient.velo.config.ConfigManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which {@link StoreItem} ids the player has bought, persisted to
 * {@code config/store-ownership.json}. Deliberately separate from
 * {@link net.veloclient.velo.client.cosmetics.CapeManager}'s own library
 * (owning a store cape and it being present in the cape library are set at
 * the same time by {@link StoreItem} purchase, but this is what the Store UI
 * checks to decide "Buy" vs "Equip" - the cape library additionally holds
 * user-imported PNGs that were never a store purchase at all).
 */
public final class StoreOwnership {

	private static Set<String> owned;

	private StoreOwnership() {
	}

	public static synchronized boolean owns(String itemId) {
		return owned().contains(itemId);
	}

	public static synchronized void grant(String itemId) {
		owned().add(itemId);
		save();
	}

	private static Set<String> owned() {
		if (owned == null) {
			owned = new LinkedHashSet<>(ConfigManager.load("store-ownership", Persisted.class, new Persisted(Set.of())).ownedItemIds());
		}
		return owned;
	}

	private static void save() {
		ConfigManager.save("store-ownership", new Persisted(owned));
	}

	private record Persisted(Set<String> ownedItemIds) {
	}
}
