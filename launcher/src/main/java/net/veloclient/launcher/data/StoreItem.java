package net.veloclient.launcher.data;

/** One purchasable Store entry - mirrors the mod's own {@code StoreItem}/{@code StoreCatalog}. {@code gifResourcePath} is a classpath resource path into this launcher's own bundled jar. */
public record StoreItem(String id, String name, String description, int priceCoins, String gifResourcePath) {
}
