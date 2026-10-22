package net.veloclient.launcher.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * The launcher's client for the Velo Coins economy on the Velo server (wallet, store purchases,
 * coin packs, daily rewards, owner tools). Calls block - run them off the FX thread. The last
 * wallet answer is cached for quick display ({@link #balance()}, {@link #owns}).
 */
public final class StoreApi {

	/** A failed request, with the server's own message (shown to the player as-is). */
	public static final class StoreError extends Exception {
		public final int status;

		StoreError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private static volatile int balance = -1;
	private static volatile Set<String> owned = Set.of();
	private static volatile boolean walletLoaded;

	/** True once the wallet (what you own) came from the server this run. */
	public static boolean walletLoaded() {
		return walletLoaded;
	}
	private static volatile boolean owner;

	private StoreApi() {
	}

	public static int balance() {
		return balance;
	}

	private static final java.util.List<Runnable> WALLET_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Runs {@code listener} (on whatever thread fetched it) whenever the balance or owned items change. */
	public static void onWalletChanged(Runnable listener) {
		WALLET_LISTENERS.add(listener);
	}

	private static void fireWallet() {
		WALLET_LISTENERS.forEach(Runnable::run);
	}

	public static boolean owns(String itemId) {
		return owned.contains(itemId);
	}

	public static Set<String> owned() {
		return owned;
	}

	public static boolean isOwner() {
		return owner;
	}

	public static boolean connected() {
		return DEMO || LauncherSocial.sessionToken() != null && LauncherSocial.baseUrl() != null;
	}

	public static JsonObject catalog() throws StoreError {
		JsonObject catalog = get("/v1/store/catalog");
		if (catalog.has("items") && catalog.get("items").isJsonArray()) {
			java.util.Map<String, Integer> map = new java.util.HashMap<>();
			java.util.Map<String, Integer> full = new java.util.HashMap<>();
			java.util.Map<String, String> tiers = new java.util.HashMap<>();
			catalog.getAsJsonArray("items").forEach(e -> {
				JsonObject item = e.getAsJsonObject();
				if (item.has("id") && item.has("price")) {
					String id = item.get("id").getAsString();
					map.put(id, item.get("price").getAsInt());
					if (item.has("fullPrice") && !item.get("fullPrice").isJsonNull()) {
						full.put(id, item.get("fullPrice").getAsInt());
					}
					if (item.has("rarity") && !item.get("rarity").isJsonNull()) {
						tiers.put(id, item.get("rarity").getAsString());
					}
				}
			});
			prices = java.util.Map.copyOf(map);
			fullPrices = java.util.Map.copyOf(full);
			rarities = java.util.Map.copyOf(tiers);
		}
		lastCatalog = catalog;
		return catalog;
	}

	private static volatile java.util.Map<String, Integer> prices = java.util.Map.of();

	/** The server's price for an item (after {@link #catalog()} ran), else {@code fallback}. */
	private static volatile java.util.Map<String, Integer> fullPrices = java.util.Map.of();
	private static volatile java.util.Map<String, String> rarities = java.util.Map.of();
	private static volatile JsonObject lastCatalog;

	/** The regular price while an item is discounted (daily deal), else null. */
	public static Integer fullPrice(String itemId) {
		return fullPrices.get(itemId);
	}

	/** The rarity the server set for an item ("rare", "epic", ...), or null. */
	public static String rarity(String itemId) {
		return rarities.get(itemId);
	}

	/** The last catalog fetched this run (daily picks, bundles, coin packs), or null. */
	public static JsonObject cachedCatalog() {
		return lastCatalog;
	}

	public static int price(String itemId, int fallback) {
		Integer price = prices.get(itemId);
		return price != null ? price : fallback;
	}

	public static JsonObject wallet() throws StoreError {
		return applyWallet(get("/v1/store/wallet"));
	}

	public static JsonObject buy(String itemId) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("itemId", itemId);
		return applyWallet(post("/v1/store/buy", body));
	}

	public static JsonObject checkout(String packId) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("packId", packId);
		return post("/v1/store/checkout", body);
	}

	public static JsonObject order(String orderId) throws StoreError {
		JsonObject order = get("/v1/store/order?id=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8));
		balance = order.get("balance").getAsInt();
		fireWallet();
		return order;
	}

	public static JsonObject rewards() throws StoreError {
		return trackBalance(get("/v1/rewards"));
	}

	public static JsonObject claimLogin() throws StoreError {
		return trackBalance(post("/v1/rewards/login", new JsonObject()));
	}

	public static JsonObject claimQuest(String questId) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("questId", questId);
		return trackBalance(post("/v1/rewards/quest", body));
	}

	public static String startAd() throws StoreError {
		return post("/v1/rewards/ad", new JsonObject()).get("url").getAsString();
	}

	public static JsonObject adminLookup(String target) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		return post("/v1/admin/store/lookup", body);
	}

	public static JsonObject adminCoins(String target, int amount, String note) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		body.addProperty("amount", amount);
		body.addProperty("note", note);
		return post("/v1/admin/store/coins", body);
	}

	public static JsonObject adminItem(String target, String itemId, boolean grant) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		body.addProperty("itemId", itemId);
		body.addProperty("grant", grant);
		return post("/v1/admin/store/item", body);
	}

	/** Velo+: {@code plus}, {@code until}, {@code price}, {@code currency}, {@code monthlyCoins}, {@code periodDays}, {@code checkoutEnabled}. */
	public static JsonObject plusStatus() throws StoreError {
		JsonObject status = get("/v1/plus/status");
		plusMember = status.has("plus") && status.get("plus").getAsBoolean();
		return status;
	}

	private static volatile boolean plusMember;

	/** Whether the last {@link #plusStatus()} answer said Velo+ is active (false until asked). */
	public static boolean isPlusCached() {
		return plusMember;
	}

	/** Starts the Velo+ subscription: {@code checkoutUrl}. */
	public static JsonObject plusCheckout() throws StoreError {
		return post("/v1/plus/checkout", new JsonObject());
	}

	/** A signed proof of membership ({@code token}) for the game's Addons check (members only). */
	public static JsonObject plusToken() throws StoreError {
		return get("/v1/plus/token");
	}

	/** The Addons jars on the server: {@code addons: [{mc, version, sha256, size}]} (members only). */
	public static JsonObject plusAddons() throws StoreError {
		return get("/v1/plus/addons");
	}

	/** The Addons jar for a Minecraft version (members only). */
	public static byte[] plusAddonJar(String mc) throws StoreError {
		try {
			HttpResponse<byte[]> response = HTTP.send(request("/v1/plus/addons/file?mc=" + java.net.URLEncoder.encode(mc, java.nio.charset.StandardCharsets.UTF_8))
					.timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				throw new StoreError(response.statusCode(), "Couldn't download the Addons (HTTP " + response.statusCode() + ")");
			}
			return response.body();
		} catch (StoreError e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StoreError(0, "Interrupted");
		} catch (Exception e) {
			throw new StoreError(0, "Couldn't reach the Velo server");
		}
	}

	/** Reports a player's chat ({@code context} "dm" or "party") to the Velo Client team. */
	public static JsonObject reportPlayer(String target, String category, String reason, String context, boolean includeHistory) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		body.addProperty("category", category);
		body.addProperty("reason", reason);
		body.addProperty("context", context);
		body.addProperty("includeHistory", includeHistory);
		return post("/v1/social/report", body);
	}

	/** Admins: open (or all) player reports. */
	public static JsonObject staffReports(String status) throws StoreError {
		return get("/v1/staff/reports?status=" + status);
	}

	/** Admins: closes a report, optionally banning the reported player from services for {@code days} (0 = for good). */
	public static JsonObject staffResolve(String id, boolean ban, int days) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("status", ban ? "actioned" : "dismissed");
		if (ban) {
			JsonObject b = new JsonObject();
			com.google.gson.JsonArray services = new com.google.gson.JsonArray();
			services.add("friends");
			services.add("parties");
			b.add("services", services);
			b.addProperty("days", days);
			b.addProperty("reason", "Reported chat");
			body.add("ban", b);
		}
		return post("/v1/staff/reports/resolve", body);
	}

	/** Owner: gives {@code days} of Velo+ (more than 0) or ends it (0). */
	public static JsonObject adminPlus(String target, int days) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		body.addProperty("days", days);
		return post("/v1/admin/store/plus", body);
	}

	/** Whether your Discord is linked ({@code linked}, {@code discordName}) and the invite link ({@code inviteUrl}). */
	public static JsonObject discordStatus() throws StoreError {
		return get("/v1/discord/status");
	}

	/** A code to paste in the Velo Discord's configured link channel ({@code code}, {@code expiresInSeconds}, {@code inviteUrl}). */
	public static JsonObject discordCode() throws StoreError {
		return post("/v1/discord/code", new JsonObject());
	}

	/** Every cosmetic with status, price, window and files; collections; the featured one (owner only). */
	public static JsonObject adminCosmetics() throws StoreError {
		return get("/v1/admin/cosmetics");
	}

	/** Changes a cosmetic - only the fields in {@code item} (plus its id). {@code clearWindow} drops its time window. */
	public static JsonObject adminUpdateCosmetic(JsonObject item, boolean clearWindow) throws StoreError {
		JsonObject body = new JsonObject();
		body.add("item", item);
		body.addProperty("clearWindow", clearWindow);
		return post("/v1/admin/cosmetics/item", body);
	}

	/** Creates or changes a collection (id, name, tag, description). */
	public static JsonObject adminCollection(JsonObject collection) throws StoreError {
		return post("/v1/admin/cosmetics/collection", collection);
	}

	/** Creates or changes an owner bundle (id, name, description, items, price, active, availableUntil). */
	public static JsonObject adminBundle(JsonObject bundle) throws StoreError {
		return post("/v1/admin/cosmetics/bundle", bundle);
	}

	public static JsonObject adminDeleteBundle(String id) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		return post("/v1/admin/cosmetics/bundle/delete", body);
	}

	/** Your invite code, invites and what they earned, who invited you and whether you can still enter a code. */
	public static JsonObject referral() throws StoreError {
		return get("/v1/referral");
	}

	/** Enters a friend's code (or their name). Answers with the fresh {@link #referral()} state. */
	public static JsonObject redeemReferral(String code) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		if (DEMO) {
			throw new StoreError(404, "No player has that code");
		}
		return post("/v1/referral/redeem", body);
	}

	private static java.nio.file.Path pendingReferralFile() {
		return net.veloclient.launcher.data.VeloPaths.config().resolve("pending-referral.txt");
	}

	/** Keeps a friend's code entered before signing in (first start), to redeem once the server knows who you are. */
	public static void savePendingReferral(String code) {
		try {
			java.nio.file.Files.createDirectories(pendingReferralFile().getParent());
			java.nio.file.Files.writeString(pendingReferralFile(), code.trim());
		} catch (java.io.IOException ignored) {
			// Best effort: the Invite page still takes the code later.
		}
	}

	/**
	 * Redeems a code kept by {@link #savePendingReferral}. Blocks - call off the FX thread. Returns null when there's
	 * nothing to do or it worked, else the server's reason (the code is dropped unless it was a connection problem).
	 */
	public static String redeemPendingReferral() {
		java.nio.file.Path file = pendingReferralFile();
		if (!java.nio.file.Files.exists(file) || !connected()) {
			return null;
		}
		try {
			String code = java.nio.file.Files.readString(file).trim();
			if (!code.isEmpty()) {
				redeemReferral(code);
			}
			java.nio.file.Files.deleteIfExists(file);
			return null;
		} catch (StoreError e) {
			if (e.status != 0 && e.status != 401 && e.status / 100 != 5) {
				try {
					java.nio.file.Files.deleteIfExists(file);
				} catch (java.io.IOException ignored) {
					// Tried again next start.
				}
			}
			return e.getMessage();
		} catch (java.io.IOException e) {
			return null;
		}
	}

	/** Buys {@code itemId} for the player called {@code toUsername} (they must have used Velo Client). */
	public static JsonObject gift(String itemId, String toUsername, String note) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("itemId", itemId);
		body.addProperty("to", toUsername);
		body.addProperty("note", note == null ? "" : note);
		return trackBalance(post("/v1/store/gift", body));
	}

	private static final java.util.Set<String> WISHLIST = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static volatile boolean wishlistLoaded;

	/** Items on your wishlist (kept locally, synced with the server when it supports it). */
	public static java.util.Set<String> wishlist() {
		loadWishlist();
		return java.util.Collections.unmodifiableSet(WISHLIST);
	}

	public static boolean wishlisted(String itemId) {
		loadWishlist();
		return WISHLIST.contains(itemId);
	}

	/** Adds or removes {@code itemId}; returns whether it's on the list now. Saves locally at once, tells the server in the background. */
	public static boolean toggleWishlist(String itemId) {
		loadWishlist();
		boolean on = !WISHLIST.remove(itemId);
		if (on) {
			WISHLIST.add(itemId);
		}
		saveWishlist();
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("itemId", itemId);
				body.addProperty("on", on);
				post("/v1/store/wishlist", body);
			} catch (StoreError ignored) {
				// Older server: the local list is enough.
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		return on;
	}

	private static java.nio.file.Path wishlistFile() {
		return net.veloclient.launcher.data.VeloPaths.config().resolve("wishlist.json");
	}

	private static synchronized void loadWishlist() {
		if (wishlistLoaded) {
			return;
		}
		wishlistLoaded = true;
		try {
			if (java.nio.file.Files.exists(wishlistFile())) {
				com.google.gson.JsonArray list = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(wishlistFile())).getAsJsonArray();
				list.forEach(e -> WISHLIST.add(e.getAsString()));
			}
		} catch (Exception ignored) {
			// Unreadable: start empty.
		}
	}

	private static synchronized void saveWishlist() {
		try {
			com.google.gson.JsonArray list = new com.google.gson.JsonArray();
			WISHLIST.forEach(list::add);
			java.nio.file.Files.writeString(wishlistFile(), list.toString());
		} catch (java.io.IOException ignored) {
			// Not fatal.
		}
	}

	/** A 6-digit code for signing in on velo-client.com ({@code code}, {@code expiresInSeconds}). */
	public static JsonObject webCode() throws StoreError {
		return post("/v1/web/code", new JsonObject());
	}

	/** Which collection the store features (null / blank = the newest one). */
	public static void adminFeatured(String collectionId) throws StoreError {
		JsonObject body = new JsonObject();
		body.addProperty("collection", collectionId);
		post("/v1/admin/cosmetics/featured", body);
	}

	private static JsonObject applyWallet(JsonObject wallet) {
		balance = wallet.get("balance").getAsInt();
		owner = wallet.has("owner") && wallet.get("owner").getAsBoolean();
		Set<String> items = new HashSet<>();
		wallet.getAsJsonArray("owned").forEach(e -> items.add(e.getAsString()));
		owned = Set.copyOf(items);
		walletLoaded = true;
		fireWallet();
		return wallet;
	}

	private static JsonObject trackBalance(JsonObject json) {
		if (json.has("balance")) {
			balance = json.get("balance").getAsInt();
			fireWallet();
		}
		return json;
	}

	/** Dev-only: the screenshot tour shows sample numbers instead of talking to the real server. */
	private static final boolean DEMO = System.getProperty("velo.launcherTour") != null;

	private static JsonObject demo(String path) {
		String json = switch (path) {
			case "/v1/store/catalog" -> """
					{"currency":"EUR","checkoutEnabled":true,"coinPacks":[
					{"id":"coins_1200","coins":1200,"bonus":0,"price":4.99},{"id":"coins_2500","coins":2300,"bonus":200,"price":9.99},
					{"id":"coins_5500","coins":4700,"bonus":800,"price":19.99},{"id":"coins_15000","coins":12000,"bonus":3000,"price":49.99}],
					"items":[]}""";
			case "/v1/referral" -> """
					{"code":"K7Q2MX","friendCoins":300,"targetSeconds":7200,"maxInvites":10,"paidInvites":3,"earned":1000,"nextReward":400,
					"ladder":[{"from":1,"to":2,"coins":300},{"from":3,"to":5,"coins":400},{"from":6,"to":10,"coins":500}],
					"invites":[{"username":"Nyxie","joinedAt":1760900000000,"playedSeconds":3120,"rewarded":false,"coins":0},
					{"username":"Kaito_","joinedAt":1760300000000,"playedSeconds":7200,"rewarded":true,"coins":400},
					{"username":"BlockBaron","joinedAt":1759900000000,"playedSeconds":7200,"rewarded":true,"coins":300},
					{"username":"mossy","joinedAt":1759000000000,"playedSeconds":7200,"rewarded":true,"coins":300}],
					"invitedBy":null,"canRedeem":true,"redeemUntil":null}""";
			case "/v1/admin/cosmetics" -> adminDemo();
			case "/v1/plus/status" -> "{\"plus\":false,\"until\":0,\"price\":9.99,\"currency\":\"EUR\",\"monthlyCoins\":2000,\"periodDays\":30,\"checkoutEnabled\":true,\"addonsEnabled\":true}";
			case "/v1/store/wallet" -> "{\"balance\":1340,\"owner\":true,\"owned\":[\"wings_bonedragon\",\"wings_cobweb\",\"wings_wraith\",\"wings_phoenix\",\"aura_orbit\",\"aura_ember\",\"aura_void\"]}";
			default -> """
					{"onlineMinutes":42,"loginClaimed":false,"loginClaimable":true,"loginCoins":12,"streak":3,"quests":[
					{"id":"kill_100","title":"Defeat 100 mobs","progress":64,"target":100,"coins":10,"claimed":false,"claimable":false},
					{"id":"play_60","title":"Play for 1 hour","progress":60,"target":60,"coins":8,"claimed":false,"claimable":true},
					{"id":"walk_5k","title":"Travel 5 km","progress":5000,"target":5000,"coins":8,"claimed":true,"claimable":false}],
					"adsWatched":1,"adsPerDay":3,"adCoins":6,"adsEnabled":false,"resetsInSeconds":18720,"weeklyBonus":40,"weeklyBonusToday":false,"balance":1340}""";
		};
		return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
	}

	/** Tour-only owner data: the bundled wings with mixed statuses and one bundle. */
	private static String adminDemo() {
		JsonObject data = new JsonObject();
		data.addProperty("featured", "");
		com.google.gson.JsonArray collections = new com.google.gson.JsonArray();
		for (var c : net.veloclient.launcher.data.WingsCatalog.collections()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", c.id());
			o.addProperty("name", c.name());
			o.addProperty("tag", c.tag());
			o.addProperty("description", c.description());
			collections.add(o);
		}
		data.add("collections", collections);
		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		com.google.gson.JsonArray bundleItems = new com.google.gson.JsonArray();
		String[] statuses = {"released", "released", "testing", "released", "hidden"};
		int i = 0;
		for (var w : net.veloclient.launcher.data.WingsCatalog.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", w.itemId());
			o.addProperty("name", w.name());
			o.addProperty("price", w.price());
			o.addProperty("status", statuses[i % statuses.length]);
			o.addProperty("accent", w.accent());
			if (bundleItems.size() < 3 && i % statuses.length < 2) {
				bundleItems.add(w.itemId());
			}
			items.add(o);
			i++;
		}
		data.add("items", items);
		com.google.gson.JsonArray bundles = new com.google.gson.JsonArray();
		JsonObject bundle = new JsonObject();
		bundle.addProperty("id", "starter");
		bundle.addProperty("name", "Starter Pack");
		bundle.addProperty("description", "Three favourites for new players");
		bundle.add("items", bundleItems);
		bundle.addProperty("price", 4800);
		bundle.addProperty("active", true);
		bundles.add(bundle);
		data.add("bundles", bundles);
		return data.toString();
	}

	private static JsonObject get(String path) throws StoreError {
		if (DEMO && path.startsWith("/v1/admin/") && System.getProperty("velo.adminToken") != null) {
			// Dev-only tour against a test server (-Dvelo.serverUrl + -Dvelo.adminToken): real owner data.
			return send(request(path).header("X-Velo-Admin-Token", System.getProperty("velo.adminToken")).GET().build());
		}
		if (DEMO) {
			return demo(path);
		}
		return send(request(path).GET().build());
	}

	private static JsonObject post(String path, JsonObject body) throws StoreError {
		return send(request(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
	}

	private static HttpRequest.Builder request(String path) throws StoreError {
		String base = LauncherSocial.baseUrl();
		if (base == null) {
			throw new StoreError(0, "The Velo server is turned off in the launcher settings");
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json")
				.timeout(Duration.ofSeconds(20));
		String token = LauncherSocial.sessionToken();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder;
	}

	private static JsonObject send(HttpRequest request) throws StoreError {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				String message = "Request failed (HTTP " + response.statusCode() + ")";
				try {
					JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject();
					if (error.has("error")) {
						message = error.get("error").getAsString();
					}
				} catch (Exception ignored) {
					// Not JSON.
				}
				if (response.statusCode() == 401) {
					message = "Sign in to use Velo Coins";
				}
				throw new StoreError(response.statusCode(), message);
			}
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (StoreError e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StoreError(0, "Interrupted");
		} catch (Exception e) {
			throw new StoreError(0, "Couldn't reach the Velo server");
		}
	}
}
