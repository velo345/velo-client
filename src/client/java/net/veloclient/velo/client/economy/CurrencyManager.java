package net.veloclient.velo.client.economy;

import net.veloclient.velo.client.network.StoreClient;

/** Your Velo Coins balance - kept by the Velo server; this is the last value it reported (-1 = unknown yet). */
public final class CurrencyManager {

	private CurrencyManager() {
	}

	public static int balance() {
		return StoreClient.balance();
	}
}
