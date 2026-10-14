package net.veloclient.velo.client.store;

import net.minecraft.util.Identifier;

/**
 * One purchasable entry in the {@link StoreCatalog}. The art is either bundled with the mod
 * ({@code gifResource}) or, for capes added on the server later, a downloaded file
 * ({@code gifSha}, see {@link net.veloclient.velo.client.cosmetics.CosmeticsCatalog}).
 * {@code inStore}: sold right now (false for capes taken out of the store - still yours if you own them).
 */
public record StoreItem(String id, StoreCategory category, String name, String description, int priceCoins,
		Identifier gifResource, String gifSha, boolean inStore) {
}
