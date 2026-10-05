package net.veloclient.launcher.data;

import net.veloclient.launcher.social.StoreApi;

/** Which store items you own - the Velo server's record. */
public final class StoreOwnership {

	private StoreOwnership() {
	}

	public static boolean owns(String itemId) {
		return StoreApi.owns(itemId);
	}
}
