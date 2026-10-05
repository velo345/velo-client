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
			return "You already own this cape.";
		}
		try {
			StoreApi.buy(item.id());
		} catch (StoreApi.StoreError e) {
			return e.getMessage();
		}
		restoreMissing();
		return null;
	}

	/** Imports every owned store cape the local library doesn't have yet (bought elsewhere / granted). */
	public static void restoreMissing() {
		for (String itemId : StoreApi.owned()) {
			StoreCatalog.byId(itemId).ifPresent(item -> {
				boolean present = CapeLibrary.listAll().stream().anyMatch(c -> c.animated() && c.name().equals(item.name()));
				if (present) {
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
}
