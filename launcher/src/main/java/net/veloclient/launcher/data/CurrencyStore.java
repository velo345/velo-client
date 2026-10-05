package net.veloclient.launcher.data;

import net.veloclient.launcher.social.StoreApi;

/** Your Velo Coins balance - kept by the Velo server; the last value it reported (-1 = not loaded yet). */
public final class CurrencyStore {

	private CurrencyStore() {
	}

	public static int balance() {
		return StoreApi.balance();
	}
}
