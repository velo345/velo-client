package net.veloclient.velo.client.store;

import net.veloclient.velo.client.network.StoreClient;

/** Which store items you own - the server's record (see {@link StoreClient}). */
public final class StoreOwnership {

	private StoreOwnership() {
	}

	public static boolean owns(String itemId) {
		return StoreClient.owns(itemId);
	}
}
