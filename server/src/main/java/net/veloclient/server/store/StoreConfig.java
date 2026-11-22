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
	/** ~250 coins per euro at 9.99, more per euro for the bigger packs (240 -> 300). */
	public List<CoinPack> coinPacks = DEFAULT_PACKS;

	public static final List<CoinPack> DEFAULT_PACKS = List.of(
			new CoinPack("coins_1200", 1200, 0, 4.99),
			new CoinPack("coins_2500", 2300, 200, 9.99),
			new CoinPack("coins_5500", 4700, 800, 19.99),
			new CoinPack("coins_15000", 12000, 3000, 49.99));

	/** The packs every store.json was created with before the 2026-10 price change - replaced on start if untouched. */
	private static final List<CoinPack> OLD_DEFAULT_PACKS = List.of(
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

	/** The Tebex webstore's package ids (Headless API): coin pack id, {@code plus_month}, {@code plus_year}. */
	public static final java.util.Map<String, Long> DEFAULT_TEBEX_PACKAGES = java.util.Map.of(
			"plus_month", 7774709L, "plus_year", 7774713L,
			"coins_1200", 7774732L, "coins_2500", 7774735L, "coins_5500", 7774742L, "coins_15000", 7774750L);

	/** Overrides of {@link #DEFAULT_TEBEX_PACKAGES} from store.json. */
	public java.util.Map<String, Long> tebexPackages;

	/** The Tebex package for {@code key}, or 0 when there is none. */
	public long tebexPackage(String key) {
		Long id = tebexPackages != null ? tebexPackages.get(key) : null;
		if (id == null) {
			id = DEFAULT_TEBEX_PACKAGES.get(key);
		}
		return id == null ? 0 : id;
	}

	/** What a Tebex package id is: a coin pack id, {@code plus_month} or {@code plus_year}; null if none of ours. */
	public String tebexKey(long packageId) {
		java.util.Map<String, Long> all = new java.util.HashMap<>(DEFAULT_TEBEX_PACKAGES);
		if (tebexPackages != null) {
			all.putAll(tebexPackages);
		}
		return all.entrySet().stream().filter(e -> e.getValue() == packageId).map(java.util.Map.Entry::getKey).findFirst().orElse(null);
	}

	/** The Velo+ subscription: what it costs (shown), how long a payment lasts, and the monthly coins it includes. */
	public static final class Plus {
		public double price = 9.99;
		public int periodDays = 30;
		public int monthlyCoins = 2000;
		/** Free Velo+ once per account that never had it (0 = no trial). No monthly coins during the trial. */
		public int trialDays = 7;
		/** Velo+ for a year, paid at once (0 = only monthly). Members get the monthly coins all year. */
		public double yearlyPrice = 99.99;
		public int yearDays = 365;
	}

	public Plus plus = new Plus();

	/** Launch week: accounts whose first Velo sign-in is inside [from, until) get {@code coins} once. 0 coins = off. */
	public static final class LaunchBonus {
		public int coins = 1000;
		/** 2026-11-01 00:00 CET (Velo Client 1.0) and a week after. */
		public long from = 1_793_487_600_000L;
		public long until = 1_793_487_600_000L + 7L * 86_400_000L;
	}

	public LaunchBonus launchBonus = new LaunchBonus();

	/**
	 * The 1.0 welcome gift: from {@code from} until {@code until}, every account (new or old) may pick one cosmetic from
	 * the Store for free, once. {@code from} 0 = off.
	 */
	public static final class WelcomeGift {
		/** 2026-11-01 00:00 CET (Velo Client 1.0) and three months after. */
		public long from = 1_793_487_600_000L;
		public long until = 1_793_487_600_000L + 90L * 86_400_000L;
	}

	public WelcomeGift welcomeGift = new WelcomeGift();

	/**
	 * One door of the Advent calendar. {@code type}: "coins" ({@code amount}), "mystery" (a random amount between
	 * {@code amount} and {@code max}), "item" ({@code id}; already owned = {@code amount} coins instead) or "plus"
	 * ({@code amount} days of Velo+).
	 */
	public record AdventDay(int day, String type, String id, int amount, int max) {
	}

	/** The Advent calendar: December 1 to 24 of {@code year}, one door a day, only today's door can be opened. */
	public static final class Advent {
		public int year = 2026;
		/** When a door's day starts (players are mostly in Germany). */
		public String zone = "Europe/Berlin";
		/** Opened every single door: this item (or {@code perfectCoins}) on top of the last door. */
		public String perfectItem = "pet_reindeer";
		public int perfectCoins = 1500;
		public List<AdventDay> days = DEFAULT_ADVENT;
	}

	public static final List<AdventDay> DEFAULT_ADVENT = List.of(
			new AdventDay(1, "coins", null, 150, 0),
			new AdventDay(2, "item", "wear_santa_hat", 500, 0),
			new AdventDay(3, "coins", null, 200, 0),
			new AdventDay(4, "item", "cape_gift_wrap", 700, 0),
			new AdventDay(5, "mystery", null, 100, 800),
			new AdventDay(6, "item", "wings_candycane", 1100, 0),
			new AdventDay(7, "coins", null, 250, 0),
			new AdventDay(8, "plus", null, 3, 0),
			new AdventDay(9, "coins", null, 250, 0),
			new AdventDay(10, "item", "cape_festive_lights", 700, 0),
			new AdventDay(11, "coins", null, 300, 0),
			new AdventDay(12, "item", "aura_frost", 800, 0),
			new AdventDay(13, "mystery", null, 150, 1000),
			new AdventDay(14, "item", "killfx_frostbite", 900, 0),
			new AdventDay(15, "coins", null, 350, 0),
			new AdventDay(16, "item", "wings_snowdrift", 1100, 0),
			new AdventDay(17, "plus", null, 7, 0),
			new AdventDay(18, "coins", null, 400, 0),
			new AdventDay(19, "item", "wings_ice_star", 1000, 0),
			new AdventDay(20, "mystery", null, 200, 1200),
			new AdventDay(21, "item", "cape_silent_night", 800, 0),
			new AdventDay(22, "item", "aura_festive", 1000, 0),
			new AdventDay(23, "coins", null, 750, 0),
			new AdventDay(24, "plus", null, 30, 0));

	public Advent advent = new Advent();

	/**
	 * A real-money sale (Black Friday): {@code percent} off the coin packs and the first Velo+ payment from {@code from}
	 * until {@code until}, through the Tebex coupon {@code tebexCoupon} (made by the owner in the Tebex panel - percentage,
	 * all packages, single + subscription baskets). The server tries the coupon on a throwaway basket and only shows and
	 * charges the lower price once Tebex really takes it. From {@code teaserFrom} the clients announce it ahead.
	 * {@code percent} 0 = off.
	 */
	public static final class Sale {
		public String id = "black_friday_2026";
		public String name = "Black Friday";
		public int percent = 50;
		/** 2026-11-21 00:00 CET: the "starts Monday" teaser. */
		public long teaserFrom = 1_795_215_600_000L;
		/** Monday 2026-11-23 00:00 CET. */
		public long from = 1_795_388_400_000L;
		/** Through Cyber Monday: 2026-12-01 00:00 CET. */
		public long until = 1_796_079_600_000L;
		public String tebexCoupon = "BLACKFRIDAY";
		public boolean coins = true;
		public boolean plus = true;
	}

	public Sale sale = new Sale();

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
		if (config.plus == null) {
			config.plus = new Plus();
		}
		boolean added = false;
		// The 2026-10 price change reaches servers whose store.json still has the old defaults (custom prices stay).
		if (config.coinPacks.equals(OLD_DEFAULT_PACKS)) {
			config.coinPacks = DEFAULT_PACKS;
			added = true;
		}
		if (config.plus.price == 8.99 && config.plus.monthlyCoins == 2500) {
			config.plus.price = 9.99;
			config.plus.monthlyCoins = 2000;
			added = true;
		}
		List<Item> merged = new java.util.ArrayList<>(config.items);
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
