package net.veloclient.server.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Advent calendar (December 1-24): one door a day, and only today's door can be opened - the server decides what
 * "today" is ({@link StoreConfig.Advent#zone}), so a client can't open day 24 early. What's behind a door stays secret
 * until it's opened. Rewards go through the normal grant paths (ledger coins, owned items, Velo+ days).
 *
 * <p>Owners get a test calendar outside December: the day of the month (or {@code ?day=}) picks the door, nothing is
 * granted, and the opened doors live in year 0 so {@code POST /v1/advent/reset} can close them again.
 */
public final class AdventCalendar {

	private static final Gson GSON = new Gson();
	private static final int LAST_DAY = 24;
	private static final int TEST_YEAR = 0;

	private final StoreService store;

	public AdventCalendar(StoreService store) {
		this.store = store;
	}

	private StoreConfig.Advent config() {
		return store.config().advent;
	}

	/** Today's door in the real calendar (1-24), or 0 outside December 1-24 of the calendar's year. */
	private int realToday() {
		StoreConfig.Advent advent = config();
		LocalDate today;
		try {
			today = LocalDate.now(ZoneId.of(advent.zone));
		} catch (Exception e) {
			today = LocalDate.now(java.time.ZoneOffset.UTC);
		}
		if (today.getYear() != advent.year || today.getMonthValue() != 12 || today.getDayOfMonth() > LAST_DAY) {
			return 0;
		}
		return today.getDayOfMonth();
	}

	private record Mode(int today, int year, boolean preview) {
	}

	private Mode mode(String uuid, String username, Integer testDay) {
		int real = realToday();
		if (real > 0) {
			return new Mode(real, config().year, false);
		}
		if (store.isOwner(uuid, username)) {
			int day = testDay != null ? testDay : LocalDate.now(ZoneId.of("Europe/Berlin")).getDayOfMonth();
			return new Mode(Math.max(1, Math.min(LAST_DAY, day)), TEST_YEAR, true);
		}
		return new Mode(0, config().year, false);
	}

	private StoreConfig.AdventDay door(int day) {
		for (StoreConfig.AdventDay door : config().days) {
			if (door.day() == day) {
				return door;
			}
		}
		return new StoreConfig.AdventDay(day, "coins", null, 100, 0);
	}

	/** The calendar as the launcher shows it: every door's state, rewards only behind opened doors. */
	public Map<String, Object> view(String uuid, String username, Integer testDay) throws Exception {
		Mode mode = mode(uuid, username, testDay);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", mode.today() > 0);
		out.put("preview", mode.preview());
		out.put("year", config().year);
		out.put("today", mode.today());
		if (mode.today() == 0) {
			out.put("openedToday", true);
			out.put("days", List.of());
			return out;
		}
		Map<Integer, String> claims = store.database().adventClaims(uuid, mode.year());
		out.put("openedToday", claims.containsKey(mode.today()));
		List<Map<String, Object>> days = new ArrayList<>();
		boolean perfect = true;
		for (int day = 1; day <= LAST_DAY; day++) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("day", day);
			String claim = claims.get(day);
			if (claim != null) {
				d.put("state", "opened");
				d.put("reward", GSON.fromJson(claim, JsonObject.class));
			} else if (day == mode.today()) {
				d.put("state", "today");
			} else if (day < mode.today()) {
				d.put("state", "missed");
				perfect = false;
			} else {
				d.put("state", "locked");
			}
			days.add(d);
		}
		out.put("perfect", perfect);
		out.put("days", days);
		return out;
	}

	/** Opens today's door: grants what's behind it and returns {@code reward} plus the updated calendar. */
	public Map<String, Object> open(String uuid, String username, Integer testDay) throws Exception {
		Mode mode = mode(uuid, username, testDay);
		if (mode.today() == 0) {
			throw new StoreService.StoreException(409, "The Advent calendar is closed right now");
		}
		StoreDatabase db = store.database();
		Map<Integer, String> before = db.adventClaims(uuid, mode.year());
		if (before.containsKey(mode.today())) {
			throw new StoreService.StoreException(409, "You've already opened today's door - come back tomorrow");
		}
		boolean perfect = mode.today() == LAST_DAY && before.size() == LAST_DAY - 1;
		JsonObject reward = describe(uuid, door(mode.today()), mode.preview());
		if (perfect) {
			reward.add("bonus", perfectBonus(uuid));
		}
		if (!db.claimAdvent(uuid, mode.year(), mode.today(), reward.toString())) {
			throw new StoreService.StoreException(409, "You've already opened today's door - come back tomorrow");
		}
		if (!mode.preview()) {
			try {
				db.touchWallet(uuid, username);
				grant(uuid, username, mode, reward);
				if (reward.has("bonus")) {
					grant(uuid, username, mode, reward.getAsJsonObject("bonus"));
				}
				System.out.println("[advent] Door " + mode.today() + " for " + username + ": " + reward);
			} catch (Exception e) {
				db.unclaimAdvent(uuid, mode.year(), mode.today());
				throw e;
			}
		}
		Map<String, Object> out = view(uuid, username, testDay);
		out.put("reward", reward);
		out.put("balance", db.balance(uuid));
		return out;
	}

	/** Owners only: closes every door of the test calendar again. */
	public Map<String, Object> resetTest(String uuid, String username, Integer testDay) throws Exception {
		if (!store.isOwner(uuid, username)) {
			throw new StoreService.StoreException(403, "Owners only");
		}
		store.database().unclaimAdvent(uuid, TEST_YEAR, null);
		return view(uuid, username, testDay);
	}

	/** What the door gives this player: an item they already own (or one the server doesn't know) turns into coins. */
	private JsonObject describe(String uuid, StoreConfig.AdventDay door, boolean preview) throws Exception {
		JsonObject reward = new JsonObject();
		reward.addProperty("day", door.day());
		String type = door.type() == null ? "coins" : door.type();
		switch (type) {
			case "item" -> {
				CosmeticsService.Item item = store.cosmetics() == null ? null : store.cosmetics().item(door.id());
				boolean owned = item != null && !preview && store.database().owns(uuid, door.id());
				if (item == null || owned) {
					reward.addProperty("type", "coins");
					reward.addProperty("amount", Math.max(100, door.amount()));
					reward.addProperty("name", Math.max(100, door.amount()) + " Coins");
					if (item != null) {
						reward.addProperty("instead", item.name);
					}
				} else {
					reward.addProperty("type", "item");
					reward.addProperty("id", item.id);
					reward.addProperty("itemType", item.type);
					reward.addProperty("name", item.name);
					reward.addProperty("amount", 1);
					reward.addProperty("worth", item.price);
					if (item.accent != null) {
						reward.addProperty("accent", item.accent);
					}
				}
			}
			case "plus" -> {
				reward.addProperty("type", "plus");
				reward.addProperty("amount", door.amount());
				reward.addProperty("name", door.amount() + " days of Velo+");
			}
			case "mystery" -> {
				int max = Math.max(door.amount(), door.max());
				// Rounded to 25s, and skewed low: the big amounts are rare.
				double roll = Math.pow(ThreadLocalRandom.current().nextDouble(), 1.8);
				int coins = (int) Math.round((door.amount() + roll * (max - door.amount())) / 25.0) * 25;
				reward.addProperty("type", "coins");
				reward.addProperty("amount", coins);
				reward.addProperty("name", coins + " Coins");
				reward.addProperty("mystery", true);
			}
			default -> {
				reward.addProperty("type", "coins");
				reward.addProperty("amount", door.amount());
				reward.addProperty("name", door.amount() + " Coins");
			}
		}
		return reward;
	}

	private JsonObject perfectBonus(String uuid) throws Exception {
		StoreConfig.Advent advent = config();
		CosmeticsService.Item item = advent.perfectItem == null || store.cosmetics() == null ? null : store.cosmetics().item(advent.perfectItem);
		if (item != null && !store.database().owns(uuid, item.id)) {
			JsonObject bonus = describe(uuid, new StoreConfig.AdventDay(LAST_DAY, "item", item.id, advent.perfectCoins, 0), false);
			bonus.addProperty("perfect", true);
			return bonus;
		}
		JsonObject bonus = new JsonObject();
		bonus.addProperty("day", LAST_DAY);
		bonus.addProperty("type", "coins");
		bonus.addProperty("amount", advent.perfectCoins);
		bonus.addProperty("name", advent.perfectCoins + " Coins");
		bonus.addProperty("perfect", true);
		return bonus;
	}

	private void grant(String uuid, String username, Mode mode, JsonObject reward) throws Exception {
		String type = reward.get("type").getAsString();
		int amount = reward.get("amount").getAsInt();
		String ref = mode.year() + "-12-" + String.format("%02d", mode.today());
		switch (type) {
			case "item" -> store.grantSystem(uuid, username, reward.get("id").getAsString(), "advent");
			case "plus" -> store.grantPlus(uuid, username, amount, "advent", "advent:" + ref + ":" + uuid, null);
			default -> store.database().adjust(uuid, amount, "advent", ref, "advent");
		}
	}
}
