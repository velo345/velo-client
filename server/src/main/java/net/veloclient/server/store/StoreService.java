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
	/** The cosmetics catalog; without one (tests) the items in store.json are sold. */
	private volatile CosmeticsService cosmetics;
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

	public StoreConfig config() {
		return config;
	}

	/** Told when a real-money coin pack was credited (the donation announcements in Discord). */
	public interface PurchaseListener {
		void coinsPurchased(StoreDatabase.Order order, String username, int balance);

		/** Someone started (or renewed) Velo+. */
		default void plusStarted(String uuid, String username) {
		}
	}

	private volatile PurchaseListener purchaseListener;

	public void setPurchaseListener(PurchaseListener listener) {
		this.purchaseListener = listener;
	}

	public StoreDatabase database() {
		return db;
	}

	/** Gives a cosmetic without charging (system grants such as the Discord link reward); true if it's new to the player. */
	public boolean grantSystem(String uuid, String username, String itemId, String actor) throws Exception {
		if (!knownItem(itemId)) {
			throw new StoreException(404, "Unknown item " + itemId);
		}
		db.touchWallet(uuid, username);
		return db.grantItem(uuid, itemId, actor);
	}

	public void setCosmetics(CosmeticsService cosmetics) {
		this.cosmetics = cosmetics;
	}

	public CosmeticsService cosmetics() {
		return cosmetics;
	}

	/** Any cosmetic the server knows (in the store or not). */
	public boolean knownItem(String itemId) {
		return cosmetics != null ? cosmetics.exists(itemId) : config.item(itemId) != null;
	}

	/** An item as sold right now (name + price), or null when it isn't for sale. */
	private StoreConfig.Item sellable(String itemId) {
		if (cosmetics == null) {
			return config.item(itemId);
		}
		CosmeticsService.Item item = cosmetics.buyable(itemId) ? cosmetics.item(itemId) : null;
		if (item == null) {
			return null;
		}
		Integer deal = dailyDeals().prices().get(itemId);
		return new StoreConfig.Item(item.id, item.name, deal != null ? deal : item.price);
	}

	/** Every configured owner uuid (VELO_OWNERS entries that are uuids). */
	public java.util.Set<String> ownerUuids() {
		java.util.Set<String> out = new java.util.HashSet<>();
		owners.stream().filter(o -> o.matches("[0-9a-f]{32}")).forEach(out::add);
		return out;
	}

	/** The first configured owner uuid (VELO_OWNERS keeps its order), or null. */
	public String firstOwnerUuid() {
		return owners.stream().filter(o -> o.matches("[0-9a-f]{32}")).findFirst().orElse(null);
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

	/** An item for sale; {@code fullPrice} is its regular price while it's one of today's deals. */
	public record CatalogItem(String id, String name, int price, Integer fullPrice) {
	}

	/** Today's four picks and when they change (next UTC midnight). */
	public record DailyView(List<String> items, long endsAt) {
	}

	/** One price for every item of a collection that's in the store. */
	public record BundleView(String id, String name, String description, List<String> items, int price) {
	}

	public record CatalogView(String currency, List<StoreConfig.CoinPack> coinPacks, List<CatalogItem> items,
			boolean checkoutEnabled, boolean adsEnabled, DailyView daily, List<BundleView> bundles, PlusOffer plus) {
	}

	/** Velo+ as offered (for visitors who aren't signed in - the website shows the live price). */
	public record PlusOffer(double price, int monthlyCoins, int periodDays) {
	}

	public CatalogView catalog() {
		List<CatalogItem> items = new ArrayList<>();
		DailyDeals deals = dailyDeals();
		for (StoreConfig.Item item : cosmetics != null ? cosmetics.storeItems() : config.items) {
			Integer deal = deals.prices().get(item.id());
			items.add(new CatalogItem(item.id(), item.name(), deal != null ? deal : item.price(), deal != null ? item.price() : null));
		}
		return new CatalogView(config.currency, config.coinPacks, items, checkoutEnabled(), adsEnabled(),
				new DailyView(deals.items(), LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()),
				bundles(), new PlusOffer(config.plus.price, config.plus.monthlyCoins, config.plus.periodDays));
	}

	// ---- Daily picks & bundles ----

	/** Off the first and second daily pick. */
	private static final int[] DAILY_DISCOUNTS = {30, 20};
	private static final int DAILY_PICKS = 4;
	private static final int BUNDLE_DISCOUNT = 20;

	private record DailyDeals(String day, List<String> items, Map<String, Integer> prices) {
	}

	private volatile DailyDeals dailyDeals;

	/** Four store items a day, the same for everyone (seeded by the date); the first two are discounted. */
	private DailyDeals dailyDeals() {
		String day = today();
		DailyDeals current = dailyDeals;
		if (current != null && current.day().equals(day)) {
			return current;
		}
		List<StoreConfig.Item> pool = new ArrayList<>(cosmetics != null ? cosmetics.storeItems() : config.items);
		pool.removeIf(item -> item.price() <= 0);
		pool.sort(java.util.Comparator.comparing(StoreConfig.Item::id));
		java.util.Collections.shuffle(pool, new Random(day.hashCode() * 31L + 7));
		List<String> picks = new ArrayList<>();
		Map<String, Integer> prices = new java.util.HashMap<>();
		for (int i = 0; i < Math.min(DAILY_PICKS, pool.size()); i++) {
			StoreConfig.Item item = pool.get(i);
			picks.add(item.id());
			if (i < DAILY_DISCOUNTS.length) {
				prices.put(item.id(), discounted(item.price(), DAILY_DISCOUNTS[i]));
			}
		}
		DailyDeals fresh = new DailyDeals(day, List.copyOf(picks), Map.copyOf(prices));
		dailyDeals = fresh;
		return fresh;
	}

	/** {@code percent} off, rounded to tens, never free. */
	static int discounted(int price, int percent) {
		int cut = (int) Math.round(price * (100 - percent) / 1000.0) * 10;
		return Math.max(10, Math.min(price, cut));
	}

	/**
	 * The owner's bundles; while the owner hasn't made any, every collection with at least two items in the
	 * store, {@value #BUNDLE_DISCOUNT}% off together.
	 */
	public List<BundleView> bundles() {
		List<BundleView> out = new ArrayList<>();
		if (cosmetics == null) {
			return out;
		}
		List<CosmeticsService.StoreBundle> own = cosmetics.storeBundles();
		if (own != null) {
			for (CosmeticsService.StoreBundle b : own) {
				out.add(new BundleView(b.id(), b.name(), b.description(), b.items().stream().map(StoreConfig.Item::id).toList(), b.price()));
			}
			return out;
		}
		for (CosmeticsService.StoreCollection collection : cosmetics.storeCollections()) {
			if (collection.items().size() < 2) {
				continue;
			}
			int sum = collection.items().stream().mapToInt(StoreConfig.Item::price).sum();
			out.add(new BundleView(collection.id(), collection.name(), collection.description(),
					collection.items().stream().map(StoreConfig.Item::id).toList(), discounted(sum, BUNDLE_DISCOUNT)));
		}
		return out;
	}

	/** Buys the items of a bundle you don't own yet; owning some lowers the price by their share. */
	private WalletView buyBundle(String uuid, String username, String bundleId) throws Exception {
		BundleView bundle = bundles().stream().filter(b -> b.id().equals(bundleId)).findFirst()
				.orElseThrow(() -> new StoreException(404, "That bundle isn't available right now"));
		int full = 0;
		int missing = 0;
		for (String id : bundle.items()) {
			int price = cosmetics.item(id).price;
			full += price;
			if (!db.owns(uuid, id)) {
				missing += price;
			}
		}
		// Owning part of it: pay the bundle's rate for the rest.
		int price = missing == full ? bundle.price() : Math.max(10, (int) Math.round((double) bundle.price() * missing / full / 10.0) * 10);
		switch (db.buyItems(uuid, bundle.items(), price, "bundle:" + bundleId)) {
			case ALREADY_OWNED -> throw new StoreException(409, "You already own everything in " + bundle.name());
			case INSUFFICIENT_COINS -> throw new StoreException(402, "Not enough Velo Coins - " + bundle.name() + " costs " + price);
			default -> {
			}
		}
		return wallet(uuid, username);
	}

	// ---- Gifts & wishlist ----

	public WalletView gift(String uuid, String username, String itemId, String to) throws Exception {
		if (to == null || to.isBlank()) {
			throw new StoreException(400, "Who is it for?");
		}
		StoreConfig.Item item = sellable(itemId);
		if (item == null) {
			throw new StoreException(404, "That item isn't for sale right now");
		}
		String recipient = db.uuidByUsername(to.trim());
		if (recipient == null && resolver != null) {
			try {
				String[] resolved = resolver.resolve(to.trim());
				recipient = resolved == null ? null : resolved[0];
			} catch (Exception ignored) {
				// Unknown name: answered below.
			}
		}
		if (recipient == null) {
			throw new StoreException(404, to.trim() + " hasn't used Velo Client yet");
		}
		if (recipient.equals(uuid)) {
			throw new StoreException(400, "That's you - buy it instead");
		}
		switch (db.giftItem(uuid, recipient, itemId, item.price())) {
			case ALREADY_OWNED -> throw new StoreException(409, to.trim() + " already owns " + item.name());
			case INSUFFICIENT_COINS -> throw new StoreException(402, "Not enough Velo Coins - " + item.name() + " costs " + item.price());
			default -> {
			}
		}
		return wallet(uuid, username);
	}

	public List<String> wishlist(String uuid) throws Exception {
		return db.wishlist(uuid);
	}

	public List<String> setWishlisted(String uuid, String itemId, boolean on) throws Exception {
		if (itemId == null || !knownItem(itemId)) {
			throw new StoreException(404, "Unknown item");
		}
		db.setWishlisted(uuid, itemId, on);
		return db.wishlist(uuid);
	}

	// ---- Referrals ----

	public static final int REFERRAL_FRIEND_COINS = 300;
	public static final long REFERRAL_PLAY_SECONDS = 2 * 3600;
	public static final int REFERRAL_WINDOW_DAYS = 7;
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	/** One rung of the ladder: invites {@code from}..{@code to} (to = 0: and up) pay {@code coins} each. */
	public record LadderStep(int from, int to, int coins) {
	}

	public static final List<LadderStep> REFERRAL_LADDER = List.of(new LadderStep(1, 2, 300), new LadderStep(3, 5, 400),
			new LadderStep(6, 10, 500));

	/** Paid invites per player, so farming invites with alt accounts stays worth little (4,300 coins at most). */
	public static final int MAX_PAID_INVITES = 10;

	/** What the referrer gets for an invite when {@code paidBefore} invites were already paid (0 past the cap). */
	public static int referrerReward(int paidBefore) {
		int n = paidBefore + 1;
		for (LadderStep step : REFERRAL_LADDER) {
			if (n <= step.to()) {
				return step.coins();
			}
		}
		return 0;
	}

	public record InviteView(String username, long joinedAt, long playedSeconds, boolean rewarded, int coins) {
	}

	public record InvitedByView(String username, boolean rewarded, long playedSeconds) {
	}

	public record ReferralView(String code, int friendCoins, long targetSeconds, List<LadderStep> ladder, int maxInvites, int paidInvites,
			int earned, int nextReward, List<InviteView> invites, InvitedByView invitedBy, boolean canRedeem, Long redeemUntil) {
	}

	private final Set<String> pendingReferrals = ConcurrentHashMap.newKeySet();
	private volatile boolean pendingLoaded;

	private void loadPendingReferrals() {
		if (pendingLoaded) {
			return;
		}
		try {
			pendingReferrals.addAll(db.pendingReferrals());
			pendingLoaded = true;
		} catch (Exception e) {
			System.out.println("[store] Couldn't load pending referrals: " + e.getMessage());
		}
	}

	private String ensureReferralCode(String uuid) throws Exception {
		String code = db.referralCode(uuid);
		while (code == null) {
			StringBuilder fresh = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				fresh.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
			}
			if (db.setReferralCode(uuid, fresh.toString())) {
				code = fresh.toString();
			} else {
				code = db.referralCode(uuid); // raced with ourselves, or the code was taken - try again
			}
		}
		return code;
	}

	/** Epoch millis until which the player may still enter a code, or null while they haven't played yet. */
	private Long redeemUntil(String uuid) throws Exception {
		String first = db.firstDay(uuid);
		return first == null ? null
				: LocalDate.parse(first).plusDays(REFERRAL_WINDOW_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	public ReferralView referral(String uuid, String username) throws Exception {
		db.touchWallet(uuid, username);
		String code = ensureReferralCode(uuid);
		List<InviteView> invites = new ArrayList<>();
		int paid = 0;
		int earned = 0;
		for (StoreDatabase.Referral r : db.referralsBy(uuid)) {
			boolean rewarded = r.rewardedAt() > 0;
			if (rewarded) {
				paid++;
				earned += r.referrerCoins();
			}
			String name = db.username(r.referred());
			invites.add(new InviteView(name == null ? "Player" : name, r.createdAt(),
					rewarded ? REFERRAL_PLAY_SECONDS : Math.min(REFERRAL_PLAY_SECONDS, db.totalOnlineSeconds(r.referred())), rewarded,
					rewarded ? r.referrerCoins() : 0));
		}
		StoreDatabase.Referral mine = db.referredBy(uuid);
		InvitedByView invitedBy = null;
		if (mine != null) {
			String name = db.username(mine.referrer());
			boolean rewarded = mine.rewardedAt() > 0;
			invitedBy = new InvitedByView(name == null ? "Player" : name, rewarded,
					rewarded ? REFERRAL_PLAY_SECONDS : Math.min(REFERRAL_PLAY_SECONDS, db.totalOnlineSeconds(uuid)));
			if (rewarded) {
				earned += REFERRAL_FRIEND_COINS;
			}
		}
		Long until = redeemUntil(uuid);
		boolean canRedeem = mine == null && (until == null || System.currentTimeMillis() < until);
		return new ReferralView(code, REFERRAL_FRIEND_COINS, REFERRAL_PLAY_SECONDS, REFERRAL_LADDER, MAX_PAID_INVITES, paid, earned, referrerReward(paid), invites,
				invitedBy, canRedeem, canRedeem ? until : null);
	}

	/** Enters a friend's code (or their name): one referrer per account, within the first days of playing. */
	public ReferralView redeemReferral(String uuid, String username, String input) throws Exception {
		String cleaned = input == null ? "" : input.trim();
		if (cleaned.isEmpty()) {
			throw new StoreException(400, "Enter a friend's code");
		}
		db.touchWallet(uuid, username);
		String referrer = db.uuidForReferralCode(cleaned.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
		if (referrer == null) {
			referrer = db.uuidByUsername(cleaned);
		}
		if (referrer == null) {
			throw new StoreException(404, "No player has that code");
		}
		if (referrer.equals(uuid)) {
			throw new StoreException(400, "That's your own code");
		}
		if (db.referredBy(uuid) != null) {
			throw new StoreException(409, "You already entered a friend's code");
		}
		Long until = redeemUntil(uuid);
		if (until != null && System.currentTimeMillis() >= until) {
			throw new StoreException(403, "Codes can only be entered in your first " + REFERRAL_WINDOW_DAYS + " days");
		}
		StoreDatabase.Referral theirs = db.referredBy(referrer);
		if (theirs != null && theirs.referrer().equals(uuid)) {
			throw new StoreException(400, "You invited them - you can't both invite each other");
		}
		if (db.referralsBy(referrer).stream().filter(r -> r.rewardedAt() > 0).count() >= MAX_PAID_INVITES) {
			throw new StoreException(403, "That code has already been used " + MAX_PAID_INVITES + " times");
		}
		if (!db.addReferral(uuid, referrer)) {
			throw new StoreException(409, "You already entered a friend's code");
		}
		loadPendingReferrals();
		pendingReferrals.add(uuid);
		checkReferral(uuid);
		return referral(uuid, username);
	}

	/** Pays an invite once the invited player has played long enough. */
	private void checkReferral(String uuid) {
		try {
			if (db.totalOnlineSeconds(uuid) >= REFERRAL_PLAY_SECONDS) {
				db.rewardReferral(uuid, REFERRAL_FRIEND_COINS, StoreService::referrerReward);
				pendingReferrals.remove(uuid);
			}
		} catch (Exception e) {
			System.out.println("[store] referral payout failed: " + e.getMessage());
		}
	}

	public record WalletView(int balance, List<String> owned, boolean owner, boolean plus, long plusUntil) {
	}

	public WalletView wallet(String uuid, String username) throws Exception {
		db.touchWallet(uuid, username);
		return new WalletView(db.balance(uuid), db.ownedItems(uuid), isOwner(uuid, username), isPlus(uuid), plusUntil(uuid));
	}

	// ---- Velo+ ----

	private final Map<String, Long> plusCache = new ConcurrentHashMap<>();

	/** Loads the active memberships into memory (start-up, and after every change): {@link #isPlus} is asked for every online player. */
	public void loadPlus() {
		try {
			Map<String, Long> fresh = new java.util.HashMap<>();
			for (StoreDatabase.PlusRow row : db.activePlus(System.currentTimeMillis())) {
				fresh.put(row.uuid(), row.until());
			}
			plusCache.keySet().retainAll(fresh.keySet());
			plusCache.putAll(fresh);
		} catch (Exception e) {
			System.out.println("[store] Couldn't load Velo+ memberships: " + e.getMessage());
		}
	}

	/** Staff of Velo Client have Velo+ for as long as they're staff (set by the server app). */
	private volatile java.util.function.Predicate<String> staffLookup = uuid -> false;

	public void setStaffLookup(java.util.function.Predicate<String> lookup) {
		this.staffLookup = lookup;
	}

	public boolean isPlus(String uuid) {
		Long until = plusCache.get(uuid);
		return until != null && until > System.currentTimeMillis() || staffLookup.test(uuid);
	}

	/** When the membership ends (0 if none or over); staff always show at least 30 days ahead. */
	public long plusUntil(String uuid) {
		Long until = plusCache.get(uuid);
		long real = until != null && until > System.currentTimeMillis() ? until : 0;
		return staffLookup.test(uuid) ? Math.max(real, System.currentTimeMillis() + 30L * 86_400_000L) : real;
	}

	/**
	 * Adds a month (or {@code days}) of Velo+ and pays out the monthly coins if they're due. A Tebex payment
	 * that arrives while an earlier Tebex month still has most of its time left (a renewal reported twice by
	 * two different events) is recorded without adding days again.
	 */
	public long grantPlus(String uuid, String username, int days, String source, String txn, String reference) throws Exception {
		db.touchWallet(uuid, username);
		int add = days;
		StoreDatabase.PlusRow current = db.plusRow(uuid);
		long now = System.currentTimeMillis();
		if ("tebex".equals(source) && txn != null && current != null && "tebex".equals(current.source())
				&& current.until() - now > (config.plus.periodDays - 3) * 86_400_000L) {
			add = 0;
		}
		long until = db.extendPlus(uuid, add, source, txn, reference);
		loadPlus();
		plusSweep();
		return until;
	}

	/** Owner: ends someone's Velo+ now. */
	public void endPlus(String uuid) throws Exception {
		db.endPlus(uuid);
		loadPlus();
	}

	/** Pays the monthly Velo Coins to every active member whose last payout is a month ago (or never) - run hourly and after a grant. */
	public synchronized void plusSweep() {
		try {
			long now = System.currentTimeMillis();
			long month = config.plus.periodDays * 86_400_000L;
			for (StoreDatabase.PlusRow row : db.activePlus(now)) {
				if (row.lastCoins() == 0 || now - row.lastCoins() >= month) {
					if (config.plus.monthlyCoins > 0) {
						db.touchWallet(row.uuid(), null);
						db.adjust(row.uuid(), config.plus.monthlyCoins, "plus_monthly", null, "plus");
					}
					db.markPlusCoins(row.uuid(), now);
				}
			}
		} catch (Exception e) {
			System.out.println("[store] Velo+ coin payout failed: " + e.getMessage());
		}
	}

	public record PlusCheckoutView(String checkoutUrl) {
	}

	public PlusCheckoutView plusCheckout(String uuid, String username) throws Exception {
		if (!checkoutEnabled()) {
			throw new StoreException(503, "Subscriptions aren't set up on this server yet");
		}
		TebexCheckout.Basket basket = tebex.createSubscriptionCheckout(uuid, username, "Velo+ (monthly)" + (username != null ? " for " + username : ""),
				config.plus.price, publicUrl + "/store/done?cancelled=1", publicUrl + "/store/done?plus=1");
		return new PlusCheckoutView(basket.checkoutUrl());
	}

	/** Store capes are published by item id; only owners may show them (anything else is a custom/other id). */
	public boolean mayDisplayCape(String uuid, String capeId) {
		if (capeId == null || !knownItem(capeId)) {
			return true;
		}
		try {
			return db.owns(uuid, capeId);
		} catch (Exception e) {
			return false;
		}
	}

	public WalletView buy(String uuid, String username, String itemId) throws Exception {
		if (itemId != null && itemId.startsWith("bundle:")) {
			return buyBundle(uuid, username, itemId.substring("bundle:".length()));
		}
		StoreConfig.Item item = sellable(itemId);
		if (item == null) {
			throw new StoreException(404, "That item isn't for sale right now");
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
				case "recurring-payment.renewed" -> handleRenewal(subject, id);
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
			if (custom.has("velo_plus") && custom.has("velo_uuid")) {
				// The Velo+ subscription: a month, then the coins and perks follow from the membership.
				String plusUuid = custom.get("velo_uuid").getAsString();
				String plusName = custom.has("velo_username") ? custom.get("velo_username").getAsString() : null;
				String reference = subject.has("recurring_payment_reference") ? subject.get("recurring_payment_reference").getAsString() : null;
				grantPlus(plusUuid, plusName, config.plus.periodDays, "tebex", txn == null ? "plus:" + System.nanoTime() : txn, reference);
				System.out.println("[store] Velo+ month for " + plusUuid + " (" + txn + ")");
				PurchaseListener listener = purchaseListener;
				if (listener != null) {
					listener.plusStarted(plusUuid, plusName == null ? db.username(plusUuid) : plusName);
				}
				continue;
			}
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
				PurchaseListener listener = purchaseListener;
				if (listener != null) {
					try {
						listener.coinsPurchased(order, db.username(order.uuid()), db.balance(order.uuid()));
					} catch (Exception e) {
						System.out.println("[store] Purchase announcement failed: " + e.getMessage());
					}
				}
			}
		}
	}

	/**
	 * A Velo+ renewal reported as a recurring-payment event: matched to the member by the first payment's
	 * transaction / the recurring reference, and counted once per webhook delivery.
	 */
	private void handleRenewal(com.google.gson.JsonObject subject, String eventId) throws Exception {
		List<String> keys = new ArrayList<>();
		for (String field : new String[] {"reference", "id", "transaction_id"}) {
			if (subject.has(field) && subject.get(field).isJsonPrimitive()) {
				keys.add(subject.get(field).getAsString());
			}
		}
		if (subject.has("initial_payment") && subject.get("initial_payment").isJsonObject()) {
			com.google.gson.JsonObject initial = subject.getAsJsonObject("initial_payment");
			for (String field : new String[] {"transaction_id", "id", "reference"}) {
				if (initial.has(field) && initial.get(field).isJsonPrimitive()) {
					keys.add(initial.get(field).getAsString());
				}
			}
		}
		for (String key : keys) {
			String uuid = db.plusMemberOfPayment(key);
			if (uuid != null) {
				grantPlus(uuid, db.username(uuid), config.plus.periodDays, "tebex", "renew:" + eventId, keys.get(0));
				System.out.println("[store] Velo+ renewed for " + uuid);
				return;
			}
		}
		System.out.println("[store] Velo+ renewal event " + eventId + " didn't match a member (" + keys + ")");
	}

	private void reverse(String txn, String status) throws Exception {
		if (txn == null) {
			return;
		}
		String plusMember = db.reversePlusPayment(txn);
		if (plusMember != null) {
			loadPlus();
			System.out.println("[store] " + status + " on " + txn + " - Velo+ days taken back from " + plusMember);
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

	public record PlayerView(String uuid, String username, int balance, List<String> owned, List<StoreDatabase.LedgerEntry> ledger, long plusUntil) {
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
		return new PlayerView(ref[0], ref[1], db.balance(ref[0]), db.ownedItems(ref[0]), db.ledger(ref[0], 30), plusUntil(ref[0]));
	}

	/** Owner: gives {@code days} of Velo+ (more than 0), or ends it ({@code days} 0 or less). */
	public PlayerView adminPlus(String meUuid, String meName, String target, int days) throws Exception {
		String[] ref = requireOwnerAndResolve(meUuid, meName, target);
		if (days > 3650) {
			throw new StoreException(400, "At most 3650 days");
		}
		if (days > 0) {
			grantPlus(ref[0], ref[1], days, "owner:" + meName, null, null);
		} else {
			endPlus(ref[0]);
		}
		return adminLookup(meUuid, meName, ref[0]);
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
		if (!knownItem(itemId)) {
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
			loadPendingReferrals();
			if (pendingReferrals.contains(uuid)) {
				checkReferral(uuid);
			}
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
		Set<String> out = new java.util.LinkedHashSet<>();
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
