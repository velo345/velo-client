package net.veloclient.server.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The Velo Coins economy, authoritative on the server: balances, owned cosmetics, real-money coin
 * packs (Tebex), owner admin tools, and the free-coins side (daily login streak, daily quests,
 * rewarded ads). Clients only ever display what this returns - nothing a client stores locally
 * can create coins or items.
 */
public final class StoreService {

	/** An error to show the player as-is, with the HTTP status to answer with. */
	public static final class StoreException extends Exception {
		public final int status;

		public StoreException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	/** Resolves a username or uuid to {uuid, username} (the friends system's lookup). */
	public interface PlayerResolver {
		String[] resolve(String query) throws Exception;
	}

	private static final long HEARTBEAT_MAX_GAP_MS = 3 * 60_000L;
	/** Per-minute plausibility caps for client-reported quest counters. */
	private static final Map<String, Integer> STAT_RATE_PER_MINUTE = Map.of(
			"mob_kills", 40, "blocks_mined", 300, "blocks_placed", 300, "distance_m", 1200);

	private final StoreConfig config;
	private final StoreDatabase db;
	private final TebexCheckout tebex;
	private final Set<String> owners;
	private final String publicUrl;
	private final AdConfig ads;
	private final PlayerResolver resolver;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Long> lastStatReport = new ConcurrentHashMap<>();
	/** Short-lived token for the ad page link -> uuid (keeps the page tied to a signed-in player). */
	private final Map<String, AdToken> adTokens = new ConcurrentHashMap<>();

	private record AdToken(String uuid, long expiresAt) {
	}

	/**
	 * Rewarded video ads by ayeT-Studios (web SDK). {@code placementId} and {@code adslot} come from the
	 * ayeT dashboard (Placements); {@code apiKey} (Account settings) signs their server-to-server callbacks.
	 */
	public record AdConfig(String placementId, String adslot, String apiKey) {
		public boolean complete() {
			return placementId != null && adslot != null && apiKey != null;
		}
	}

	public StoreService(StoreConfig config, StoreDatabase db, TebexCheckout tebex, Set<String> owners, String publicUrl,
			AdConfig ads, PlayerResolver resolver) {
		this.config = config;
		this.db = db;
		this.tebex = tebex;
		this.owners = owners;
		this.publicUrl = publicUrl == null ? null : publicUrl.replaceAll("/+$", "");
		this.ads = ads == null ? new AdConfig(null, null, null) : ads;
		this.resolver = resolver;
	}

	public static String today() {
		return LocalDate.now(ZoneOffset.UTC).toString();
	}

	public boolean isOwner(String uuid, String username) {
		return owners.contains(uuid) || (username != null && owners.contains(username.toLowerCase(Locale.ROOT)));
	}

	public boolean checkoutEnabled() {
		return tebex.canCheckout() && publicUrl != null;
	}

	public boolean adsEnabled() {
		return ads.complete() && publicUrl != null;
	}

	// ---- Catalog & wallet ----

	public record CatalogView(String currency, List<StoreConfig.CoinPack> coinPacks, List<StoreConfig.Item> items,
			boolean checkoutEnabled, boolean adsEnabled) {
	}

	public CatalogView catalog() {
		return new CatalogView(config.currency, config.coinPacks, config.items, checkoutEnabled(), adsEnabled());
	}

	public record WalletView(int balance, List<String> owned, boolean owner) {
	}

	public WalletView wallet(String uuid, String username) throws Exception {
		db.touchWallet(uuid, username);
		return new WalletView(db.balance(uuid), db.ownedItems(uuid), isOwner(uuid, username));
	}

	/** Store capes are published by item id; only owners may show them (anything else is a custom/other id). */
	public boolean mayDisplayCape(String uuid, String capeId) {
		if (capeId == null || config.item(capeId) == null) {
			return true;
		}
		try {
			return db.owns(uuid, capeId);
		} catch (Exception e) {
			return false;
		}
	}

	public WalletView buy(String uuid, String username, String itemId) throws Exception {
		StoreConfig.Item item = config.item(itemId);
		if (item == null) {
			throw new StoreException(404, "That item isn't sold here");
		}
		switch (db.buyItem(uuid, itemId, item.price())) {
			case ALREADY_OWNED -> throw new StoreException(409, "You already own " + item.name());
			case INSUFFICIENT_COINS -> throw new StoreException(402, "Not enough Velo Coins - " + item.name() + " costs " + item.price());
			default -> {
			}
		}
		return wallet(uuid, username);
	}

