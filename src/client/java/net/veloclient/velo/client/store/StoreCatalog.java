package net.veloclient.velo.client.store;

import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The store's capes: the ones bundled with the mod, updated and extended by the server's cosmetics
 * catalog (new capes, prices, what's in the store right now) - so a new cape needs no client update.
 */
public final class StoreCatalog {

	private static final List<StoreItem> BUNDLED = List.of(
			cape("cape_black_pattern", "Digitized", "Shifting black geometric weave.", 900),
			cape("cape_red_code", "Codebreaker", "Scrolling red code cascades down the fabric.", 1000),
			cape("cape_red_lightning", "Storm Strike", "Crackling red lightning arcs across the cloth.", 1200),
			cape("cape_symbols", "Rune Weave", "Animated arcane symbols drift and glow.", 950));

	private static int seenVersion = -1;
	private static List<StoreItem> items = BUNDLED;

	private StoreCatalog() {
	}

	private static StoreItem cape(String id, String name, String description, int priceCoins) {
		Identifier gif = Identifier.of("velo-client", "textures/store/cape/" + id + ".gif");
		// Not for sale any more (the Velo Originals replaced them) - still shown on whoever owns one.
		return new StoreItem(id, StoreCategory.CAPES, name, description, priceCoins, gif, null, false);
	}

	private static synchronized List<StoreItem> items() {
		int version = CosmeticsCatalog.version();
		if (version != seenVersion) {
			seenVersion = version;
			boolean server = CosmeticsCatalog.loaded();
			Map<String, StoreItem> merged = new LinkedHashMap<>();
			for (StoreItem item : BUNDLED) {
				merged.put(item.id(), server ? new StoreItem(item.id(), item.category(), item.name(), item.description(), item.priceCoins(),
						item.gifResource(), null, false) : item);
			}
			List<CosmeticsCatalog.Item> remote = new ArrayList<>(CosmeticsCatalog.items());
			remote.addAll(CosmeticsCatalog.lookedUp());
			for (CosmeticsCatalog.Item c : remote) {
				if (!"cape".equals(c.type())) {
					continue;
				}
				StoreItem bundled = BUNDLED.stream().filter(i -> i.id().equals(c.id())).findFirst().orElse(null);
				merged.put(c.id(), new StoreItem(c.id(), StoreCategory.CAPES, c.name(), c.description(),
						c.price() > 0 ? c.price() : bundled != null ? bundled.priceCoins() : 0,
						bundled != null ? bundled.gifResource() : null, bundled != null ? null : c.files().get("gif"), c.inStore()));
			}
			items = List.copyOf(merged.values());
		}
		return items;
	}

	/** Every cape this client knows. */
	public static List<StoreItem> all() {
		return items();
	}

	/** What the Store sells in a category right now. */
	public static List<StoreItem> byCategory(StoreCategory category) {
		return items().stream().filter(item -> item.category() == category && item.inStore()).toList();
	}

	public static java.util.Optional<StoreItem> byId(String id) {
		java.util.Optional<StoreItem> found = items().stream().filter(item -> item.id().equals(id)).findFirst();
		if (found.isEmpty() && id != null && id.startsWith("cape_")) {
			CosmeticsCatalog.requestItem(id);
		}
		return found;
	}
}
