package net.veloclient.launcher.data;

import java.io.IOException;
import java.io.InputStream;

/** Buying a {@link StoreItem} - mirrors the mod's own {@code StorePurchase}: spend the coins, drop it into {@link CapeLibrary}, mark it owned. */
public final class StorePurchase {

	public enum Result {
		SUCCESS, ALREADY_OWNED, INSUFFICIENT_COINS, IMPORT_FAILED
	}

	private StorePurchase() {
	}

	public static Result buy(StoreItem item) {
		if (StoreOwnership.owns(item.id())) {
			return Result.ALREADY_OWNED;
		}
		if (!CurrencyStore.spend(item.priceCoins())) {
			return Result.INSUFFICIENT_COINS;
		}
		try (InputStream in = StoreCatalog.openGif(item)) {
			CapeLibrary.importAnimatedGif(item.name(), in, CapePhysicsPresetData.defaults());
			StoreOwnership.grant(item.id());
			return Result.SUCCESS;
		} catch (IOException e) {
			// Refund - the coins were already spent above, and nothing was
			// actually delivered.
			CurrencyStore.grant(item.priceCoins());
			return Result.IMPORT_FAILED;
		}
	}
}