	// ---- Real-money coin packs (Tebex) ----

	public record CheckoutView(String orderId, String checkoutUrl) {
	}

	public CheckoutView checkout(String uuid, String username, String packId) throws Exception {
		if (!checkoutEnabled()) {
			throw new StoreException(503, "Buying coins isn't set up on this server yet");
		}
		StoreConfig.CoinPack pack = config.pack(packId);
		if (pack == null) {
			throw new StoreException(404, "Unknown coin pack");
		}
		String orderId = "vo_" + randomHex(12);
		db.createOrder(orderId, uuid, pack, config.currency);
		String name = String.format(Locale.ROOT, "%,d Velo Coins", pack.totalCoins()) + (username != null ? " for " + username : "");
		TebexCheckout.Basket basket = tebex.createCheckout(orderId, uuid, username, name, pack.price(),
				publicUrl + "/store/done?cancelled=1", publicUrl + "/store/done?order=" + orderId);
		db.setBasket(orderId, basket.ident());
		return new CheckoutView(orderId, basket.checkoutUrl());
	}

	public record OrderView(String orderId, String status, int coins, int balance) {
	}

	public OrderView order(String uuid, String orderId) throws Exception {
		StoreDatabase.Order order = db.order(orderId);
		if (order == null || !order.uuid().equals(uuid)) {
			throw new StoreException(404, "Unknown order");
		}
		return new OrderView(order.orderId(), order.status(), order.coins(), db.balance(uuid));
	}

	/**
	 * A verified Tebex webhook. Returns the JSON body to answer with. Payments are credited from our
	 * own order record (coins/price fixed when the order was created), never from amounts in the
	 * webhook; the price actually paid is checked against it.
	 */
	public Object tebexWebhook(com.google.gson.JsonObject event) throws Exception {
		String id = event.has("id") ? event.get("id").getAsString() : null;
		String type = event.has("type") ? event.get("type").getAsString() : "";
		if ("validation.webhook".equals(type)) {
			return Map.of("id", id == null ? "" : id);
		}
		if (id == null || !db.firstDelivery(id, type)) {
			return Map.of("ok", true); // retry of something already handled
		}
		try {
			com.google.gson.JsonObject subject = event.has("subject") && event.get("subject").isJsonObject()
					? event.getAsJsonObject("subject") : new com.google.gson.JsonObject();
			String txn = subject.has("transaction_id") ? subject.get("transaction_id").getAsString() : null;
			switch (type) {
				case "payment.completed" -> handlePaid(subject, txn);
				case "payment.refunded" -> reverse(txn, "refunded");
				case "payment.dispute.lost" -> reverse(txn, "chargeback");
				case "payment.dispute.opened" -> System.out.println("[store] Dispute opened on " + txn + " - coins stay until it's lost");
				default -> {
				}
			}
			return Map.of("ok", true);
		} catch (Exception e) {
			db.forgetDelivery(id); // let Tebex's retry try again
			throw e;
		}
	}

	private void handlePaid(com.google.gson.JsonObject subject, String txn) throws Exception {
		if (!subject.has("products")) {
			return;
		}
		double paid = subject.has("price_paid") ? subject.getAsJsonObject("price_paid").get("amount").getAsDouble() : -1;
		String paidCurrency = subject.has("price_paid") ? subject.getAsJsonObject("price_paid").get("currency").getAsString() : "";
		for (var element : subject.getAsJsonArray("products")) {
			com.google.gson.JsonObject product = element.getAsJsonObject();
			if (!product.has("custom") || !product.get("custom").isJsonObject()) {
				continue;
			}
			com.google.gson.JsonObject custom = product.getAsJsonObject("custom");
			if (!custom.has("velo_order")) {
				continue;
			}
			String orderId = custom.get("velo_order").getAsString();
			StoreDatabase.Order order = db.order(orderId);
			if (order == null) {
				System.out.println("[store] Paid webhook for unknown order " + orderId + " (" + txn + ")");
				continue;
			}
			boolean uuidMatches = custom.has("velo_uuid") && order.uuid().equals(custom.get("velo_uuid").getAsString());
			boolean priceOk = paid < 0 || !paidCurrency.equalsIgnoreCase(order.currency()) || paid + 0.01 >= order.price();
			if (!uuidMatches || !priceOk) {
				db.flagOrder(orderId, "review", txn);
				System.out.println("[store] Order " + orderId + " needs review (uuid ok: " + uuidMatches + ", paid " + paid + " "
						+ paidCurrency + " vs " + order.price() + " " + order.currency() + ")");
				continue;
			}
			if (db.completeOrder(orderId, txn)) {
				System.out.println("[store] Credited " + order.coins() + " coins to " + order.uuid() + " for " + txn);
			}
		}
	}

