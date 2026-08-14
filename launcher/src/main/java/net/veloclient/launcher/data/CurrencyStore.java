package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Velo Coins balance - reads/writes the exact same {@code
 * config/currency.json} the mod's own {@code CurrencyManager} uses, so a
 * purchase made in either place is immediately reflected in the other.
 */
public final class CurrencyStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private CurrencyStore() {
	}

	public static int balance() {
		Path file = VeloPaths.config().resolve("currency.json");
		if (!Files.exists(file)) {
			return 0;
		}
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Persisted persisted = GSON.fromJson(reader, Persisted.class);
			return persisted == null ? 0 : persisted.veloCoins;
		} catch (IOException e) {
			return 0;
		}
	}

	public static void grant(int amount) {
		if (amount <= 0) {
			return;
		}
		save(balance() + amount);
	}

	/** Deducts {@code amount} if the balance covers it. Returns false (and leaves the balance untouched) otherwise. */
	public static boolean spend(int amount) {
		int current = balance();
		if (amount <= 0 || current < amount) {
			return false;
		}
		save(current - amount);
		return true;
	}

	private static void save(int balance) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve("currency.json");
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(new Persisted(balance), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save Velo Coins balance", e);
		}
	}

	private static final class Persisted {
		int veloCoins;

		Persisted(int veloCoins) {
			this.veloCoins = veloCoins;
		}
	}
}
