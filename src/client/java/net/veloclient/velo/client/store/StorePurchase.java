package net.veloclient.velo.client.store;

import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.economy.CurrencyManager;

import java.io.IOException;
import java.io.InputStream;

/** Buying a {@link StoreItem}: spend the coins, drop it into {@link CapeManager}'s library, and mark it owned - the three steps every Store "Buy" button needs, in the one order that keeps them consistent if a step fails partway through. */
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
		if (!CurrencyManager.spend(item.priceCoins())) {
			return Result.INSUFFICIENT_COINS;
		}
		try (InputStream in = StoreAssets.openGif(item)) {
			CapeManager.importAnimatedGif(item.name(), in, CapePhysicsPreset.defaults(), item.id());
			StoreOwnership.grant(item.id());
			return Result.SUCCESS;
		} catch (IOException e) {
			// Refund - the coins were already spent above, and nothing was
			// actually delivered, so a failed import must not leave the
			// player just out the price.
			CurrencyManager.grant(item.priceCoins());
			net.veloclient.velo.VeloClient.LOGGER.error("Failed to deliver purchased store item {}", item.id(), e);
			return Result.IMPORT_FAILED;
		}
	}
}