	private void reverse(String txn, String status) throws Exception {
		if (txn == null) {
			return;
		}
		int reversed = db.reverseByTransaction(txn, status);
		if (reversed > 0) {
			System.out.println("[store] " + status + " on " + txn + " - coins taken back from " + reversed + " order(s)");
		}
	}

	public boolean verifyTebexSignature(byte[] body, String signature) {
		return tebex.verifySignature(body, signature);
	}

	// ---- Owner tools ----

	public record PlayerView(String uuid, String username, int balance, List<String> owned, List<StoreDatabase.LedgerEntry> ledger) {
	}

	private String[] requireOwnerAndResolve(String meUuid, String meName, String target) throws Exception {
		if (!isOwner(meUuid, meName)) {
			throw new StoreException(403, "Only the server owner can do that");
		}
		String[] ref = resolver.resolve(target);
		if (ref == null) {
			throw new StoreException(404, "No such player");
		}
		return ref;
	}

	public PlayerView adminLookup(String meUuid, String meName, String target) throws Exception {
		String[] ref = requireOwnerAndResolve(meUuid, meName, target);
		return new PlayerView(ref[0], ref[1], db.balance(ref[0]), db.ownedItems(ref[0]), db.ledger(ref[0], 30));
	}

	public PlayerView adminCoins(String meUuid, String meName, String target, int amount, String note) throws Exception {
		String[] ref = requireOwnerAndResolve(meUuid, meName, target);
		if (amount == 0 || Math.abs(amount) > 10_000_000) {
			throw new StoreException(400, "Amount must be between -10,000,000 and 10,000,000 (not 0)");
		}
		db.touchWallet(ref[0], ref[1]);
		db.adjust(ref[0], amount, amount > 0 ? "admin_grant" : "admin_take", note, meName + "/" + meUuid);
		return adminLookup(meUuid, meName, ref[0]);
	}

	public PlayerView adminItem(String meUuid, String meName, String target, String itemId, boolean grant) throws Exception {
		String[] ref = requireOwnerAndResolve(meUuid, meName, target);
		if (config.item(itemId) == null) {
			throw new StoreException(404, "Unknown item");
		}
		db.touchWallet(ref[0], ref[1]);
		if (grant) {
			db.grantItem(ref[0], itemId, meName + "/" + meUuid);
		} else {
			db.revokeItem(ref[0], itemId, meName + "/" + meUuid);
		}
		return adminLookup(meUuid, meName, ref[0]);
	}

	// ---- Free coins: daily login, quests, ads ----

	/** Every game-session heartbeat (game, not launcher) counts towards today's in-game time. */
	public void onGameHeartbeat(String uuid) {
		try {
			db.recordHeartbeat(uuid, today(), System.currentTimeMillis(), HEARTBEAT_MAX_GAP_MS);
		} catch (Exception e) {
			System.out.println("[store] heartbeat accounting failed: " + e.getMessage());
		}
	}

	public record QuestView(String id, String title, long progress, long target, int coins, boolean claimed, boolean claimable,
			String hint) {
	}

	public record RewardsView(String day, int onlineMinutes, boolean loginClaimed, boolean loginClaimable, int loginCoins,
			int streak, List<QuestView> quests, int adsWatched, int adsPerDay, int adCoins, boolean adsEnabled, int balance,
			long resetsInSeconds, int weeklyBonus, boolean weeklyBonusToday) {
	}

