package net.veloclient.server.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What the store sells and for how much - read from {@code store.json} in the data directory
 * (written with defaults on first start). Prices live only here, on the server: clients display
 * what {@code /v1/store/catalog} returns and can never choose their own price.
 */
public final class StoreConfig {

	/** A coin pack bought with real money through Tebex. {@code price} is in {@link #currency}. */
	public record CoinPack(String id, int coins, int bonus, double price) {
		public int totalCoins() {
			return coins + bonus;
		}
	}

	/** A cosmetic bought with coins. The id matches the item bundled with the clients. */
	public record Item(String id, String name, int price) {
	}

	public String currency = "EUR";
	public List<CoinPack> coinPacks = List.of(
			new CoinPack("coins_500", 500, 0, 4.99),
			new CoinPack("coins_1200", 1200, 140, 9.99),
			new CoinPack("coins_2600", 2600, 400, 19.99),
			new CoinPack("coins_6500", 6500, 1500, 39.99));
	public List<Item> items = DEFAULT_ITEMS;

	/**
	 * Everything the clients ship with. New ones are added to an existing store.json on start (see
	 * {@link #load}), so a server that was set up before an item existed still sells it; prices
	 * already in the file are never changed. The wings ids are {@code wings_<id>} of the client's
	 * {@code cosmetics/wings/wings.json} - keep the prices there in sync (they're only the fallback
	 * shown before the catalog loads).
	 */
	public static final List<Item> DEFAULT_ITEMS = List.of(
			new Item("cape_black_pattern", "Digitized", 900),
			new Item("cape_red_code", "Codebreaker", 1000),
			new Item("cape_red_lightning", "Storm Strike", 1200),
			new Item("cape_symbols", "Rune Weave", 950),
			new Item("wings_angelic", "Angelic Wings", 3000),
			new Item("wings_frost", "Frost Wings", 2800),
			new Item("wings_phoenix", "Phoenix Wings", 3200),
			new Item("wings_raven", "Raven Wings", 2400),
			new Item("wings_shadowfang", "Shadowfang Wings", 2800),
			new Item("wings_cobweb", "Cobweb Wings", 2600),
			new Item("wings_bonedragon", "Bone Dragon Wings", 3000),
			new Item("wings_wraith", "Wraith Wings", 2800));

	/**
	 * A daily quest. {@code stat}: {@code online_minutes} (measured by the server from game
	 * heartbeats) or a client-reported counter ({@code mob_kills}, {@code blocks_mined},
	 * {@code blocks_placed}, {@code distance_m}). {@code minOnlineMinutes}: in-game time the
	 * server must have seen that day before it pays out - reported counters alone aren't enough.
	 */
	public record Quest(String id, String title, String stat, long target, int coins, int minOnlineMinutes) {
	}

	/**
	 * Free coins. Deliberately slow: with everything maxed every single day (login streak, all
	 * quests, all ads) a player earns roughly 30-35 coins/day, so a ~1000-coin cape is about a month
	 * of daily grinding - possible for free, but buying stays the sensible option.
	 */
	public static final class Rewards {
		public int dailyLoginCoins = 6;
		public int streakBonusPerDay = 2;
		public int streakBonusMax = 8;
		/** Extra coins on every 7th login day in a row (a full week of playing). */
		public int weeklyStreakBonus = 40;
		public int loginMinOnlineMinutes = 5;
		public int questsPerDay = 3;
		public int adCoins = 6;
		public int adsPerDay = 3;
		public List<Quest> quests = List.of(
				new Quest("play_60", "Play for 1 hour", "online_minutes", 60, 8, 0),
				new Quest("play_180", "Play for 3 hours", "online_minutes", 180, 16, 0),
				new Quest("kill_100", "Defeat 100 mobs", "mob_kills", 100, 10, 30),
				new Quest("kill_300", "Defeat 300 mobs", "mob_kills", 300, 18, 75),
				new Quest("mine_1000", "Mine 1,000 blocks", "blocks_mined", 1000, 12, 45),
				new Quest("mine_3000", "Mine 3,000 blocks", "blocks_mined", 3000, 20, 120),
				new Quest("place_500", "Place 500 blocks", "blocks_placed", 500, 10, 30),
				new Quest("walk_5k", "Travel 5 km", "distance_m", 5000, 8, 30),
				new Quest("walk_15k", "Travel 15 km", "distance_m", 15000, 16, 75));
	}

	public Rewards rewards = new Rewards();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static StoreConfig load(Path dataDir) throws IOException {
		Path file = dataDir.resolve("store.json");
		if (!Files.exists(file)) {
			StoreConfig defaults = new StoreConfig();
			Files.createDirectories(dataDir);
			Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
			return defaults;
		}
		StoreConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoreConfig.class);
		if (config == null || config.coinPacks == null || config.items == null) {
			throw new IOException("store.json is missing coinPacks or items");
		}
		if (config.rewards == null) {
			config.rewards = new Rewards();
		}
		List<Item> merged = new java.util.ArrayList<>(config.items);
		boolean added = false;
		for (Item item : DEFAULT_ITEMS) {
			if (config.item(item.id()) == null) {
				merged.add(item);
				added = true;
			}
		}
		if (added) {
			config.items = List.copyOf(merged);
			Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
		}
		return config;
	}

	public CoinPack pack(String id) {
		return coinPacks.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	public Quest quest(String id) {
		return rewards.quests.stream().filter(q -> q.id().equals(id)).findFirst().orElse(null);
	}

	public Item item(String id) {
		return items.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
	}
}
