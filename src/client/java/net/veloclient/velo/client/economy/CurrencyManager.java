package net.veloclient.velo.client.economy;

import net.veloclient.velo.config.ConfigManager;

/**
 * Velo Coins: the store's single currency, persisted to
 * {@code config/currency.json} - the same file the launcher's own
 * {@code CurrencyStore} reads/writes, so the balance is always in sync
 * regardless of whether a purchase was made in-game or in the launcher.
 * New accounts start at 0; coins are only ever added via the Buy Coins
 * screen (a stubbed, no-real-payment "package" purchase for now) or spent
 * buying a store item.
 */
public final class CurrencyManager {

	private static Integer balance;

	private CurrencyManager() {
	}

	public static synchronized int balance() {
		if (balance == null) {
			load();
		}
		return balance;
	}

	public static synchronized void grant(int amount) {
		if (amount <= 0) {
			return;
		}
		balance = balance() + amount;
		save();
	}

	/** Deducts {@code amount} if the balance covers it. Returns false (and leaves the balance untouched) otherwise. */
	public static synchronized boolean spend(int amount) {
		if (amount <= 0 || balance() < amount) {
			return false;
		}
		balance -= amount;
		save();
		return true;
	}

	private static void load() {
		balance = ConfigManager.load("currency", Persisted.class, new Persisted(0)).veloCoins();
	}

	private static void save() {
		ConfigManager.save("currency", new Persisted(balance));
	}

	private record Persisted(int veloCoins) {
	}
}