	public RewardsView rewards(String uuid) throws Exception {
		String day = today();
		StoreConfig.Rewards r = config.rewards;
		StoreDatabase.Daily daily = db.daily(uuid, day);
		int onlineMinutes = (int) (daily.onlineSeconds() / 60);
		int streak = db.loginStreakBefore(uuid, day);
		int loginCoins = r.dailyLoginCoins + Math.min(r.streakBonusMax, streak * r.streakBonusPerDay);
		// Every 7th day in a row (day 7, 14, 21...) pays an extra weekly bonus on top.
		boolean weeklyToday = r.weeklyStreakBonus > 0 && (streak + 1) % 7 == 0;
		if (weeklyToday) {
			loginCoins += r.weeklyStreakBonus;
		}
		Map<String, Long> stats = db.stats(uuid, day);
		Set<String> claimed = db.claimedQuests(uuid, day);
		List<QuestView> quests = new ArrayList<>();
		for (StoreConfig.Quest q : questsFor(uuid, day)) {
			long progress = q.stat().equals("online_minutes") ? onlineMinutes : stats.getOrDefault(q.stat(), 0L);
			boolean done = progress >= q.target();
			boolean enoughTime = onlineMinutes >= q.minOnlineMinutes();
			String hint = done && !enoughTime ? "Unlocks after " + q.minOnlineMinutes() + " min in game today" : null;
			quests.add(new QuestView(q.id(), q.title(), Math.min(progress, q.target()), q.target(), q.coins(),
					claimed.contains(q.id()), done && enoughTime && !claimed.contains(q.id()), hint));
		}
		long resetsIn = java.time.Duration.between(java.time.ZonedDateTime.now(ZoneOffset.UTC),
				LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC)).getSeconds();
		return new RewardsView(day, onlineMinutes, daily.loginClaimed(),
				!daily.loginClaimed() && onlineMinutes >= r.loginMinOnlineMinutes, loginCoins, streak, quests,
				daily.adsWatched(), r.adsPerDay, r.adCoins, adsEnabled(), db.balance(uuid), resetsIn, r.weeklyStreakBonus, weeklyToday);
	}

	/** Today's quests for a player: a fixed pick per player and day, at most one play-time quest. */
	List<StoreConfig.Quest> questsFor(String uuid, String day) {
		List<StoreConfig.Quest> pool = new ArrayList<>(config.rewards.quests);
		java.util.Collections.shuffle(pool, new Random((uuid + "|" + day).hashCode() * 31L + 7));
		List<StoreConfig.Quest> picked = new ArrayList<>();
		boolean hasTime = false;
		for (StoreConfig.Quest q : pool) {
			if (picked.size() >= config.rewards.questsPerDay) {
				break;
			}
			boolean time = q.stat().equals("online_minutes");
			if (time && hasTime) {
				continue;
			}
			hasTime |= time;
			picked.add(q);
		}
		return picked;
	}

	/**
	 * Client-reported counters for quests. Each is capped to what's humanly possible since the
	 * previous report (and counters only matter for today's quests anyway).
	 */
	public void reportProgress(String uuid, Map<String, Long> deltas) throws Exception {
		long now = System.currentTimeMillis();
		Long last = lastStatReport.put(uuid, now);
		double minutes = last == null ? 1 : Math.min(5, Math.max(0.05, (now - last) / 60000.0));
		String day = today();
		for (Map.Entry<String, Long> entry : deltas.entrySet()) {
			Integer rate = STAT_RATE_PER_MINUTE.get(entry.getKey());
			if (rate == null || entry.getValue() == null || entry.getValue() <= 0) {
				continue;
			}
			long allowed = (long) Math.ceil(rate * minutes);
			db.addStat(uuid, day, entry.getKey(), Math.min(entry.getValue(), allowed));
		}
	}

	public RewardsView claimLogin(String uuid) throws Exception {
		RewardsView view = rewards(uuid);
		if (view.loginClaimed()) {
			throw new StoreException(409, "Already claimed today - come back tomorrow");
		}
		if (!view.loginClaimable()) {
			throw new StoreException(409, "Play for " + config.rewards.loginMinOnlineMinutes + " minutes with Velo Client today first");
		}
		db.claimLogin(uuid, view.day(), view.loginCoins());
		return rewards(uuid);
	}

	public RewardsView claimQuest(String uuid, String questId) throws Exception {
		RewardsView view = rewards(uuid);
		QuestView quest = view.quests().stream().filter(q -> q.id().equals(questId)).findFirst()
				.orElseThrow(() -> new StoreException(404, "That quest isn't one of today's"));
		if (quest.claimed()) {
			throw new StoreException(409, "Already claimed");
		}
		if (!quest.claimable()) {
			throw new StoreException(409, quest.hint() != null ? quest.hint() : "Not finished yet");
		}
		db.claimQuest(uuid, view.day(), questId, quest.coins());
		return rewards(uuid);
	}

