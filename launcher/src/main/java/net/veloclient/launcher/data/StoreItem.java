package net.veloclient.launcher.data;

/**
 * One purchasable Store cape - mirrors the mod's own {@code StoreItem}/{@code StoreCatalog}. The art is
 * bundled ({@code gifResourcePath}, a classpath path) or, for capes added on the server, a downloaded
 * file ({@code gifSha}). {@code inStore}: sold right now.
 */
public record StoreItem(String id, String name, String description, int priceCoins, String gifResourcePath, String gifSha, boolean inStore) {
}
