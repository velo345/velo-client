package net.veloclient.launcher.data;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The Store's capes: the ones bundled with the launcher (same ids as the mod's), updated and extended
 * by the server's cosmetics catalog - new capes, prices, what's in the store right now.
 */
public final class StoreCatalog {

	private static final List<StoreItem> BUNDLED = List.of(
			cape("cape_black_pattern", "Digitized", "Shifting black geometric weave.", 900),
			cape("cape_red_code", "Codebreaker", "Scrolling red code cascades down the fabric.", 1000),
			cape("cape_red_lightning", "Storm Strike", "Crackling red lightning arcs across the cloth.", 1200),
			cape("cape_symbols", "Rune Weave", "Animated arcane symbols drift and glow.", 950));

	private StoreCatalog() {
	}

	private static StoreItem cape(String id, String name, String description, int priceCoins) {
		// Not for sale any more (the Velo Originals replaced them) - still yours if you own one.
		return new StoreItem(id, name, description, priceCoins, "/net/veloclient/launcher/store/cape/" + id + ".gif", null, false);
	}

	/** Every cape the launcher knows (bundled, for sale, or owned). */
	public static List<StoreItem> all() {
		List<com.google.gson.JsonObject> server = WingsCatalog.serverItems("cape");
		boolean known = WingsCatalog.serverLoaded();
		java.util.Map<String, StoreItem> merged = new java.util.LinkedHashMap<>();
		for (StoreItem item : BUNDLED) {
			// Once the server's catalog is in, a bundled cape it doesn't list isn't for sale (hidden).
			merged.put(item.id(), known ? new StoreItem(item.id(), item.name(), item.description(), item.priceCoins(), item.gifResourcePath(),
					null, false) : item);
		}
		for (com.google.gson.JsonObject o : server) {
			String id = o.get("id").getAsString();
			StoreItem bundled = BUNDLED.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
			String sha = o.has("files") && o.getAsJsonObject("files").has("gif") ? o.getAsJsonObject("files").get("gif").getAsString() : null;
			merged.put(id, new StoreItem(id, o.has("name") ? o.get("name").getAsString() : id,
					o.has("description") && !o.get("description").isJsonNull() ? o.get("description").getAsString() : "",
					o.has("price") && o.get("price").getAsInt() > 0 ? o.get("price").getAsInt() : bundled != null ? bundled.priceCoins() : 0,
					bundled != null ? bundled.gifResourcePath() : null, bundled != null ? null : sha, o.has("inStore") && o.get("inStore").getAsBoolean()));
		}
		return List.copyOf(merged.values());
	}

	/** What the Store sells right now. */
	public static List<StoreItem> forSale() {
		return all().stream().filter(StoreItem::inStore).toList();
	}

	public static Optional<StoreItem> byId(String id) {
		return all().stream().filter(item -> item.id().equals(id)).findFirst();
	}

	/** The cape's GIF: bundled, or the server's file (downloaded first if needed - call off the UI thread when you can). */
	public static InputStream openGif(StoreItem item) {
		if (item.gifResourcePath() == null) {
			return WingsCatalog.openFile(item.gifSha());
		}
		InputStream in = StoreCatalog.class.getResourceAsStream(item.gifResourcePath());
		if (in == null) {
			throw new RuntimeException("Missing bundled store resource " + item.gifResourcePath());
		}
		return in;
	}
}
