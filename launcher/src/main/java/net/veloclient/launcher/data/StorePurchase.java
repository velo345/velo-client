package net.veloclient.launcher.data;

import net.veloclient.launcher.social.StoreApi;

import java.io.InputStream;

/**
 * Buying a {@link StoreItem} with Velo Coins: the server checks the price and balance and records
 * ownership; the cape then lands in the local {@link CapeLibrary}. Blocking - call off the FX thread.
 */
public final class StorePurchase {

	private StorePurchase() {
	}

	/** Returns null on success, else the message to show. */
	public static String buy(StoreItem item) {
		if (StoreOwnership.owns(item.id())) {
			return "You already own this cloak.";
		}
		try {
			StoreApi.buy(item.id());
		} catch (StoreApi.StoreError e) {
			return e.getMessage();
		}
		restoreMissing();
		return null;
	}

	/** Called with a library cloak's id after its art was swapped for the server's newer art (UI caches drop it). */
	public static volatile java.util.function.Consumer<String> onArtReplaced = id -> { };

	/**
	 * Imports every owned store cape the local library doesn't have yet (bought elsewhere / granted), and swaps the art of
	 * ones whose art the server changed since they were imported (the library keeps its own copy).
	 */
	public static void restoreMissing() {
		for (String itemId : StoreApi.owned()) {
			StoreCatalog.byId(itemId).ifPresent(item -> {
				CapeEntry present = CapeLibrary.listAllBundles().stream().filter(c -> c.animated() && c.name().equals(item.name())).findFirst().orElse(null);
				if (present != null) {
					refreshArt(present, item);
					return;
				}
				try (InputStream in = StoreCatalog.openGif(item)) {
					CapeLibrary.importAnimatedGif(item.name(), in, CapePhysicsPresetData.defaults(), item.id());
				} catch (Exception ignored) {
					// Retried on the next refresh.
				}
			});
		}
	}

	private static void refreshArt(CapeEntry entry, StoreItem item) {
		String want = item.gifSha();
		if (item.gifResourcePath() != null || want == null || want.isBlank()) {
			return;
		}
		try {
			if (want.equalsIgnoreCase(CapeLibrary.gifSha(entry))) {
				return;
			}
			try (InputStream in = StoreCatalog.openGif(item)) {
				if (in == null) {
					return;
				}
				CapeLibrary.replaceGif(entry, in);
			}
			onArtReplaced.accept(entry.id());
		} catch (Exception ignored) {
			// Retried on the next refresh.
		}
	}
}
