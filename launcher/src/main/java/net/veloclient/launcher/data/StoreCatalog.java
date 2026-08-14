package net.veloclient.launcher.data;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** The Store's item list - mirrors the mod's own {@code StoreCatalog} (same ids/names/prices; duplicated rather than shared code, same as the rest of this launcher/mod split). Only the "Capes" category exists today. */
public final class StoreCatalog {

	private static final List<StoreItem> ITEMS = List.of(
			cape("cape_black_pattern", "Digitized", "Shifting black geometric weave.", 900),
			cape("cape_red_code", "Codebreaker", "Scrolling red code cascades down the fabric.", 1000),
			cape("cape_red_lightning", "Storm Strike", "Crackling red lightning arcs across the cloth.", 1200),
			cape("cape_symbols", "Rune Weave", "Animated arcane symbols drift and glow.", 950));

	private StoreCatalog() {
	}

	private static StoreItem cape(String id, String name, String description, int priceCoins) {
		return new StoreItem(id, name, description, priceCoins, "/net/veloclient/launcher/store/cape/" + id + ".gif");
	}

	public static List<StoreItem> all() {
		return ITEMS;
	}

	public static Optional<StoreItem> byId(String id) {
		return ITEMS.stream().filter(item -> item.id().equals(id)).findFirst();
	}

	public static InputStream openGif(StoreItem item) {
		InputStream in = StoreCatalog.class.getResourceAsStream(item.gifResourcePath());
		if (in == null) {
			throw new RuntimeException("Missing bundled store resource " + item.gifResourcePath());
		}
		return in;
	}
}
