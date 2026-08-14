package net.veloclient.velo.client.store;

import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The store's item list (design spec's cosmetics store, "Capes" category:
 * the first collection of animated capes). Hardcoded rather than
 * server-fetched for now - there's no backend yet, same as every other
 * client-only cosmetic in this mod.
 */
public final class StoreCatalog {

	private static final List<StoreItem> ITEMS = List.of(
			cape("cape_black_pattern", "Digitized", "Shifting black geometric weave.", 900),
			cape("cape_red_code", "Codebreaker", "Scrolling red code cascades down the fabric.", 1000),
			cape("cape_red_lightning", "Storm Strike", "Crackling red lightning arcs across the cloth.", 1200),
			cape("cape_symbols", "Rune Weave", "Animated arcane symbols drift and glow.", 950));

	private StoreCatalog() {
	}

	private static StoreItem cape(String id, String name, String description, int priceCoins) {
		Identifier gif = Identifier.of("velo-client", "textures/store/cape/" + id + ".gif");
		return new StoreItem(id, StoreCategory.CAPES, name, description, priceCoins, gif);
	}

	public static List<StoreItem> all() {
		return ITEMS;
	}

	public static List<StoreItem> byCategory(StoreCategory category) {
		return ITEMS.stream().filter(item -> item.category() == category).toList();
	}

	public static java.util.Optional<StoreItem> byId(String id) {
		return ITEMS.stream().filter(item -> item.id().equals(id)).findFirst();
	}
}
