package net.veloclient.velo.client.store;

import net.veloclient.velo.client.network.StoreClient;

import java.util.function.Consumer;

/**
 * Buys a store item with Velo Coins - on the server (which checks the price and your balance and
 * records ownership), then adds the cape to the local library.
 */
public final class StorePurchase {

	private StorePurchase() {
	}

	/** {@code onDone} runs on the client thread with null on success, else the message to show. */
	public static void buy(StoreItem item, Consumer<String> onDone) {
		if (StoreClient.owns(item.id())) {
			onDone.accept("You already own this cape.");
			return;
		}
		StoreClient.buy(item.id(), () -> {
			StoreRestore.restoreMissing();
			onDone.accept(null);
		}, onDone);
	}
}
