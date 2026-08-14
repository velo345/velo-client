package net.veloclient.velo.client.store;

import net.minecraft.util.Identifier;

/**
 * One purchasable entry in the {@link StoreCatalog}. {@code gifResource}
 * points at the item's bundled animated-cape art under this mod's own
 * assets (see {@link StoreCatalog} for the actual list) - the only kind of
 * store item that exists today, hence no item "type" field yet.
 */
public record StoreItem(String id, StoreCategory category, String name, String description, int priceCoins,
		Identifier gifResource) {
}
