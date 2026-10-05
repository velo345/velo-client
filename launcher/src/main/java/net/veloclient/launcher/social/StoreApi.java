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
	private static volatile boolean owner;

	private StoreApi() {
	}

	public static int balance() {
		return balance;
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
		return get("/v1/store/catalog");
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

	private static JsonObject applyWallet(JsonObject wallet) {
		balance = wallet.get("balance").getAsInt();
		owner = wallet.has("owner") && wallet.get("owner").getAsBoolean();
		Set<String> items = new HashSet<>();
		wallet.getAsJsonArray("owned").forEach(e -> items.add(e.getAsString()));
		owned = Set.copyOf(items);
		return wallet;
	}

	private static JsonObject trackBalance(JsonObject json) {
		if (json.has("balance")) {
			balance = json.get("balance").getAsInt();
		}
		return json;
	}

	/** Dev-only: the screenshot tour shows sample numbers instead of talking to the real server. */
	private static final boolean DEMO = System.getProperty("velo.launcherTour") != null;

	private static JsonObject demo(String path) {
		String json = switch (path) {
			case "/v1/store/catalog" -> """
					{"currency":"EUR","checkoutEnabled":true,"coinPacks":[
					{"id":"coins_500","coins":500,"bonus":0,"price":4.99},{"id":"coins_1200","coins":1200,"bonus":140,"price":9.99},
					{"id":"coins_2600","coins":2600,"bonus":400,"price":19.99},{"id":"coins_6500","coins":6500,"bonus":1500,"price":39.99}],
					"items":[]}""";
			case "/v1/store/wallet" -> "{\"balance\":1340,\"owner\":true,\"owned\":[]}";
			default -> """
					{"onlineMinutes":42,"loginClaimed":false,"loginClaimable":true,"loginCoins":12,"streak":3,"quests":[
					{"id":"kill_100","title":"Defeat 100 mobs","progress":64,"target":100,"coins":10,"claimed":false,"claimable":false},
					{"id":"play_60","title":"Play for 1 hour","progress":60,"target":60,"coins":8,"claimed":false,"claimable":true},
					{"id":"walk_5k","title":"Travel 5 km","progress":5000,"target":5000,"coins":8,"claimed":true,"claimable":false}],
					"adsWatched":1,"adsPerDay":3,"adCoins":6,"adsEnabled":false,"resetsInSeconds":18720,"weeklyBonus":40,"weeklyBonusToday":false,"balance":1340}""";
		};
		return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
	}

	private static JsonObject get(String path) throws StoreError {
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