	public record AdStartView(String url) {
	}

	public AdStartView startAd(String uuid) throws Exception {
		if (!adsEnabled()) {
			throw new StoreException(503, "Ads aren't set up on this server yet");
		}
		if (db.daily(uuid, today()).adsWatched() >= config.rewards.adsPerDay) {
			throw new StoreException(409, "You've watched today's ads - more tomorrow");
		}
		adTokens.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
		String token = randomHex(16);
		adTokens.put(token, new AdToken(uuid, System.currentTimeMillis() + 60 * 60_000L));
		return new AdStartView(publicUrl + "/rewards/ad?t=" + token);
	}

	public boolean validAdToken(String token) {
		AdToken t = token == null ? null : adTokens.get(token);
		return t != null && t.expiresAt() > System.currentTimeMillis();
	}

	public AdConfig adConfig() {
		return ads;
	}

	/** The player behind an ad page link, or null once it expired. */
	public String adTokenUuid(String token) {
		AdToken t = token == null ? null : adTokens.get(token);
		return t != null && t.expiresAt() > System.currentTimeMillis() ? t.uuid() : null;
	}

	public RewardsView adStatus(String token) throws Exception {
		AdToken t = adTokens.get(token);
		return t == null ? null : rewards(t.uuid());
	}

	/**
	 * ayeT-Studios' server-to-server callback for a completed rewarded video. The callback URL set in
	 * the ayeT dashboard is {@code /v1/rewards/ayet?uid={external_identifier}&tid={transaction_id}};
	 * ayeT signs the request in the {@code X-Ayetstudios-Security-Hash} header: hex HMAC-SHA256 (key =
	 * publisher API key) of all query parameters sorted by name and re-encoded like PHP's
	 * {@code http_build_query}. Coins come from our config, never from the request.
	 *
	 * @return null if the signature is wrong (403), otherwise a short status (always HTTP 200, so ayeT
	 *         doesn't keep retrying a callback we've already handled)
	 */
	public String ayetCallback(Map<String, String> params, String signature) throws Exception {
		if (!adsEnabled() || signature == null) {
			return null;
		}
		String expected = hmacSha256Hex(ads.apiKey(), phpQueryString(params));
		if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
			return null;
		}
		String uuid = nz(params.get("uid")).replace("-", "").toLowerCase(Locale.ROOT);
		String tid = params.get("tid");
		if (tid == null || tid.isBlank() || !uuid.matches("[0-9a-f]{32}")) {
			return "ignored";
		}
		return switch (db.creditAd("ayet:" + tid, uuid, today(), config.rewards.adCoins, config.rewards.adsPerDay)) {
			case CREDITED -> "credited";
			case DUPLICATE -> "duplicate";
			case CAPPED -> "capped";
		};
	}

	/** {@code ksort} + {@code http_build_query}: name-sorted, form-encoded the way PHP's urlencode does it. */
	static String phpQueryString(Map<String, String> params) {
		StringBuilder out = new StringBuilder();
		for (String key : new java.util.TreeMap<>(params).keySet()) {
			if (out.length() > 0) {
				out.append('&');
			}
			out.append(phpUrlEncode(key)).append('=').append(phpUrlEncode(params.get(key)));
		}
		return out.toString();
	}

	private static String phpUrlEncode(String value) {
		// Java's URLEncoder matches PHP's urlencode except that it leaves '*' alone.
		return java.net.URLEncoder.encode(nz(value), StandardCharsets.UTF_8).replace("*", "%2A");
	}

	static String hmacSha256Hex(String key, String data) throws Exception {
		javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
		mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}

	private String randomHex(int bytes) {
		byte[] buffer = new byte[bytes];
		random.nextBytes(buffer);
		return HexFormat.of().formatHex(buffer);
	}

	/** Owner list from VELO_OWNERS: uuids (dashed or not) and/or usernames, comma separated. */
	public static Set<String> parseOwners(String env, Function<String, String> normalizeUuid) {
		Set<String> out = new java.util.HashSet<>();
		if (env == null) {
			return out;
		}
		for (String part : env.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String uuid = normalizeUuid.apply(trimmed);
			out.add(uuid != null ? uuid : trimmed.toLowerCase(Locale.ROOT));
		}
		return out;
	}
}
